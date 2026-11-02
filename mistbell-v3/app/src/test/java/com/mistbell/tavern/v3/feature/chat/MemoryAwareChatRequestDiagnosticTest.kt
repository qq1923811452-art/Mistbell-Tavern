package com.mistbell.tavern.v3.feature.chat

import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MemoryAwareChatRequestDiagnosticTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failedDiagnosticCannotBlockChatForReadyEmptyOrUnavailableMemory() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val text = MemoryTextRecord("fact-1", "The user prefers concise answers.")
                val ready =
                    VectorSnapshotReadResult.Loaded(
                        VectorSnapshot(
                            modelId = "local-test-model",
                            dimensions = 2,
                            records = listOf(StoredVector("vector-1", text.sourceTextSha256, 1L, listOf(1f, 0f))),
                        ),
                    )
                val empty =
                    VectorSnapshotReadResult.Loaded(
                        VectorSnapshot(modelId = "local-test-model", dimensions = 2, records = emptyList()),
                    )
                val client = newClient(server)
                val brokenStore = DiagnosticLogStore(temporaryFolder.newFile())
                val history = listOf(PromptMessage(PromptRole.USER, "What response style do I prefer?"))
                for (snapshot in listOf(ready, empty, VectorSnapshotReadResult.Missing)) {
                    server.enqueue(
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"),
                    )
                    val context =
                        VectorMemoryContextService(
                            snapshots = VectorSnapshotProvider { snapshot },
                            embeddings = QueryEmbeddingProvider { listOf(1f, 0f) },
                            textProvider = MemoryTextProvider { if (snapshot === ready) listOf(text) else emptyList() },
                        )
                    var status: String? = null
                    val finished = CountDownLatch(1)
                    var failure: ChatStreamFailure? = null
                    val observer =
                        object : ChatStreamObserver {
                            override fun onDelta(text: String) = Unit

                            override fun onComplete() {
                                finished.countDown()
                            }

                            override fun onFailure(error: ChatStreamFailure) {
                                failure = error
                                finished.countDown()
                            }
                        }

                    MemoryAwareChatRequest(client, context, { status = it }, brokenStore).stream(history, observer)

                    assertTrue(finished.await(5, TimeUnit.SECONDS))
                    assertEquals(null, failure)
                    assertTrue(!status.isNullOrBlank())
                    val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    val content = messages.getJSONObject(messages.length() - 1).getString("content")
                    if (snapshot === ready) {
                        assertTrue(content.contains(text.text))
                    } else {
                        assertEquals(history.single().content, content)
                    }
                }
                assertEquals(3, server.requestCount)
            }
        }

    private fun newClient(server: MockWebServer): OpenAiCompatibleChatClient {
        val profile =
            ProviderProfile(
                id = "chat",
                kind = ProviderKind.CHAT,
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                modelId = "chat-model",
            )
        return OpenAiCompatibleChatClient(
            providers =
                ProviderProfileSet(
                    profiles = listOf(profile),
                    selectedProfileIds = mapOf(ProviderKind.CHAT to profile.id),
                ),
            credentials = CredentialResolver { null },
        )
    }
}
