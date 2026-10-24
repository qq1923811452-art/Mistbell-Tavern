package com.mistbell.tavern.v3.feature.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames
import com.mistbell.tavern.v3.feature.chat.ChatHistoryLoadResult
import com.mistbell.tavern.v3.feature.chat.ChatHistoryStore
import com.mistbell.tavern.v3.feature.chat.SqliteChatHistoryRepository
import com.mistbell.tavern.v3.feature.chat.MemoryExtractionBatchInputFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Process-resilient queue consumer. The queue and source history are both persisted privately;
 * API credentials are resolved from Android Keystore using the opaque handle stored in the job.
 */
class MemoryExtractionQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = MemoryExtractionJobRepository(MemoryExtractionJobStore(applicationContext))
        val runner = MemoryExtractionQueueRunner(repository)
        return try {
            runner.recoverInterrupted()
            val credentialStore = AndroidKeystoreMemoryCredentialStore(applicationContext)
            val inputFactory = MemoryExtractionBatchInputFactory()
            var processedJobs = 0
            while (!isStopped && processedJobs < MAX_JOBS_PER_RUN) {
                val completion = CompletableDeferred<Boolean>()
                runner.runNext(
                        buildInput = { job ->
                            val handle = requireNotNull(job.credentialHandle) {
                                "Durable queue jobs require a saved credential handle."
                            }
                            require(credentialStore.contains(handle)) {
                                "Saved memory credential is unavailable."
                            }
                            val preferences = applicationContext.getSharedPreferences(
                                "mistbell-v3-settings",
                                Context.MODE_PRIVATE,
                            )
                            val modelId = preferences.getString("memory_model_id", null)
                                ?.trim()?.takeIf(String::isNotEmpty)
                                ?: error("Memory extraction model is no longer configured.")
                            val baseUrl = preferences.getString("memory_api_base_url", null)
                                ?.trim()?.takeIf(String::isNotEmpty)
                                ?: error("Memory extraction endpoint is no longer configured.")
                            // Chat history now lives in SQLite. The repository still migrates a
                            // legacy JSON file once if the conversation has not been opened since
                            // the upgrade, so background extraction reads the same source as chat.
                            val historyScopeId = job.historyScopeId
                            val legacyScopeId = job.conversationId
                                .takeIf { it.isNotBlank() && it != historyScopeId }
                            val historyRepository = SqliteChatHistoryRepository(
                                applicationContext,
                                historyScopeId,
                                ChatHistoryStore(
                                    applicationContext,
                                    MemoryScopeFileNames.forScope(
                                        ChatHistoryStore.DEFAULT_FILE_NAME,
                                        historyScopeId,
                                    ),
                                ),
                                legacyScopeId = legacyScopeId,
                                legacyFileStore = legacyScopeId?.let { scope ->
                                    ChatHistoryStore(
                                        applicationContext,
                                        MemoryScopeFileNames.forScope(
                                            ChatHistoryStore.DEFAULT_FILE_NAME,
                                            scope,
                                        ),
                                    )
                                },
                            )
                            val history = try {
                                when (val loaded = historyRepository.load()) {
                                    is ChatHistoryLoadResult.Loaded -> loaded.messages
                                    ChatHistoryLoadResult.Missing ->
                                        error("Persisted source chat history is missing.")
                                    ChatHistoryLoadResult.Corrupt ->
                                        error("Persisted source chat history is corrupt.")
                                    ChatHistoryLoadResult.Unavailable ->
                                        throw MemoryExtractionQueueRunner.RetryableInputException()
                                }
                            } finally {
                                historyRepository.close()
                            }
                            val input = inputFactory.create(job, history)
                            val profile = ProviderProfile(
                                id = job.providerProfileId,
                                kind = ProviderKind.MEMORY_EXTRACTION,
                                baseUrl = baseUrl,
                                modelId = modelId,
                                credentialHandle = handle,
                            )
                            Triple(input, profile, handle)
                        },
                        execute = { job, prepared, complete ->
                            val (input, profile, handle) = prepared
                            val providers = ProviderProfileSet(
                                profiles = listOf(profile),
                                selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                            )
                            val pipeline = TemporalFactExtractionPipeline(
                                client = MemoryExtractionClient(
                                    providers = providers,
                                    credentials = MemoryCredentialResolver(credentialStore::resolve),
                                ),
                                factStore = AtomicTemporalFactStore(
                                    applicationContext,
                                    MemoryScopeFileNames.forScope(
                                        AtomicTemporalFactStore.DEFAULT_FILE_NAME,
                                        job.conversationId,
                                    ),
                                ),
                                memoryTextStore = AtomicMemoryTextStore(
                                    applicationContext,
                                    MemoryScopeFileNames.forScope(
                                        AtomicMemoryTextStore.DEFAULT_FILE_NAME,
                                        job.conversationId,
                                    ),
                                ),
                            )
                            pipeline.extractAndPersist(input) { outcome ->
                                when (outcome) {
                                    is TemporalFactPipelineOutcome.Saved ->
                                        complete(MemoryExtractionQueueRunner.ExecutionResult.Success)
                                    is TemporalFactPipelineOutcome.Failed ->
                                        complete(
                                            MemoryExtractionFailurePolicy.classify(
                                                safeMessage = outcome.safeMessage,
                                                httpStatus = outcome.httpStatus,
                                            ),
                                        )
                                }
                            }
                        },
                        observer = { result ->
                            when (result) {
                                MemoryExtractionQueueRunner.Result.Idle -> completion.complete(false)
                                is MemoryExtractionQueueRunner.Result.Finished -> {
                                    val completedJob = if (result.state == MemoryExtractionJob.State.COMPLETED) {
                                        repository.list().firstOrNull { it.id == result.jobId }
                                    } else {
                                        null
                                    }
                                    val turnId = completedJob?.turnIds?.lastOrNull()
                                    val scopeId = completedJob?.conversationId
                                    if (turnId != null && !scopeId.isNullOrBlank()) {
                                        applicationContext.getSharedPreferences(
                                            "mistbell-v3-settings",
                                            Context.MODE_PRIVATE,
                                        ).edit().putString(
                                            "memory_extraction_last_success_turn_id_$scopeId",
                                            turnId,
                                        ).apply()
                                    }
                                    completion.complete(true)
                                }
                                is MemoryExtractionQueueRunner.Result.PersistenceFailure ->
                                    completion.completeExceptionally(
                                        IllegalStateException("Queue state could not be persisted."),
                                    )
                                is MemoryExtractionQueueRunner.Result.Started -> Unit
                            }
                        },
                    )
                val hasJob = completion.await()
                if (!hasJob) break
                processedJobs += 1
            }
            scheduleNextRetry(repository)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun scheduleNextRetry(repository: MemoryExtractionJobRepository) {
        val active = repository.list().filter {
            it.state == MemoryExtractionJob.State.PENDING ||
                it.state == MemoryExtractionJob.State.RUNNING ||
                it.state == MemoryExtractionJob.State.RETRY_WAIT
        }
        // Only the oldest outstanding batch in each conversation may run. If that batch is
        // waiting for retry, do not spin on later batches whose due time is already in the past.
        val nextTime = active.groupBy(MemoryExtractionJob::conversationId)
            .values
            .mapNotNull { jobs -> jobs.minByOrNull(MemoryExtractionJob::createdAtEpochMillis) }
            .minOfOrNull(MemoryExtractionJob::nextAttemptAtEpochMillis) ?: return
        val delay = (nextTime - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<MemoryExtractionQueueWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "mistbell-memory-extraction-queue"
        private const val MAX_JOBS_PER_RUN = 10

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<MemoryExtractionQueueWorker>()
                .addTag(UNIQUE_WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
