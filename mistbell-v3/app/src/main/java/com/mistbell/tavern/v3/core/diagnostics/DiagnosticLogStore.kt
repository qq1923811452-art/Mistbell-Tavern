package com.mistbell.tavern.v3.core.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Small, bounded, local-only diagnostic log. Callers must log events and error summaries, never
 * prompts, generated content, API credentials, or complete HTTP request/response bodies.
 */
class DiagnosticLogStore(
    private val directory: File,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val logFile = File(directory, LOG_FILE_NAME)

    fun info(event: String, detail: String = "") = record("INFO", event, detail)
    fun warn(event: String, detail: String = "") = record("WARN", event, detail)
    fun error(event: String, detail: String = "") = record("ERROR", event, detail)

    fun record(level: String, event: String, detail: String = "") {
        require(level in LEVELS) { "Unsupported diagnostic log level." }
        val safeEvent = sanitize(event, MAX_EVENT_CHARS)
        val safeDetail = sanitize(detail, MAX_DETAIL_CHARS)
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) {
                "Diagnostic log directory could not be created."
            }
            rotateIfNeeded()
            logFile.appendText(
                "${clock()} [$level] $safeEvent" +
                    if (safeDetail.isBlank()) "\n" else " - $safeDetail\n",
                StandardCharsets.UTF_8,
            )
        }
    }

    fun exportTo(output: OutputStream) {
        val snapshot = synchronized(lock) {
            ByteArrayOutputStream().apply {
                write("Mistbell Tavern 3.0 diagnostic log\n".toByteArray(StandardCharsets.UTF_8))
                write("Exported: ${clock()}\n".toByteArray(StandardCharsets.UTF_8))
                write("Privacy: prompts, chat content, and API keys are not intentionally logged.\n\n"
                    .toByteArray(StandardCharsets.UTF_8))
                val rotatedFile = File(directory, "$LOG_FILE_NAME.1")
                var hasEvents = false
                if (rotatedFile.isFile) {
                    rotatedFile.inputStream().use { it.copyTo(this) }
                    hasEvents = true
                }
                if (logFile.isFile) {
                    logFile.inputStream().use { it.copyTo(this) }
                    hasEvents = true
                }
                if (!hasEvents) write("No diagnostic events recorded yet.\n".toByteArray(StandardCharsets.UTF_8))
            }.toByteArray()
        }
        output.write(snapshot)
        output.flush()
    }
    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_LOG_BYTES) return
        val previous = File(directory, "$LOG_FILE_NAME.1")
        if (previous.exists() && !previous.delete()) {
            throw IllegalStateException("Previous diagnostic log could not be rotated.")
        }
        if (!logFile.renameTo(previous)) {
            throw IllegalStateException("Diagnostic log could not be rotated.")
        }
    }

    private fun sanitize(value: String, maxChars: Int): String {
        val singleLine = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        val redacted = singleLine
            .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;&}]+")) { match -> "${match.groupValues[1]}[REDACTED]" }
            .replace(Regex("(?i)(authorization\\s*[:=]\\s*basic\\s+)[A-Za-z0-9+/=]+")) { match -> "${match.groupValues[1]}[REDACTED]" }
            .replace(Regex("(?i)(bearer\\s+)[^\\s,;&}]+")) { match -> "${match.groupValues[1]}[REDACTED]" }
            .replace(Regex("(?i)([\\\"\\\']?(?:authorization|x-api-key|api[_ -]?key|access[_ -]?token|token)[\\\"\\\']?\\s*[:=]\\s*[\\\"\\\']?)([^\\\"\\\'\\s,;&}]+)([\\\"\\\']?)")) { match -> "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}" }
            .replace(Regex("(?i)([?&](?:access_token|token|api_key|x-api-key)=)[^&\\s]+")) { match -> "${match.groupValues[1]}[REDACTED]" }
            .replace(Regex("(?i)\\bhf_[A-Za-z0-9]{10,}\\b"), "[REDACTED]")
            // Redact common provider credentials even when a caller logs the raw value without a key name.
            .replace(
                Regex("(?i)\\b(?:sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|ya29\\.[A-Za-z0-9_-]{20,})\\b"),
                "[REDACTED]",
            )
        return redacted.take(maxChars)
    }

    companion object {
        const val LOG_FILE_NAME = "mistbell-diagnostics.log"
        const val MAX_LOG_BYTES = 1024 * 1024
        const val MAX_EVENT_CHARS = 100
        const val MAX_DETAIL_CHARS = 500
        private val LEVELS = setOf("INFO", "WARN", "ERROR")
    }
}
