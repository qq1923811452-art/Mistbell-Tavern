package com.mistbell.tavern.v3.feature.memory

import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Durable tombstones also cover schedulers that enqueue after a migration's job snapshot. */
data class MemoryExtractionScopeBinding(
    val legacyScopeId: String,
    val roleScopeId: String,
    val historyScopeId: String,
    val pending: Boolean,
)

interface MemoryExtractionScopeRegistry {
    fun load(): List<MemoryExtractionScopeBinding>
    fun save(bindings: List<MemoryExtractionScopeBinding>)
}

/** For injected, nonpersistent queue stores in JVM tests only. */
class InMemoryExtractionScopeRegistry : MemoryExtractionScopeRegistry {
    private var bindings = emptyList<MemoryExtractionScopeBinding>()
    override fun load() = bindings
    override fun save(bindings: List<MemoryExtractionScopeBinding>) { this.bindings = bindings.toList() }
}

/** Shares the queue lock at the repository boundary; corrupt registries fail closed. */
class AtomicMemoryExtractionScopeRegistry(directory: File, fileName: String) : MemoryExtractionScopeRegistry {
    private val file = AtomicFile(File(directory, "$fileName.scopes.json"))

    override fun load(): List<MemoryExtractionScopeBinding> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size <= MemoryExtractionJobCodec.MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val values = root.getJSONArray("bindings")
        val bindings = (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            MemoryExtractionScopeBinding(
                item.getString("legacy"), item.getString("role"), item.getString("history"), item.getBoolean("pending"),
            ).also {
                require(listOf(it.legacyScopeId, it.roleScopeId, it.historyScopeId).all { scope ->
                    scope.isNotBlank() && scope.length <= 256
                })
            }
        }
        require(bindings.map { it.legacyScopeId }.distinct().size == bindings.size)
        return bindings
    }

    override fun save(bindings: List<MemoryExtractionScopeBinding>) {
        val values = JSONArray()
        bindings.forEach { binding ->
            values.put(JSONObject().put("legacy", binding.legacyScopeId).put("role", binding.roleScopeId)
                .put("history", binding.historyScopeId).put("pending", binding.pending))
        }
        val bytes = JSONObject().put("version", 1).put("bindings", values).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MemoryExtractionJobCodec.MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
}
