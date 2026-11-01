package com.mistbell.tavern.v3.core.storage

import android.content.Context
import android.util.AtomicFile
import com.mistbell.tavern.v3.core.memory.MemoryOverride
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

sealed interface MemoryOverrideLoadResult {
    data object Missing : MemoryOverrideLoadResult

    data class Loaded(val overrides: List<MemoryOverride>) : MemoryOverrideLoadResult

    data class Corrupt(val safeReason: String) : MemoryOverrideLoadResult
}

/**
 * User revisions are persisted separately from the extraction ledger, so a synchronizer or a new
 * extraction run can never erase them: the projection re-reads this layer every time.
 */
interface MemoryOverridePersistence {
    fun load(): MemoryOverrideLoadResult

    /** Read-modify-write under a path-scoped lock shared by every instance in the process. */
    fun update(transform: (List<MemoryOverride>) -> List<MemoryOverride>): List<MemoryOverride>
}

/** App-private, atomic storage for one conversation scope's manual memory revisions. */
class AtomicMemoryOverrideStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : MemoryOverridePersistence {
    private val atomicFile: AtomicFile

    init {
        require(
            fileName.isNotBlank() && fileName != "." && fileName != ".." &&
                '/' !in fileName && '\\' !in fileName,
        ) { "Memory override file name must be a simple file name." }
        atomicFile = AtomicFile(File(context.filesDir, fileName))
    }

    override fun load(): MemoryOverrideLoadResult =
        synchronized(lockFor(atomicFile.baseFile)) {
            val baseFile = atomicFile.baseFile
            val backup = File(baseFile.path + ".bak")
            if (!baseFile.exists() && !backup.exists()) return MemoryOverrideLoadResult.Missing
            if (maxOf(baseFile.length(), backup.length()) > MAX_STORE_BYTES) {
                return MemoryOverrideLoadResult.Corrupt("Memory override store exceeds the size limit.")
            }
            try {
                val bytes = atomicFile.openRead().use { it.readBytes() }
                MemoryOverrideLoadResult.Loaded(MemoryOverrideCodec.decode(bytes))
            } catch (_: IOException) {
                MemoryOverrideLoadResult.Corrupt("Memory override store could not be read.")
            } catch (_: IllegalArgumentException) {
                MemoryOverrideLoadResult.Corrupt("Memory override store failed validation.")
            } catch (_: org.json.JSONException) {
                MemoryOverrideLoadResult.Corrupt("Memory override store contains invalid JSON.")
            }
        }

    override fun update(transform: (List<MemoryOverride>) -> List<MemoryOverride>): List<MemoryOverride> =
        synchronized(lockFor(atomicFile.baseFile)) {
            val current =
                when (val loaded = loadUnlocked()) {
                    MemoryOverrideLoadResult.Missing -> emptyList()
                    is MemoryOverrideLoadResult.Loaded -> loaded.overrides
                    is MemoryOverrideLoadResult.Corrupt -> throw IllegalStateException(loaded.safeReason)
                }
            val updated = transform(current)
            if (updated != current) saveUnlocked(updated)
            updated
        }

    private fun loadUnlocked(): MemoryOverrideLoadResult = load()

    private fun saveUnlocked(overrides: List<MemoryOverride>) {
        val bytes = MemoryOverrideCodec.encode(overrides)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (failure: IllegalStateException) {
            atomicFile.failWrite(stream)
            throw failure
        } catch (failure: java.io.IOException) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "mistbell-v3-memory-overrides.json"
        const val MAX_STORE_BYTES = 8L * 1024 * 1024
        private const val LOCK_STRIPES = 16
        private val LOCKS = Array(LOCK_STRIPES) { Any() }

        private fun lockFor(file: File): Any = LOCKS[(file.absolutePath.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
    }
}

/** Versioned JSON so the ledger's binary format stays untouched. */
object MemoryOverrideCodec {
    private const val VERSION = 1
    private const val MAX_OVERRIDES = 50_000

    fun encode(overrides: List<MemoryOverride>): ByteArray {
        require(overrides.size <= MAX_OVERRIDES) { "Memory override store contains too many records." }
        val array = JSONArray()
        overrides.sortedWith(compareBy(MemoryOverride::scopeId).thenBy(MemoryOverride::factId))
            .forEach { override ->
                array.put(
                    JSONObject()
                        .put("scopeId", override.scopeId)
                        .put("factId", override.factId)
                        .put("subject", override.subject)
                        .put("predicate", override.predicate)
                        .put("value", override.value)
                        .put("revision", override.revision)
                        .put("sourceLogicalKey", override.sourceLogicalKey)
                        .put("reverted", override.reverted)
                        .put("updatedAt", override.updatedAtEpochMillis),
                )
            }
        val root = JSONObject().put("version", VERSION).put("overrides", array)
        return root.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= AtomicMemoryOverrideStore.MAX_STORE_BYTES) {
                "Memory override store exceeds the size limit."
            }
        }
    }

    fun decode(bytes: ByteArray): List<MemoryOverride> {
        require(bytes.size <= AtomicMemoryOverrideStore.MAX_STORE_BYTES) {
            "Memory override store exceeds the size limit."
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optInt("version", -1) == VERSION) { "Unsupported memory override version." }
        val array =
            root.optJSONArray("overrides")
                ?: throw IllegalArgumentException("Memory override payload is missing.")
        require(array.length() <= MAX_OVERRIDES) { "Memory override store contains too many records." }
        val overrides =
            (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("Malformed override.")
                MemoryOverride(
                    scopeId = item.optString("scopeId"),
                    factId = item.optString("factId"),
                    subject = item.optString("subject"),
                    predicate = item.optString("predicate"),
                    value = item.optString("value"),
                    revision = item.optLong("revision"),
                    updatedAtEpochMillis = item.optLong("updatedAt"),
                    sourceLogicalKey = item.optString("sourceLogicalKey").takeIf(String::isNotBlank),
                    reverted = item.optBoolean("reverted", false),
                )
            }
        require(overrides.map(MemoryOverride::factId).distinct().size == overrides.size) {
            "Memory override fact IDs must be unique."
        }
        return overrides
    }
}
