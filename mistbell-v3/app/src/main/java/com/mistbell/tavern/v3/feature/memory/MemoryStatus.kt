package com.mistbell.tavern.v3.feature.memory

import android.content.Context
import com.mistbell.tavern.v3.core.security.AndroidKeystoreMemoryCredentialStore
import com.mistbell.tavern.v3.core.storage.AtomicMemoryTextStore
import com.mistbell.tavern.v3.core.storage.AtomicTemporalFactStore
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames
import com.mistbell.tavern.v3.core.storage.MemoryTextLoadResult
import com.mistbell.tavern.v3.core.storage.TemporalFactLoadResult
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Read-only view of one conversation's memory pipeline: configuration, extraction queue,
 * fact ledger, source text, vector snapshot and the last retrieval outcome.
 *
 * Nothing here mutates the memory files; a status page must never repair or rewrite data
 * as a side effect of looking at it.
 */
internal data class MemoryStatusSnapshot(
    val extractionEnabled: Boolean,
    val modelId: String,
    val endpoint: String,
    val credentialSaved: Boolean,
    val queuedJobs: Int = 0,
    val runningJobs: Int = 0,
    val retryJobs: Int = 0,
    val failedJobs: Int = 0,
    val completedJobs: Int = 0,
    val queueUnreadable: Boolean = false,
    val lastQueueError: String? = null,
    val oldestPendingAgeMillis: Long? = null,
    val factsCurrent: Int = 0,
    val factsTotal: Int = 0,
    val factIssue: String? = null,
    val textRecords: Int = 0,
    val textIssue: String? = null,
    val vectorBytes: Long? = null,
    val vectorAgeMillis: Long? = null,
    val lastRetrieval: String? = null,
    val lastRetrievalAgeMillis: Long? = null,
) {
    val configured: Boolean get() = modelId.isNotBlank() && endpoint.isNotBlank()
    val batchTotal: Int get() = queuedJobs + runningJobs + retryJobs + failedJobs + completedJobs
}

internal object MemoryStatusReader {
    fun read(context: Context, scopeId: String, now: Long = System.currentTimeMillis()): MemoryStatusSnapshot {
        val preferences = context.getSharedPreferences("mistbell-v3-settings", Context.MODE_PRIVATE)
        val credentialSaved = runCatching {
            val handle = preferences.getString("memory_api_credential_handle", null)
                ?.takeIf(String::isNotBlank) ?: return@runCatching false
            AndroidKeystoreMemoryCredentialStore(context).contains(handle)
        }.getOrDefault(false)

        val queue = readQueue(context, now)
        val facts = readFacts(context, scopeId)
        val texts = readTexts(context, scopeId)
        val vector = File(
            context.filesDir,
            MemoryScopeFileNames.forScope(AtomicVectorSnapshotStoreFile.DEFAULT_NAME, scopeId),
        )

        return MemoryStatusSnapshot(
            extractionEnabled = preferences.getBoolean("memory_extraction_enabled", true),
            modelId = preferences.getString("memory_model_id", "").orEmpty().trim(),
            endpoint = preferences.getString("memory_api_base_url", "").orEmpty().trim(),
            credentialSaved = credentialSaved,
            queuedJobs = queue.queued,
            runningJobs = queue.running,
            retryJobs = queue.retry,
            failedJobs = queue.failed,
            completedJobs = queue.completed,
            queueUnreadable = queue.unreadable,
            lastQueueError = queue.lastError,
            oldestPendingAgeMillis = queue.oldestPendingAgeMillis,
            factsCurrent = facts.current,
            factsTotal = facts.total,
            factIssue = facts.issue,
            textRecords = texts.count,
            textIssue = texts.issue,
            vectorBytes = vector.takeIf(File::isFile)?.length(),
            vectorAgeMillis = vector.takeIf(File::isFile)?.let { age(it.lastModified(), now) },
            lastRetrieval = preferences.getString("memory_last_status_$scopeId", null),
            lastRetrievalAgeMillis = preferences.getLong("memory_last_status_at_$scopeId", 0L)
                .takeIf { it > 0L }?.let { age(it, now) },
        )
    }

    private data class QueueCounts(
        val queued: Int = 0,
        val running: Int = 0,
        val retry: Int = 0,
        val failed: Int = 0,
        val completed: Int = 0,
        val unreadable: Boolean = false,
        val lastError: String? = null,
        val oldestPendingAgeMillis: Long? = null,
    )

    private fun readQueue(context: Context, now: Long): QueueCounts = runCatching {
        val jobs = com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobRepository(
            com.mistbell.tavern.v3.feature.memory.MemoryExtractionJobStore(context.applicationContext),
        ).list()
        var queued = 0
        var running = 0
        var retry = 0
        var failed = 0
        var completed = 0
        var oldest: Long? = null
        jobs.forEach { job ->
            when (job.state) {
                com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.PENDING -> queued++
                com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.RUNNING -> running++
                com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.RETRY_WAIT -> retry++
                com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.PERMANENT_FAILURE -> failed++
                com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.COMPLETED -> completed++
            }
            if (job.state == com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.PENDING ||
                job.state == com.mistbell.tavern.v3.feature.memory.MemoryExtractionJob.State.RETRY_WAIT
            ) {
                val pending = age(job.createdAtEpochMillis, now)
                if (pending != null && (oldest == null || pending > oldest)) oldest = pending
            }
        }
        val lastError = jobs.lastOrNull { it.lastSafeError != null }?.lastSafeError
        QueueCounts(queued, running, retry, failed, completed, false, lastError, oldest)
    }.getOrElse {
        QueueCounts(unreadable = true, lastError = it.javaClass.simpleName)
    }

    private data class FactCounts(val current: Int = 0, val total: Int = 0, val issue: String? = null)

    private fun readFacts(context: Context, scopeId: String): FactCounts = runCatching {
        val store = AtomicTemporalFactStore(
            context.applicationContext,
            MemoryScopeFileNames.forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, scopeId),
        )
        when (val loaded = store.load()) {
            TemporalFactLoadResult.Missing -> FactCounts()
            is TemporalFactLoadResult.Corrupt -> FactCounts(issue = "文件校验失败，已保留未覆盖")
            is TemporalFactLoadResult.Loaded -> FactCounts(
                current = loaded.facts.count { it.isCurrent },
                total = loaded.facts.size,
            )
        }
    }.getOrElse { FactCounts(issue = it.javaClass.simpleName) }

    private data class TextCounts(val count: Int = 0, val issue: String? = null)

    private fun readTexts(context: Context, scopeId: String): TextCounts = runCatching {
        val store = AtomicMemoryTextStore(
            context.applicationContext,
            MemoryScopeFileNames.forScope(AtomicMemoryTextStore.DEFAULT_FILE_NAME, scopeId),
        )
        when (val loaded = store.load()) {
            MemoryTextLoadResult.Missing -> TextCounts()
            is MemoryTextLoadResult.Corrupt -> TextCounts(issue = "文件校验失败，已保留未覆盖")
            is MemoryTextLoadResult.Loaded -> TextCounts(count = loaded.records.size)
        }
    }.getOrElse { TextCounts(issue = it.javaClass.simpleName) }

    private fun age(then: Long, now: Long): Long? =
        if (then <= 0L || then > now) null else now - then
}

/** Kept separate so the reader does not duplicate the snapshot file name. */
private object AtomicVectorSnapshotStoreFile {
    const val DEFAULT_NAME = "mistbell-v3-vectors.bin"
}

internal object MemoryStatusFormat {
    /** Endpoint hosts are enough to identify a provider; never show key material or paths. */
    fun endpointLabel(raw: String): String {
        val value = raw.trim()
        val host = runCatching { URI(value).host }.getOrNull()
        return when {
            value.isEmpty() -> ""
            !host.isNullOrBlank() -> host.lowercase(Locale.ROOT)
            else -> value.substringAfter("://", value).substringBefore('/').take(MAX_ENDPOINT_CHARS)
        }
    }

    fun age(millis: Long?): String = when {
        millis == null -> "从未"
        millis < MINUTE_MS -> "刚刚"
        millis < HOUR_MS -> "${millis / MINUTE_MS} 分钟前"
        millis < DAY_MS -> "${millis / HOUR_MS} 小时前"
        else -> "${millis / DAY_MS} 天前"
    }

    fun bytes(value: Long?): String = when {
        value == null -> "未生成"
        value < KIB -> "$value B"
        value < MIB -> "%.1f KiB".format(value.toDouble() / KIB)
        else -> "%.1f MiB".format(value.toDouble() / MIB)
    }

    private const val MAX_ENDPOINT_CHARS = 80
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val KIB = 1024.0
    private const val MIB = 1024.0 * 1024.0
}

/**
 * Turns the raw counters into the single sentence a user needs, in the order that actually
 * blocks them. Everything here is derived from persisted state, never from a guess.
 */
internal object MemoryStatusDiagnosis {
    fun hint(snapshot: MemoryStatusSnapshot): String = when {
        !snapshot.extractionEnabled -> "记忆抽取已关闭，不会再写入新记忆。"
        !snapshot.configured -> "未配置记忆模型或接口地址，抽取任务会反复失败且不留日志。"
        !snapshot.credentialSaved -> "未保存记忆 API Key，抽取任务不会入队。"
        snapshot.queueUnreadable -> "抽取队列读取失败，详见诊断日志。"
        snapshot.failedJobs > 0 -> "有 ${snapshot.failedJobs} 条抽取任务永久失败，需查看日志后清理队列。"
        snapshot.factIssue != null -> "事实库读取异常：${snapshot.factIssue}。"
        snapshot.textIssue != null -> "记忆文本读取异常：${snapshot.textIssue}。"
        snapshot.queuedJobs + snapshot.runningJobs + snapshot.retryJobs > 0 ->
            "抽取任务进行中：待处理 ${snapshot.queuedJobs}、重试 ${snapshot.retryJobs}、执行中 ${snapshot.runningJobs}。"
        snapshot.factsTotal == 0 -> "还没有任何记忆事实：继续聊天，抽取成功后这里会出现条目。"
        snapshot.vectorBytes == null -> "事实已写入但向量索引未生成，本轮只能靠关键词匹配。"
        else -> snapshot.lastRetrieval ?: "记忆链路正常。"
    }
}
