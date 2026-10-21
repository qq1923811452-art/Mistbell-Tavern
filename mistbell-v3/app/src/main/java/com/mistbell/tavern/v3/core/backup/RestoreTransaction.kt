package com.mistbell.tavern.v3.core.backup

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Stages a multi-target swap on the same filesystem. Targets omitted from the backup can be
 * removed transactionally; a failed move or pre-commit callback restores every prior target.
 */
class RestoreTransaction(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")
    fun replaceFiles(
        stagedToDestination: Map<File, File>,
        removeDestinations: Set<File> = emptySet(),
        beforeCommit: () -> Unit = {},
        beforeRollback: () -> Unit = {},
        afterRollback: () -> Unit = {},
    ) {
        val pairs = stagedToDestination.entries.toList()
        val destinations = pairs.map { it.value } + removeDestinations
        require(destinations.map { it.canonicalPath }.distinct().size == destinations.size) {
            "Restore contains duplicate destination targets."
        }
        pairs.forEach { (staged, destination) ->
            require(staged.exists() && (staged.isFile || staged.isDirectory) && staged.canRead()) {
                "Staged restore data is missing."
            }
            require(staged.isDirectory == destination.isDirectory || !destination.exists()) {
                "Restore target type does not match the staged data."
            }
            destination.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Restore destination directory could not be created.")
                }
            }
        }
        if (destinations.isEmpty()) {
            beforeCommit()
            return
        }

        val backups = linkedMapOf<File, File>()
        val installed = mutableListOf<File>()
        try {
            destinations.forEach { destination ->
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
            runCatching(beforeRollback).onFailure(failure::addSuppressed)
            installed.asReversed().forEach { destination ->
                if (destination.exists() && !destination.deleteRecursively()) {
                    failure.addSuppressed(IOException("Failed to remove partially restored data."))
                }
            }
            backups.entries.toList().asReversed().forEach { (destination, backup) ->
                if (backup.exists() && !backup.renameTo(destination)) {
                    failure.addSuppressed(
                        IOException("Rollback failed; previous data remains at ${backup.absolutePath}."),
                    )
                }
            }
            runCatching(afterRollback).onFailure(failure::addSuppressed)
            throw failure
        }

        backups.values.forEach { backup ->
            if (backup.exists() && !backup.deleteRecursively()) {
                // A leftover rollback copy is safer than failing after the new data is committed.
            }
        }
    }
}
