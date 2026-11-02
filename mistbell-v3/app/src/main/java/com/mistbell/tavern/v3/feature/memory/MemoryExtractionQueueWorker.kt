package com.mistbell.tavern.v3.feature.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mistbell.tavern.v3.core.diagnostics.DiagnosticLogStore
import com.mistbell.tavern.v3.core.diagnostics.bestEffortDiagnostic
import com.mistbell.tavern.v3.core.model.ProviderKind
import com.mistbell.tavern.v3.core.model.ProviderProfile
import com.mistbell.tavern.v3.core.model.ProviderProfileSet
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryOverrideStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames
import com.mistbell.tavern.v3.feature.chat.ChatHistoryLoadResult
import com.mistbell.tavern.v3.feature.chat.ChatHistoryStore
import com.mistbell.tavern.v3.feature.chat.SqliteChatHistoryRepository
import com.mistbell.tavern.v3.feature.chat.MemoryExtractionBatchInputFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Process-resilient queue consumer. The queue and source history are both persisted privately;
 * API credentials are resolved from Android Keystore using the opaque handle stored in the job.
 */
@Suppress("TooGenericExceptionCaught")
class MemoryExtractionQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = MemoryExtractionJobRepository(MemoryExtractionJobStore(applicationContext))
        val runner = MemoryExtractionQueueRunner(repository)
        val diagnostics = DiagnosticLogStore(applicationContext.filesDir)
        val queuedAtStart = runCatching { repository.list() }.getOrNull()
        bestEffortDiagnostic {
            diagnostics.info(
                "memory.extraction.worker.started",
                "total=${queuedAtStart?.size ?: -1}; " +
                    "active=${queuedAtStart?.count { it.state in ACTIVE_STATES } ?: -1}",
            )
        }
        return try {
            runner.recoverInterrupted()
            val credentialStore = AndroidKeystoreMemoryCredentialStore(applicationContext)
            var processedJobs = 0
            while (!isStopped && processedJobs < MAX_JOBS_PER_RUN) {
                val attempt = MemoryExtractionAttempt()
                val completion = attempt.completion
                runner.runNext(
                        buildInput = { job -> buildExtractionInput(job, diagnostics) },
                        execute = { job, prepared, complete ->
                            attempt.bindCompletion(complete)
                            val (input, profile, handle) = prepared
                            val providers = ProviderProfileSet(
                                profiles = listOf(profile),
                                selectedProfileIds = mapOf(ProviderKind.MEMORY_EXTRACTION to profile.id),
                            )
                            val pipeline = TemporalFactExtractionPipeline(
                                client = MemoryExtractionClient(
                                    providers = providers,
                                    credentials = MemoryCredentialResolver(credentialStore::resolve),
                                    onDiagnostic = { event, detail ->
                                        bestEffortDiagnostic {
                                            diagnostics.info(event, "job=${job.id.take(JOB_ID_PREFIX_CHARS)}; $detail")
                                        }
                                    },
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
                                overrideStore = AtomicMemoryOverrideStore(
                                    applicationContext,
                                    MemoryScopeFileNames.forScope(
                                        AtomicMemoryOverrideStore.DEFAULT_FILE_NAME,
                                        job.conversationId,
                                    ),
                                ),
                            )
                            val jobPrefix = job.id.take(JOB_ID_PREFIX_CHARS)
                            val call = pipeline.extractAndPersist(input, commitGate = attempt::commit) { outcome ->
                                when (outcome) {
                                    is TemporalFactPipelineOutcome.Saved -> {
                                        bestEffortDiagnostic {
                                            diagnostics.info(
                                                "memory.extraction.job.saved",
                                                "job=$jobPrefix; " +
                                                    "extracted=${outcome.extractedFacts}; " +
                                                    "current=${outcome.currentFacts}; " +
                                                    "indexed=${outcome.indexedTextRecords}; " +
                                                    "warning=${outcome.indexingWarning ?: "none"}",
                                            )
                                        }
                                        complete(MemoryExtractionQueueRunner.ExecutionResult.Success)
                                    }
                                    is TemporalFactPipelineOutcome.Failed -> {
                                        val failure = MemoryExtractionFailurePolicy.classify(
                                            safeMessage = outcome.safeMessage,
                                            httpStatus = outcome.httpStatus,
                                        )
                                        bestEffortDiagnostic {
                                            diagnostics.warn(
                                                "memory.extraction.job.failed",
                                                "job=$jobPrefix; category=${failure.safeCategory}; " +
                                                    "permanent=${failure.permanent}; " +
                                                    "httpStatus=${outcome.httpStatus ?: "none"}; " +
                                                    "detail=${outcome.safeMessage.take(ERROR_DETAIL_CHARS)}",
                                            )
                                        }
                                        complete(failure)
                                    }
                                }
                            }
                            attempt.attachCall(call)
                        },
                        observer = { result ->
                            when (result) {
                                MemoryExtractionQueueRunner.Result.Idle -> {
                                    bestEffortDiagnostic {
                                        diagnostics.info("memory.extraction.worker.idle")
                                    }
                                    completion.complete(false)
                                }
                                is MemoryExtractionQueueRunner.Result.Finished -> {
                                    val finishedJob = repository.list().firstOrNull { it.id == result.jobId }
                                    val nextInMs = finishedJob?.let {
                                        (it.nextAttemptAtEpochMillis - System.currentTimeMillis())
                                            .coerceAtLeast(0L)
                                    } ?: -1L
                                    bestEffortDiagnostic {
                                        diagnostics.info(
                                            "memory.extraction.job.finished",
                                            "job=${result.jobId.take(JOB_ID_PREFIX_CHARS)}; state=${result.state}; " +
                                                "attempts=${finishedJob?.attemptCount ?: -1}; " +
                                                "nextInMs=$nextInMs; " +
                                                "safeError=${finishedJob?.lastSafeError ?: "none"}",
                                        )
                                    }
                                    val completedJob = if (result.state == MemoryExtractionJob.State.COMPLETED) {
                                        finishedJob
                                    } else {
                                        null
                                    }
                                    val turnId = completedJob?.turnIds?.lastOrNull()
                                    // The turn cursor belongs to one conversation, not to the
                                    // memory scope: a shared scope must not make switching back
                                    // to an older conversation re-extract its whole history.
                                    val scopeId = completedJob?.run {
                                        historyScopeId.takeIf(String::isNotBlank) ?: conversationId
                                    }
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
                val hasJob = attempt.await()
                if (!hasJob) break
                processedJobs += 1
            }
            val retryDelay = scheduleNextRetry(repository)
            bestEffortDiagnostic {
                diagnostics.info(
                    "memory.extraction.worker.completed",
                    "processed=$processedJobs; retryInMs=$retryDelay",
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            bestEffortDiagnostic {
                diagnostics.error("memory.extraction.worker.retry", "reason=${error::class.java.simpleName}")
            }
            Result.retry()
        }
    }

    @Suppress("LongMethod")
    private fun buildExtractionInput(
        job: MemoryExtractionJob,
        diagnostics: DiagnosticLogStore,
    ): Triple<MemoryExtractionInput, ProviderProfile, String> =
        try {
            val credentialStore = AndroidKeystoreMemoryCredentialStore(applicationContext)
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
            // Chat history now lives in SQLite. The repository still migrates a legacy JSON file
            // once if the conversation has not been opened since the upgrade, so background
            // extraction reads the same source as chat.
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
            val input = MemoryExtractionBatchInputFactory().create(job, history)
            val profile = ProviderProfile(
                id = job.providerProfileId,
                kind = ProviderKind.MEMORY_EXTRACTION,
                baseUrl = baseUrl,
                modelId = modelId,
                credentialHandle = handle,
            )
            Triple(input, profile, handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            bestEffortDiagnostic {
                diagnostics.warn(
                    "memory.extraction.input_unavailable",
                    "job=${job.id.take(JOB_ID_PREFIX_CHARS)}; reason=${error::class.java.simpleName}: " +
                        (error.message ?: "none").take(ERROR_DETAIL_CHARS),
                )
            }
            throw error
        }

    private fun scheduleNextRetry(repository: MemoryExtractionJobRepository): Long {
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
            .minOfOrNull(MemoryExtractionJob::nextAttemptAtEpochMillis) ?: return -1L
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
        return delay
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "mistbell-memory-extraction-queue"
        private const val MAX_JOBS_PER_RUN = 10
        private const val JOB_ID_PREFIX_CHARS = 8
        private const val ERROR_DETAIL_CHARS = 180
        private val ACTIVE_STATES = setOf(
            MemoryExtractionJob.State.PENDING,
            MemoryExtractionJob.State.RUNNING,
            MemoryExtractionJob.State.RETRY_WAIT,
        )

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
