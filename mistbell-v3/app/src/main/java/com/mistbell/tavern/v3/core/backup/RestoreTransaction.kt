package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Replaces a set of existing files with staged files. Each old file is renamed beside itself,
 * so backup and replacement stay on the same filesystem. Any failed move or pre-commit callback
 * rolls all prior moves back before returning control to the caller.
 */
class RestoreTransaction(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun replaceFiles(
        stagedToDestination: Map<File, File>,
        beforeCommit: () -> Unit = {},
    ) {
        require(stagedToDestination.isNotEmpty()) { "No files were selected for restore." }
        val pairs = stagedToDestination.entries.toList()
        require(pairs.map { it.value.canonicalPath }.distinct().size == pairs.size) {
            "Restore contains duplicate destination files."
        }
        pairs.forEach { (staged, destination) ->
            require(staged.isFile && staged.canRead()) { "Staged restore file is missing." }
            destination.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Restore destination directory could not be created.")
            }
        }

        val backups = linkedMapOf<File, File>()
        val installed = mutableListOf<File>()
        try {
            pairs.forEach { (_, destination) ->
                if (destination.exists()) {
                    val backup = File(destination.parentFile, ".${destination.name}.restore-old-${idFactory()}")
                    if (!destination.renameTo(backup)) throw IOException("Existing data could not be moved aside.")
                    backups[destination] = backup
                }
            }
            pairs.forEach { (staged, destination) ->
                if (!staged.renameTo(destination)) throw IOException("Staged data could not be installed.")
                installed += destination
            }
            beforeCommit()
        } catch (failure: Throwable) {
            installed.asReversed().forEach { destination ->
                if (destination.exists() && !destination.delete()) {
                    failure.addSuppressed(IOException("Failed to remove partial restored file."))
                }
            }
            backups.entries.toList().asReversed().forEach { (destination, backup) ->
                if (backup.exists() && !backup.renameTo(destination)) {
                    failure.addSuppressed(IOException("Rollback failed; previous data remains at ${backup.absolutePath}."))
                }
            }
            throw failure
        }

        backups.values.forEach { backup ->
            if (backup.exists() && !backup.delete()) {
                // A leftover rollback file is safer than failing after the new data is committed.
            }
        }
    }
}
