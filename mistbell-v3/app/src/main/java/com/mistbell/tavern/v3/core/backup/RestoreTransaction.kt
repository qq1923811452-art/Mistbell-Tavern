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
    @Suppress("TooGenericExceptionCaught")
    fun replaceFiles(
        stagedToDestination: Map<File, File>,
        removeDestinations: Set<File> = emptySet(),
        beforeCommit: () -> Unit = {},
        beforeRollback: () -> Unit = {},
        afterRollback: () -> Unit = {},
    ) {
        val pairs = stagedToDestination.entries.toList()
        val destinations = pairs.map { it.value } + removeDestinations
        validatePlan(pairs, destinations)
        if (destinations.isEmpty()) {
            beforeCommit()
            return
        }

        val originallyExisted = destinations.filter { it.exists() }.toSet()
        val backups = linkedMapOf<File, File>()
        val installed = mutableListOf<File>()
        try {
            moveExistingTargets(destinations, backups)
            installStagedTargets(pairs, installed)
            beforeCommit()
        } catch (failure: Throwable) {
            rollback(
                failure = failure,
                installed = installed,
                removeDestinations = removeDestinations,
                originallyExisted = originallyExisted,
                backups = backups,
                beforeRollback = beforeRollback,
                afterRollback = afterRollback,
            )
            throw failure
        }
        deleteBackups(backups.values)
    }

    private fun validatePlan(pairs: List<Map.Entry<File, File>>, destinations: List<File>) {
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
            ensureDestinationParent(destination)
        }
    }

    private fun ensureDestinationParent(destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Restore destination directory could not be created.")
            }
        }
    }

    private fun moveExistingTargets(
        destinations: List<File>,
        backups: MutableMap<File, File>,
    ) {
        destinations.forEach { destination ->
            if (destination.exists()) {
                val backup = File(destination.parentFile, ".${destination.name}.restore-old-${idFactory()}")
                if (!destination.renameTo(backup)) throw IOException("Existing data could not be moved aside.")
                backups[destination] = backup
            }
        }
    }

    private fun installStagedTargets(
        pairs: List<Map.Entry<File, File>>,
        installed: MutableList<File>,
    ) {
        pairs.forEach { (staged, destination) ->
            if (!staged.renameTo(destination)) throw IOException("Staged data could not be installed.")
            installed += destination
        }
    }

    private fun rollback(
        failure: Throwable,
        installed: List<File>,
        removeDestinations: Set<File>,
        originallyExisted: Set<File>,
        backups: Map<File, File>,
        beforeRollback: () -> Unit,
        afterRollback: () -> Unit,
    ) {
        runCatching(beforeRollback).onFailure(failure::addSuppressed)
        val callbackCreatedRemovals = removeDestinations.filter { destination ->
            destination !in originallyExisted || destination in backups
        }
        (installed + callbackCreatedRemovals).distinctBy { it.canonicalPath }.asReversed().forEach { destination ->
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
    }

    private fun deleteBackups(backups: Collection<File>) {
        backups.forEach { backup ->
            if (backup.exists() && !backup.deleteRecursively()) {
                // A leftover rollback copy is safer than failing after the new data is committed.
            }
        }
    }
}
