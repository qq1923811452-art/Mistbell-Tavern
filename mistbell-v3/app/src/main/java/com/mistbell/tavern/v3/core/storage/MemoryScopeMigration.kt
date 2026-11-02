package com.mistbell.tavern.v3.core.storage

import android.content.Context
import com.mistbell.tavern.v3.core.memory.TemporalFactIdentity
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames.forScope
import java.io.File

/**
 * One-way, copy-only migration from a conversation-scoped memory folder to a role-scoped one.
 *
 * When memory becomes shared across every conversation with the same role, the previous
 * per-conversation files are no longer reachable. Rather than merge histories (which can mix
 * unrelated story lines), the active conversation's files are *copied* into the role scope the
 * first time that scope is opened. The originals are never deleted or modified, so switching back
 * to per-conversation isolation still finds its data intact.
 *
 * Two record types embed the scope id itself and must be rewritten during the copy:
 *
 * - temporal facts group "current value" per `conversationId`, so a stale conversation id would
 *   stop new observations from superseding the copied ones;
 * - manual overrides are matched against `fact.conversationId` through `scopeId`.
 *
 * Everything here is best-effort: a failure leaves the role scope empty (a normal first run)
 * instead of breaking chat.
 */
object MemoryScopeMigration {
    data class Outcome(
        val copiedFiles: Int,
        val rewrittenFacts: Int,
        val rewrittenOverrides: Int,
        val failed: Boolean,
    ) {
        val didCopy: Boolean get() = copiedFiles > 0
    }

    private val SCOPED_FILE_NAMES = listOf(
        AtomicMemoryTextStore.DEFAULT_FILE_NAME,
        AtomicVectorSnapshotStore.DEFAULT_FILE_NAME,
        AtomicMemoryOverrideStore.DEFAULT_FILE_NAME,
        AtomicTemporalFactStore.DEFAULT_FILE_NAME,
    )

    @Suppress("ReturnCount") // Guard clauses separate "nothing to do" from "copied" and "failed".
    fun copyConversationScopeToRole(
        context: Context,
        roleScopeId: String,
        conversationScopeId: String,
    ): Outcome {
        require(roleScopeId.isNotBlank() && conversationScopeId.isNotBlank())
        if (roleScopeId == conversationScopeId) {
            return Outcome(copiedFiles = 0, rewrittenFacts = 0, rewrittenOverrides = 0, failed = false)
        }
        var copiedFiles = 0
        try {
            for (fileName in SCOPED_FILE_NAMES) {
                val source = File(context.filesDir, forScope(fileName, conversationScopeId))
                val target = File(context.filesDir, forScope(fileName, roleScopeId))
                // Never overwrite: an existing role scope is authoritative.
                if (!source.exists() || target.exists()) continue
                source.copyTo(target, overwrite = false)
                copiedFiles++
                val sourceBackup = File(source.path + ".bak")
                if (sourceBackup.exists()) {
                    sourceBackup.copyTo(File(target.path + ".bak"), overwrite = false)
                }
            }
            if (copiedFiles == 0) {
                return Outcome(copiedFiles = 0, rewrittenFacts = 0, rewrittenOverrides = 0, failed = false)
            }
            return Outcome(
                copiedFiles = copiedFiles,
                rewrittenFacts = rewriteFacts(context, roleScopeId, conversationScopeId),
                rewrittenOverrides = rewriteOverrides(context, roleScopeId, conversationScopeId),
                failed = false,
            )
        } catch (_: Exception) {
            return Outcome(copiedFiles, rewrittenFacts = 0, rewrittenOverrides = 0, failed = true)
        }
    }

    @Suppress("ReturnCount") // Guard clauses skip a missing or already-rewritten store.
    private fun rewriteFacts(context: Context, roleScopeId: String, conversationScopeId: String): Int {
        val store = AtomicTemporalFactStore(
            context,
            forScope(AtomicTemporalFactStore.DEFAULT_FILE_NAME, roleScopeId),
        )
        val loaded = store.load()
        if (loaded !is TemporalFactLoadResult.Loaded) return 0
        val changed = loaded.facts.count { it.conversationId == conversationScopeId }
        if (changed == 0) return 0
        store.save(
            loaded.facts.map { fact ->
                if (fact.conversationId != conversationScopeId) {
                    fact
                } else {
                    // The id is derived from the conversation scope, so rewriting the scope
                    // without recomputing the id would defeat deduplication forever after.
                    fact.copy(
                        conversationId = roleScopeId,
                        id = TemporalFactIdentity.forObservation(
                            conversationId = roleScopeId,
                            sourceTurnId = fact.sourceTurnId,
                            subject = fact.subject,
                            predicate = fact.predicate,
                            value = fact.value,
                        ),
                    )
                }
            },
        )
        return changed
    }

    @Suppress("ReturnCount") // Guard clauses skip a missing or already-rewritten store.
    private fun rewriteOverrides(context: Context, roleScopeId: String, conversationScopeId: String): Int {
        val store = AtomicMemoryOverrideStore(
            context,
            forScope(AtomicMemoryOverrideStore.DEFAULT_FILE_NAME, roleScopeId),
        )
        val loaded = store.load()
        if (loaded !is MemoryOverrideLoadResult.Loaded) return 0
        val changed = loaded.overrides.size
        if (changed == 0) return 0
        // The override store has no public save(); its read-modify-write API is the only writer,
        // so reuse it to keep the same atomic path and path-scoped lock.
        store.update { overrides ->
            overrides.map { override ->
                if (override.scopeId == conversationScopeId) override.copy(scopeId = roleScopeId) else override
            }
        }
        return changed
    }
}
