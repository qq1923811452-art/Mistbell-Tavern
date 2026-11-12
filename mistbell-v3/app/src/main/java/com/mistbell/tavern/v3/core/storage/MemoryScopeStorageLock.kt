package com.mistbell.tavern.v3.core.storage

import android.util.AtomicFile
import java.io.File

/** One lock order for migration and every memory store, including nested projection updates. */
internal object MemoryScopeStorageLock {
    @PublishedApi
    internal val lock = Any()

    inline fun <T> withLock(
        file: File,
        action: () -> T,
    ): T =
        synchronized(lock) {
            recover(file.parentFile!!)
            action()
        }

    /** Publish a complete, durable snapshot; readers finish an interrupted publication first. */
    fun publish(
        directory: File,
        files: Map<String, ByteArray>,
        afterTargetWrite: () -> Unit = {},
    ) {
        val staging = File(directory, TRANSACTION_DIRECTORY)
        check(staging.mkdirs() || staging.isDirectory)
        files.forEach { (name, bytes) -> writeAtomic(File(staging, name), bytes) }
        writeAtomic(File(staging, MANIFEST), files.keys.joinToString("\n").toByteArray(Charsets.UTF_8))
        recover(directory, afterTargetWrite)
    }

    @PublishedApi
    internal fun recover(
        directory: File,
        afterTargetWrite: () -> Unit = {},
    ) {
        val staging = File(directory, TRANSACTION_DIRECTORY)
        val manifest = AtomicFile(File(staging, MANIFEST))
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists()) return
        val names = manifest.openRead().use { it.readBytes().toString(Charsets.UTF_8).lines() }
        require(names.isNotEmpty() && names.all { it.startsWith("mistbell-v3-") && '/' !in it && '\\' !in it })
        // Keep staging until every target is durable. A crash can safely replay the same snapshot.
        names.forEach { name ->
            val bytes = AtomicFile(File(staging, name)).openRead().use { it.readBytes() }
            writeAtomic(File(directory, name), bytes)
            afterTargetWrite()
        }
        manifest.delete()
        names.forEach { AtomicFile(File(staging, it)).delete() }
    }

    @Suppress("TooGenericExceptionCaught") // AtomicFile must roll back and rethrow every write failure.
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (failure: Throwable) {
            target.failWrite(stream)
            throw failure
        }
    }

    internal const val TRANSACTION_DIRECTORY = "mistbell-v3-memory-scope-transaction"
    internal const val MANIFEST = "manifest"
}
