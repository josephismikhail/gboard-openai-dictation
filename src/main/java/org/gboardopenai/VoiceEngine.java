// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import com.trevornk.ramblr.*;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same streaming recognizer, prompt, vocabulary and fidelity guard as the tested Ramblr setup. */
public final class VoiceEngine {
    public interface Listener {
        void recording();
        default void level(float value) {}
        void result(String text);
        void error(String safeMessage);
    }
    private static final ExecutorService WORK = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final Context context;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean completing = new AtomicBoolean();
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final Object lock = new Object();
    private volatile AudioRecord recorder;
    private volatile boolean stopRequested, ended;
    private volatile CloudLiveTranscriptionSession live;
    private volatile CloudLiveTerminal terminal;
    private volatile Call call;
    private JSONObject config;
    private List<String> vocabulary;
    private volatile DictationContext editorContext;
    private long stoppedAt, audioBytes;

    public VoiceEngine(Context context, Listener listener) {
        this(context,listener,null);
    }
    public VoiceEngine(Context context, Listener listener, DictationContext editorContext) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.editorContext=editorContext;
    }
    public void start() { WORK.execute(this::record); }
    public void stop() { stoppedAt = SystemClock.elapsedRealtime(); stopRequested = true; }
    public void cancel() {
        editorContext=null;
        cancelled.set(true); stopRequested = true;
        CloudLiveTranscriptionSession session = live; if (session != null) session.cancel();
        Call pending = call; if (pending != null) pending.cancel();
    }
    private void record() {
        AudioRecord audio = null;
        try {
            config = VoiceConfig.load(context);
            vocabulary = new ArrayList<>();
            JSONArray terms = config.optJSONArray("vocabulary");
            if (terms != null) for (int i=0; i<terms.length(); i++) vocabulary.add(terms.getString(i));
            List<String> keywords = new ArrayList<>();
            for (String term : vocabulary) {
                String canonical = VocabularyAliases.INSTANCE.canonical(term);
                if (!canonical.isEmpty() && canonical.length() <= 128 && !keywords.contains(canonical)
                    && !canonical.matches(".*[<>\\r\\n].*")) keywords.add(canonical);
                if (keywords.size() == 128) break;
            }
            OpenAiCloudLiveTranscriptionClient factory = new OpenAiCloudLiveTranscriptionClient(
                HTTP, HttpUrl.get("https://api.openai.com/v1/realtime?intent=transcription"),
                config.getString("key"), "gpt-live-transcribe", Collections.singletonList("en"),
                keywords, WORK, TIMER, 10000L, 7000L, System::currentTimeMillis, false);
            live = factory.create(new CloudLiveTranscriptionListener() {
                @Override public void onInterim(String text, CloudLiveTiming timing) {}
                @Override public void onTerminal(CloudLiveTerminal value) { terminal = value; finishIfReady(); }
            });
            live.connect(); live.startActivity();
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Audio format unavailable");
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 16000));
            recorder = audio;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            if (cancelled.get()) return;
            audio.startRecording(); listener.recording();
            byte[] buffer = new byte[3200];
            while (!stopRequested && !cancelled.get()) {
                int count = audio.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IllegalStateException("Microphone read failed");
                acceptAudio(buffer, count);
                if (audioBytes > 16000L * 2 * 600) throw new IllegalStateException("Recording limit reached");
            }
            if (cancelled.get()) return;
            int drained = 0;
            for (int i=0; i<16 && drained<8000; i++) {
                int count = audio.read(buffer, 0, Math.min(buffer.length, 8000-drained), AudioRecord.READ_NON_BLOCKING);
                if (count <= 0) break;
                acceptAudio(buffer, count); drained += count;
            }
            audio.stop(); audio.release(); audio = null; recorder = null;
            ended = true;
            live.endActivity();
            finishIfReady();
        } catch (Throwable error) { fail("Microphone or dictation setup failed. Please try again."); }
        finally {
            if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} audio.release(); }
            recorder = null;
            if (cancelled.get() && live != null) live.close();
        }
    }
    private void acceptAudio(byte[] buffer, int count) {
        if (count <= 0) return;
        if (!stopRequested && !cancelled.get()) listener.level(AudioLevel.measure(buffer, count));
        synchronized (lock) { pcm.write(buffer, 0, count); audioBytes += count; }
        if (terminal == null && live != null) live.sendPcm(buffer, count);
    }
    private void finishIfReady() {
        if (!ended || terminal == null || cancelled.get() || !completing.compareAndSet(false, true)) return;
        WORK.execute(() -> {
            long cleanupStart = 0;
            String raw = "";
            try {
                raw = terminal instanceof CloudLiveTerminal.Success ? ((CloudLiveTerminal.Success)terminal).getText() : "";
                boolean fallback = raw.trim().isEmpty();
                if (fallback) raw = transcribeFile();
                if (raw.trim().isEmpty()) { fail("No speech was detected."); return; }
                if (cancelled.get()) return;
                cleanupStart = SystemClock.elapsedRealtime();
                JSONObject response = cleanup(raw);
                String cleaned = outputText(response,editorContext!=null && config.optBoolean("useMessageContext",true));
                FidelityVerdict verdict = CloudCleanupFidelityGuard.INSTANCE.check(raw, cleaned, vocabulary);
                JSONObject metrics = new JSONObject().put("timestamp", System.currentTimeMillis())
                    .put("audioMs", audioBytes / 32).put("batchFallback", fallback)
                    .put("stopToFinalMs", cleanupStart-stoppedAt)
                    .put("cleanupMs", SystemClock.elapsedRealtime()-cleanupStart)
                    .put("stopToResultMs", SystemClock.elapsedRealtime()-stoppedAt)
                    .put("serviceTier", response.optString("service_tier", "unknown"))
                    .put("usage", response.optJSONObject("usage"))
                    .put("guardAccepted", verdict instanceof FidelityVerdict.Ok);
                try (java.io.FileOutputStream file = context.openFileOutput("pixelvoice-last-metrics.json", 0)) {
                    file.write(metrics.toString().getBytes("UTF-8"));
                }
                if (!cancelled.get()) {
                    if (verdict instanceof FidelityVerdict.Ok) listener.result(cleaned);
                    else { listener.error("Cleanup could not preserve the wording; original text inserted."); listener.result(raw); }
                }
            } catch (Throwable error) {
                if (!cancelled.get()) {
                    if (!raw.isEmpty()) { listener.error("Cleanup unavailable; original transcript inserted."); listener.result(raw); }
                    else fail("OpenAI dictation failed. Check your connection and try again.");
                }
            } finally {
                if (live != null) live.close();
                synchronized (lock) { pcm.reset(); }
                config = null;
                editorContext = null;
            }
        });
    }
    private JSONObject cleanup(String raw) throws Exception {
        String prompt = config.getString("prompt");
        if (prompt.contains("{{vocabulary}}")) prompt = prompt.replace("{{vocabulary}}",
            " Watch for these project names and personal vocabulary terms, which speech-to-text often mishears: " + String.join(", ", vocabulary) + ".");
        prompt += "\n\nWrite your output in the same language as the transcript; do not translate it unless the instructions above explicitly ask for a translation.";
        DictationContext snapshot=editorContext;
        boolean contextual=snapshot!=null && config.optBoolean("useMessageContext",true);
        if(contextual)prompt+="\n\n"+DictationContext.INSTRUCTIONS;
        JSONObject body = new JSONObject().put("model", "gpt-6.1-sol").put("service_tier", "ultrafast")
            .put("store", false).put("reasoning", new JSONObject().put("effort", "low"))
            .put("text", new JSONObject().put("verbosity", "low")).put("max_output_tokens", 4096)
            .put("input", new JSONArray().put(new JSONObject().put("role", "system").put("content", prompt))
                .put(new JSONObject().put("role", "user").put("content", contextual?snapshot.input(raw):raw)));
        return request("https://api.openai.com/v1/responses", RequestBody.create(body.toString(), MediaType.get("application/json")));
    }
    private String transcribeFile() throws Exception {
        byte[] data; synchronized (lock) { data = pcm.toByteArray(); }
        ByteBuffer wave = ByteBuffer.allocate(data.length+44).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes("US-ASCII")).putInt(data.length+36).put("WAVEfmt ".getBytes("US-ASCII"))
            .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000)
            .putShort((short)2).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(data.length).put(data);
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "gpt-transcribe").addFormDataPart("language", "en")
            .addFormDataPart("prompt", String.join(", ", vocabulary))
            .addFormDataPart("file", "dictation.wav", RequestBody.create(wave.array(), MediaType.get("audio/wav"))).build();
        return request("https://api.openai.com/v1/audio/transcriptions", body).optString("text");
    }
    private JSONObject request(String url, RequestBody body) throws Exception {
        if (cancelled.get()) throw new IllegalStateException("Cancelled");
        call = HTTP.newCall(new Request.Builder().url(url).header("Authorization", "Bearer "+config.getString("key")).post(body).build());
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("API rejected request");
            return new JSONObject(response.body().string());
        } finally { call = null; }
    }
    public static String outputText(JSONObject response) throws Exception {
        return outputText(response,false);
    }
    public static String outputText(JSONObject response,boolean contextual) throws Exception {
        if (!"completed".equals(response.optString("status"))) throw new IllegalStateException("Incomplete cleanup");
        StringBuilder output = new StringBuilder(); JSONArray items = response.getJSONArray("output");
        for (int i=0; i<items.length(); i++) {
            JSONObject item=items.getJSONObject(i); if (!"message".equals(item.optString("type"))) continue;
            JSONArray parts=item.optJSONArray("content"); if (parts==null) continue;
            for (int j=0; j<parts.length(); j++) if ("output_text".equals(parts.getJSONObject(j).optString("type")))
                output.append(parts.getJSONObject(j).getString("text"));
        }
        String text=contextual?DictationContext.trimOutputPadding(output.toString()):output.toString().trim();
        if (text.trim().isEmpty()) throw new IllegalStateException("Empty cleanup");
        return text;
    }
    private void fail(String message) {
        if (!cancelled.getAndSet(true)) { stopRequested=true; if(live!=null) live.cancel(); listener.error(message); }
    }
}
