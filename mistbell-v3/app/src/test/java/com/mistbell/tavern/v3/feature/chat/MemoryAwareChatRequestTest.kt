package com.mistbell.tavern.v3.feature.chat

import kotlinx.coroutines.runBlocking
import com.mistbell.tavern.v3.core.memory.MemoryTextProvider
import com.mistbell.tavern.v3.core.memory.MemoryTextRecord
import com.mistbell.tavern.v3.core.memory.QueryEmbeddingProvider
import com.mistbell.tavern.v3.core.memory.StoredVector
import com.mistbell.tavern.v3.core.memory.VectorMemoryContextService
import com.mistbell.tavern.v3.core.memory.VectorSnapshot
import com.mistbell.tavern.v3.core.memory.VectorSnapshotProvider
import com.mistbell.tavern.v3.core.memory.VectorSnapshotReadResult
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemoryAwareChatRequestTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun retrievedMemoryIsSentWithRequestWithoutMutatingConversationHistory() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        server.start()
        val memoryText = MemoryTextRecord("fact-1", "The user prefers concise answers.")
        val snapshot = VectorSnapshot(
            modelId = "local-test-model",
            dimensions = 2,
            records = listOf(
                StoredVector("vector-1", memoryText.sourceTextSha256, 1L, listOf(1f, 0f)),
            ),
        )
        val context = VectorMemoryContextService(
            snapshots = VectorSnapshotProvider { VectorSnapshotReadResult.Loaded(snapshot) },
            embeddings = QueryEmbeddingProvider { listOf(1f, 0f) },
            textProvider = MemoryTextProvider { listOf(memoryText) },
            minimumScore = 0.5,
        )
        val profile = ProviderProfile(
            id = "chat",
            kind = ProviderKind.CHAT,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelId = "chat-model",
            credentialHandle = "chat-secret",
        )
        val chatClient = OpenAiCompatibleChatClient(
            providers = ProviderProfileSet(
                profiles = listOf(profile),
                selectedProfileIds = mapOf(ProviderKind.CHAT to profile.id),
            ),
            credentials = CredentialResolver { if (it == "chat-secret") "test-key" else null },
            client = OkHttpClient(),
        )
        val request = MemoryAwareChatRequest(chatClient, context)
        val history = listOf(
            PromptMessage(PromptRole.SYSTEM, "Stable instructions."),
            PromptMessage(PromptRole.USER, "Earlier question"),
            PromptMessage(PromptRole.ASSISTANT, "Earlier answer"),
            PromptMessage(PromptRole.USER, "What response style do I prefer?"),
        )
        val observer = RecordingObserver()

        request.stream(history, observer)

        assertTrue(observer.finished.await(5, TimeUnit.SECONDS))
        assertEquals(null, observer.failure)
        val captured = server.takeRequest(1, TimeUnit.SECONDS)!!
        val messages = JSONObject(captured.body.readUtf8()).getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(
            messages.getJSONObject(0).getString("content")
                .contains("The user prefers concise answers."),
        )
        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("What response style do I prefer?", messages.getJSONObject(3).getString("content"))
        assertEquals(4, history.size)
        assertEquals("Earlier answer", history[2].content)
    }

    private class RecordingObserver : ChatStreamObserver {
        val finished = CountDownLatch(1)
        @Volatile var failure: ChatStreamFailure? = null

        override fun onDelta(text: String) = Unit
        override fun onComplete() {
            finished.countDown()
        }
        override fun onFailure(failure: ChatStreamFailure) {
            this.failure = failure
            finished.countDown()
        }
    }
}
