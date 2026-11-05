package com.trevornk.ramblr

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class OpenAiCloudLiveTranscriptionClientTest {
    @Test fun setupUsesManualCommitAndLiteralKeywordHints() {
        val root = JSONObject(OpenAiCloudLiveTranscriptionClient.setupJson("gpt-live-transcribe", listOf("en"), listOf("Test Name")))
        assertEquals("session.update", root.getString("type"))
        val input = root.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertTrue(input.isNull("turn_detection"))
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("low", input.getJSONObject("transcription").getString("delay"))
        assertEquals("Test Name", input.getJSONObject("transcription").getJSONArray("keywords").getString(0))
    }
    @Test fun wrongHostAndUnsafeKeywordsAreRejectedBeforeConnecting() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCloudLiveTranscriptionClient(apiKey = "fake", endpoint = "https://other.example/v1/realtime?intent=transcription".toHttpUrl())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiCloudLiveTranscriptionClient(apiKey = "fake", customVocabulary = listOf("two\nlines"))
        }
    }
    @Test fun preservesAudioOrderResamplesAndAcceptsOnlyTheCommittedItem() {
        val server = MockWebServer()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val received = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        var outcome: CloudLiveTerminal? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val event = JSONObject(text)
                val type = event.getString("type")
                received += type
                if (type == "session.update") ws.send("{\"type\":\"session.updated\"}")
                if (type == "input_audio_buffer.append") {
                    val bytes = java.util.Base64.getDecoder().decode(event.getString("audio"))
                    assertEquals(12, bytes.size)
                }
                if (type == "input_audio_buffer.commit") {
                    ws.send("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"correct\"}")
                    ws.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"other\",\"transcript\":\"Wrong\"}")
                    ws.send("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"correct\",\"transcript\":\"Correct text.\"}")
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
        }))
        server.start()
        try {
            val session = OpenAiCloudLiveTranscriptionClient(
                apiKey = "test-only-key", endpoint = server.url("/v1/realtime?intent=transcription"),
                allowTestEndpoint = true, scheduler = scheduler, callbackExecutor = Executor { it.run() },
            ).create(object : CloudLiveTranscriptionListener {
                override fun onInterim(text: String, timing: CloudLiveTiming) = Unit
                override fun onTerminal(result: CloudLiveTerminal) { outcome = result; done.countDown() }
            })
            session.connect()
            assertTrue(session.startActivity())
            assertTrue(session.sendPcm(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0), 8))
            assertTrue(session.endActivity())
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("Correct text.", (outcome as CloudLiveTerminal.Success).text)
            assertEquals(listOf("session.update", "input_audio_buffer.clear", "input_audio_buffer.append", "input_audio_buffer.commit"), received.toList())
            assertEquals("Bearer test-only-key", server.takeRequest().getHeader("Authorization"))
            session.close()
        } finally {
            scheduler.shutdownNow()
            server.shutdown()
        }
    }
}
