package com.mistbell.tavern.v3.core.storage

import android.content.Context
import com.mistbell.tavern.v3.core.memory.EffectiveMemoryResolver
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import com.mistbell.tavern.v3.core.memory.MemoryProjection
import com.mistbell.tavern.v3.core.memory.MemoryTextCodec
import com.mistbell.tavern.v3.core.memory.TemporalFactCodec
import com.mistbell.tavern.v3.core.memory.TemporalFactLedger
import com.mistbell.tavern.v3.core.memory.TemporalFactObservation
import com.mistbell.tavern.v3.core.memory.TemporalMemoryFact
import com.mistbell.tavern.v3.core.memory.VectorSnapshotCodec
import com.mistbell.tavern.v3.core.storage.MemoryScopeFileNames.forScope
import java.io.File

/** Copy only the current conversation, never merge it into an existing role's memory. */
object MemoryScopeMigration {
    data class Outcome(
        val copiedFiles: Int,
        val rewrittenFacts: Int,
        val rewrittenOverrides: Int,
        val failed: Boolean,
    ) {
        val didCopy: Boolean get() = copiedFiles > 0
    }

    private val fileNames =
        listOf(
            AtomicMemoryTextStore.DEFAULT_FILE_NAME,
            AtomicVectorSnapshotStore.DEFAULT_FILE_NAME,
            AtomicMemoryOverrideStore.DEFAULT_FILE_NAME,
            AtomicTemporalFactStore.DEFAULT_FILE_NAME,
        )

    @Suppress("ReturnCount", "LongMethod")
    fun copyConversationScopeToRole(
        context: Context,
        roleScopeId: String,
        conversationScopeId: String,
    ): Outcome {
        require(roleScopeId.isNotBlank() && conversationScopeId.isNotBlank())
        return try {
            MemoryScopeStorageLock.withLock(File(context.filesDir, "memory-migration")) {
                if (roleScopeId == conversationScopeId || hasScopeFiles(context.filesDir, roleScopeId)) {
                    return Outcome(0, 0, 0, false)
                }

                // Decode a consistent committed snapshot without modifying the original, even
                // when AtomicFile left only a backup. A .new file is never committed data.
                fun committed(
                    name: String,
                    limit: Long,
                ): ByteArray? = committedBytes(context.filesDir, conversationScopeId, name, limit)
                val factBytes =
                    committed(AtomicTemporalFactStore.DEFAULT_FILE_NAME, TemporalFactCodec.MAX_STORE_BYTES.toLong())
                val overrideBytes =
                    committed(AtomicMemoryOverrideStore.DEFAULT_FILE_NAME, AtomicMemoryOverrideStore.MAX_STORE_BYTES)
                val textBytes =
                    committed(AtomicMemoryTextStore.DEFAULT_FILE_NAME, MemoryTextCodec.MAX_STORE_BYTES.toLong())
                val facts = factBytes?.let(TemporalFactCodec::decode).orEmpty()
                val overrides = overrideBytes?.let(MemoryOverrideCodec::decode).orEmpty()
                val texts = textBytes?.let(MemoryTextCodec::decode).orEmpty()
                val idMap =
                    facts.associate { fact -> fact.id to migratedFactId(fact, conversationScopeId, roleScopeId) }
                val rewrittenFacts = facts.map { fact ->
                    migratedFact(fact, conversationScopeId, roleScopeId, idMap)
                }
                val factsById = facts.associateBy { it.id }
                val rewrittenOverrides = overrides.map { override ->
                    migratedOverride(override, conversationScopeId, roleScopeId, idMap, factsById)
                }
                val textIdMap =
                    idMap.mapKeys { MemoryProjection.factRecordId(it.key) }
                        .mapValues { MemoryProjection.factRecordId(it.value) }
                val files = linkedMapOf<String, ByteArray>()

                fun put(
                    name: String,
                    bytes: ByteArray,
                ) {
                    files[forScope(name, roleScopeId)] = bytes
                }
                // Keep unrelated legacy texts; rebuild all fact texts using the migrated edits.
                if (textBytes != null || factBytes != null) {
                    put(
                        AtomicMemoryTextStore.DEFAULT_FILE_NAME,
                        MemoryTextCodec.encode(
                            if (factBytes == null) {
                                texts
                            } else {
                                texts.filterNot { it.id.startsWith(MemoryProjection.FACT_TEXT_ID_PREFIX) } +
                                    EffectiveMemoryResolver.currentTexts(rewrittenFacts, rewrittenOverrides)
                            },
                        ),
                    )
                }
                if (factBytes != null) {
                    put(AtomicTemporalFactStore.DEFAULT_FILE_NAME, TemporalFactCodec.encode(rewrittenFacts))
                }
                if (overrideBytes != null) {
                    put(AtomicMemoryOverrideStore.DEFAULT_FILE_NAME, MemoryOverrideCodec.encode(rewrittenOverrides))
                }
                migratedVectorBytes(context.filesDir, conversationScopeId, textIdMap)?.let {
                    put(AtomicVectorSnapshotStore.DEFAULT_FILE_NAME, it)
                }
                if (files.isEmpty()) return Outcome(0, 0, 0, false)
                MemoryScopeStorageLock.publish(context.filesDir, files)
                Outcome(
                    files.size,
                    facts.count { it.conversationId == conversationScopeId },
                    overrides.count { it.scopeId == conversationScopeId },
                    false,
                )
            }
        } catch (_: Exception) {
            // Before the manifest there are no targets. After it, every store recovers the snapshot
            // before allowing a read/write. Never expose or accept writes over a partial migration.
            Outcome(0, 0, 0, true)
        }
    }

    private fun hasScopeFiles(
        directory: File,
        scopeId: String,
    ): Boolean =
        fileNames.any { name ->
            val target = File(directory, forScope(name, scopeId))
            target.exists() || File(target.path + ".bak").exists() || File(target.path + ".new").exists()
        }

    private fun committedBytes(
        directory: File,
        scopeId: String,
        name: String,
        limit: Long,
    ): ByteArray? {
        val base = File(directory, forScope(name, scopeId))
        val backup = File(base.path + ".bak")
        val source = if (backup.exists()) backup else base
        if (!source.exists()) return null
        require(source.length() <= limit)
        return source.readBytes()
    }

    private fun migratedFact(
        fact: TemporalMemoryFact,
        conversationScopeId: String,
        roleScopeId: String,
        idMap: Map<String, String>,
    ): TemporalMemoryFact =
        if (fact.conversationId == conversationScopeId) {
            fact.copy(conversationId = roleScopeId, id = idMap.getValue(fact.id))
        } else {
            fact
        }

    private fun migratedOverride(
        override: MemoryOverride,
        conversationScopeId: String,
        roleScopeId: String,
        idMap: Map<String, String>,
        factsById: Map<String, TemporalMemoryFact>,
    ): MemoryOverride =
        if (override.scopeId == conversationScopeId) {
            override.copy(
                scopeId = roleScopeId,
                factId = idMap[override.factId] ?: override.factId,
                sourceLogicalKey = EffectiveMemoryResolver.originalKey(override, factsById),
            )
        } else {
            override
        }

    private fun migratedFactId(
        fact: TemporalMemoryFact,
        conversationScopeId: String,
        roleScopeId: String,
    ): String =
        if (fact.conversationId != conversationScopeId) {
            fact.id
        } else {
            TemporalFactLedger.stableId(
                TemporalFactObservation(
                    roleScopeId,
                    fact.sourceTurnId,
                    fact.subject,
                    fact.predicate,
                    fact.value,
                    fact.observedAtEpochMillis,
                ),
            )
        }

    private fun migratedVectorBytes(
        directory: File,
        scopeId: String,
        textIdMap: Map<String, String>,
    ): ByteArray? {
        // Read the committed recovery copy without touching the original source files.
        val bytes =
            committedBytes(
                directory, scopeId, AtomicVectorSnapshotStore.DEFAULT_FILE_NAME,
                VectorSnapshotCodec.MAX_SNAPSHOT_BYTES.toLong(),
            )
                ?: return null
        val snapshot = VectorSnapshotCodec.decode(bytes)
        return VectorSnapshotCodec.encode(
            snapshot.copy(
                records = snapshot.records.map { record -> record.copy(id = textIdMap[record.id] ?: record.id) },
            ),
        )
    }
}
