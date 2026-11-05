package com.trevornk.ramblr

import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** OpenAI live transcription with explicit push-to-talk turn boundaries.
 *
 * Authentication uses the existing device-resident OpenAI API key in an HTTP header, never a URL.
 * OkHttp invokes socket methods off-main; listener callbacks are serialized on [callbackExecutor]
 * (also off-main by default). The runtime is responsible for explicitly hopping UI work to main.
 */
class OpenAiCloudLiveTranscriptionClient(
    private val httpClient: OkHttpClient = NetworkClients.shared,
    // OkHttp's WebSocket API accepts an HTTPS HttpUrl and performs the WSS upgrade itself.
    private val endpoint: HttpUrl = DEFAULT_HTTP_ENDPOINT.toHttpUrl(),
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    internal val languageCodes: List<String> = emptyList(),
    private val customVocabulary: List<String> = emptyList(),
    callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenAiLive-callback").apply { isDaemon = true }
    },
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "OpenAiLive-timeout").apply { isDaemon = true }
    },
    private val setupTimeoutMs: Long = DEFAULT_SETUP_TIMEOUT_MS,
    private val finalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    internal val allowTestEndpoint: Boolean = false,
) : CloudLiveTranscriptionSessionFactory {
    private val callbacks = SerialCallbackExecutor(callbackExecutor)

    init {
        require(endpoint.scheme == "https" || (allowTestEndpoint && endpoint.scheme == "http"))
        require(endpoint.encodedPath == ENDPOINT_PATH && endpoint.query == "intent=transcription" && endpoint.fragment == null) { "Unexpected OpenAI Live endpoint" }
        require(allowTestEndpoint || endpoint.host == OPENAI_HOST) { "Unexpected OpenAI Live host" }
        require(apiKey.isNotBlank()) { "OpenAI API key is blank" }
        require(MODEL_ID.matches(model) && model == DEFAULT_MODEL) { "Invalid OpenAI Live model id" }
        require(languageCodes.all(LANGUAGE_CODE::matches)) { "Invalid language code" }
        require(customVocabulary.size <= MAX_CUSTOM_VOCABULARY && customVocabulary.none {
            it.isBlank() || it.any { char -> char in "<>\r\n" }
        }) { "Invalid custom vocabulary" }
        require(setupTimeoutMs > 0 && finalTimeoutMs > 0)
    }

    override fun create(listener: CloudLiveTranscriptionListener): CloudLiveTranscriptionSession = Session(listener)

    private inner class Session(private val listener: CloudLiveTranscriptionListener) : CloudLiveTranscriptionSession {
        private val lock = Any()
        private val terminal = AtomicBoolean(false)
        private val pending = ArrayDeque<String>()
        private var pendingBytes = 0L
        private var socket: WebSocket? = null
        private var connected = false
        private var setupComplete = false
        private var draining = false
        private var startRequested = false
        private var endRequested = false
        private var setupTimeout: ScheduledFuture<*>? = null
        private var finalTimeout: ScheduledFuture<*>? = null
        private var timing = CloudLiveTiming(connectStartedAtMs = nowMs())
        private var committedItemId: String? = null
        private val partials = mutableMapOf<String, String>()
        private val resampler = PcmNormalizer(16000, 1, false, 24000) { samples, count ->
            enqueue(audioJson(Base64.getEncoder().encodeToString(PcmNormalizer.shortsToBytes(samples, count))))
        }

        override fun connect() {
            synchronized(lock) {
                if (connected || terminal.get()) return
                connected = true
                timing = timing.copy(connectStartedAtMs = nowMs())
                setupTimeout = scheduler.schedule(
                    { fail(CloudLiveFailureReason.SETUP_TIMEOUT, "Live transcription setup timed out") },
                    setupTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
                val request = Request.Builder().url(endpoint).header(API_KEY_HEADER, "Bearer $apiKey").build()
                socket = httpClient.newWebSocket(request, SocketListener())
            }
        }

        override fun startActivity(): Boolean = enqueueControl(activityStartJson(), isStart = true)

        override fun sendPcm(buffer: ByteArray, length: Int): Boolean {
            if (length < 0 || length > buffer.size) {
                fail(CloudLiveFailureReason.INVALID_REQUEST, "Invalid PCM length")
                return false
            }
            if (length % 2 != 0) {
                fail(CloudLiveFailureReason.INVALID_REQUEST, "Incomplete PCM sample")
                return false
            }
            synchronized(lock) {
                if (!startRequested || endRequested || terminal.get()) return false
                resampler.feed(java.nio.ByteBuffer.wrap(buffer.copyOf(length)), length)
                resampler.flushBufferedOutput()
            }
            return !terminal.get()
        }

        override fun endActivity(): Boolean {
            synchronized(lock) {
                if (endRequested || terminal.get()) return false
                resampler.finish()
            }
            val accepted = enqueueControl(activityEndJson(), isStart = false)
            if (accepted) synchronized(lock) {
                timing = timing.copy(activityEndedAtMs = nowMs())
                if (setupComplete) armFinalTimeoutLocked()
            }
            return accepted
        }

        private fun enqueueControl(message: String, isStart: Boolean): Boolean {
            synchronized(lock) {
                if (terminal.get()) return false
                if (isStart) {
                    if (startRequested || endRequested) return false
                    startRequested = true
                } else {
                    if (!startRequested || endRequested) return false
                    endRequested = true
                }
            }
            return enqueue(message)
        }

        /**
         * Appends [message] to [pending] -- the single ordering authority -- then drains the queue
         * if no other thread already owns the drain.
         *
         * Nothing is ever handed straight to the socket, even after the handshake lands. A direct
         * send would let a message enqueued *later* reach the wire ahead of one still sitting in
         * the queue: the recorder thread's next PCM chunk used to overtake the not-yet-flushed
         * `activityStart` while [onSetupComplete] was mid-flush, and with
         * `automaticActivityDetection.disabled = true` the audio order must stay consistent. Routing every message through the queue makes append order == wire order
         * by construction, which is exactly what [CloudLiveTranscriptionSession] promises.
         *
         * The monitor is never held across a send: OkHttp's [WebSocket.send] enqueues onto its own
         * writer rather than blocking on the network, but keeping it outside the lock also means a
         * slow writer can never stall the recorder thread's next `sendPcm`.
         */
        private fun enqueue(message: String): Boolean {
            val buffered = synchronized(lock) {
                if (terminal.get()) return false
                val bytes = message.toByteArray(Charsets.UTF_8).size.toLong()
                if (pendingBytes + bytes > MAX_PENDING_BYTES) {
                    false // Failure is completed outside this monitor.
                } else {
                    pending.addLast(message)
                    pendingBytes += bytes
                    true
                }
            }
            if (!buffered) {
                fail(CloudLiveFailureReason.BUFFER_LIMIT, "Live transcription outbound buffer limit reached")
                return false
            }
            drainPending()
            return true
        }

        /**
         * Sends every queued message in FIFO order. At most one thread drains at a time; whoever
         * loses the race simply returns, because the winner is guaranteed to pick up its message
         * (the emptiness check that ends a drain and the `draining = false` that reopens it happen
         * in the same critical section, so no enqueue can be stranded).
         */
        private fun drainPending() {
            val owned = synchronized(lock) {
                if (terminal.get() || !setupComplete || draining) return
                draining = true
                true
            }
            if (!owned) return
            var failure: String? = null
            while (true) {
                val message = takeNextPendingOrEndDrain() ?: break
                // A missing socket after setup is a released/never-opened connection, not a full
                // outbound buffer -- report what actually happened.
                val ws = synchronized(lock) { socket }
                if (ws == null) { failure = "Live transcription socket unavailable"; break }
                if (ws.queueSize() > MAX_WEBSOCKET_QUEUE_BYTES || !ws.send(message)) {
                    failure = "Live transcription send failed"
                    break
                }
            }
            if (failure != null) {
                synchronized(lock) { draining = false }
                fail(CloudLiveFailureReason.SEND_FAILED, failure)
            }
        }

        /** Pops the next queued message, or ends this drain (in the same critical section as the
         *  emptiness check) and returns null. */
        private fun takeNextPendingOrEndDrain(): String? = synchronized(lock) {
            if (terminal.get() || pending.isEmpty()) {
                draining = false
                return null
            }
            val next = pending.removeFirst()
            pendingBytes -= next.toByteArray(Charsets.UTF_8).size.toLong()
            next
        }

        override fun cancel() = fail(CloudLiveFailureReason.CANCELLED, "Live transcription cancelled")

        override fun close() {
            if (!terminal.get()) cancel()
            synchronized(lock) { socket?.close(NORMAL_CLOSE, "done") }
        }

        private fun onSetupComplete() {
            synchronized(lock) {
                if (terminal.get() || setupComplete) return
                setupComplete = true
                timing = timing.copy(setupCompletedAtMs = nowMs())
                setupTimeout?.cancel(false)
                setupTimeout = null
            }
            drainPending()
            synchronized(lock) { if (endRequested && !terminal.get()) armFinalTimeoutLocked() }
        }

        private fun armFinalTimeoutLocked() {
            if (finalTimeout != null) return
            finalTimeout = scheduler.schedule(
                { fail(CloudLiveFailureReason.FINAL_TIMEOUT, "Live transcription final timed out") },
                finalTimeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun onText(text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_INBOUND_BYTES) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Live transcription message too large")
                return
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Malformed live transcription event")
                return
            }
            when (root.optString("type")) {
                "error", "conversation.item.input_audio_transcription.failed" -> {
                    fail(CloudLiveFailureReason.PROTOCOL_ERROR, "OpenAI live transcription request failed")
                }
                "session.updated" -> onSetupComplete()
                "input_audio_buffer.committed" -> synchronized(lock) {
                    committedItemId = root.optString("item_id").takeIf { it.isNotBlank() }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val id = root.optString("item_id")
                    val delta = root.optString("delta")
                    if (id.isBlank() || delta.isEmpty()) return
                    val update = synchronized(lock) {
                        if (terminal.get()) return
                        val next = partials[id].orEmpty() + delta
                        if (next.length > MAX_INBOUND_BYTES) {
                            fail(CloudLiveFailureReason.BUFFER_LIMIT, "Live transcript too large")
                            return
                        }
                        partials[id] = next
                        if (timing.firstInterimAtMs == null) timing = timing.copy(firstInterimAtMs = nowMs())
                        next to timing
                    }
                    callbacks.execute { if (!terminal.get()) listener.onInterim(update.first, update.second) }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val id = root.optString("item_id")
                    val expected = synchronized(lock) { committedItemId }
                    if (expected != null && id == expected && endRequested) {
                        succeed(root.optString("transcript").trim())
                    }
                }
            }
        }

        private fun succeed(text: String) {
            val result = synchronized(lock) {
                timing = timing.copy(finalAtMs = nowMs())
                CloudLiveTerminal.Success(text, timing)
            }
            complete(result)
        }

        private fun fail(reason: CloudLiveFailureReason, message: String) {
            val safe = UrlRedaction.redact(message.replace(apiKey, "[REDACTED]"))
                ?: "Live transcription failed"
            val result = synchronized(lock) { CloudLiveTerminal.Failure(reason, safe, timing) }
            complete(result)
        }

        private fun complete(result: CloudLiveTerminal) {
            if (!terminal.compareAndSet(false, true)) return
            synchronized(lock) {
                setupTimeout?.cancel(false); setupTimeout = null
                finalTimeout?.cancel(false); finalTimeout = null
                pending.clear(); pendingBytes = 0
                socket?.close(NORMAL_CLOSE, "done")
            }
            callbacks.execute { listener.onTerminal(result) }
        }

        private inner class SocketListener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) { timing = timing.copy(socketOpenedAtMs = nowMs()) }
                if (!webSocket.send(setupJson(model, languageCodes, customVocabulary))) {
                    fail(CloudLiveFailureReason.SEND_FAILED, "Live transcription setup send failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            /**
             * OpenAI Live delivers its JSON events as binary frames as well as text
             * ones; the frame opcode is not part of the protocol contract. Decode and
             * parse them identically instead of treating binary as fatal. Malformed
             * bytes still fail closed via [onText]'s JSON parse.
             */
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > MAX_INBOUND_BYTES) {
                    fail(CloudLiveFailureReason.PROTOCOL_ERROR, "Live transcription message too large")
                    return
                }
                onText(bytes.utf8())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(CloudLiveFailureReason.NETWORK_ERROR, t.message ?: "Live transcription network failure")
            }
            /**
             * OkHttp's default onClosing does not echo the close frame, so a
             * server-initiated close never advances to onClosed -- verified: a
             * 6s wait yields onClosing(1011) and nothing else. Handling only
             * onClosed left a mid-stream server close with no terminal at all,
             * stalling the session until the final timeout expired. Treat the
             * close frame itself as the terminal.
             */
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) fail(CloudLiveFailureReason.NETWORK_ERROR, "Live transcription connection closed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get()) fail(CloudLiveFailureReason.NETWORK_ERROR, "Live transcription connection closed")
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-live-transcribe"
        const val ENDPOINT_PATH = "/v1/realtime"
        const val OPENAI_HOST = "api.openai.com"
        const val DEFAULT_WSS_ENDPOINT = "wss://$OPENAI_HOST$ENDPOINT_PATH?intent=transcription"
        internal const val DEFAULT_HTTP_ENDPOINT = "https://$OPENAI_HOST$ENDPOINT_PATH?intent=transcription"
        const val MAX_CUSTOM_VOCABULARY = 128
        const val DEFAULT_SETUP_TIMEOUT_MS = 10_000L
        const val DEFAULT_FINAL_TIMEOUT_MS = 5_000L
        const val MAX_PENDING_BYTES = 2L * 1024 * 1024
        const val MAX_WEBSOCKET_QUEUE_BYTES = 4L * 1024 * 1024
        const val MAX_INBOUND_BYTES = 256 * 1024
        private const val API_KEY_HEADER = "Authorization"
        private const val NORMAL_CLOSE = 1000
        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val LANGUAGE_CODE = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

        fun setupJson(model: String, languageCodes: List<String>, customVocabulary: List<String>): String =
            JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "transcription")
                .put("audio", JSONObject().put("input", JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                    .put("turn_detection", JSONObject.NULL)
                    .put("transcription", JSONObject().put("model", model)
                        .put("delay", "low")
                        .put("languages", JSONArray(languageCodes))
                        .put("keywords", JSONArray(customVocabulary))
                        .put("prompt", "Personal messages and everyday dictation.")))))
                .toString()

        private fun activityStartJson() = JSONObject().put("type", "input_audio_buffer.clear").toString()
        private fun activityEndJson() = JSONObject().put("type", "input_audio_buffer.commit").toString()
        private fun audioJson(base64: String) = JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", base64).toString()
    }
}
