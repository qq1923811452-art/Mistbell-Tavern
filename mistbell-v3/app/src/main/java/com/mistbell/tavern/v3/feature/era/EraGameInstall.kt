package com.mistbell.tavern.v3.feature.era

import java.io.File

/**
 * An imported eraTW game pack.
 *
 * [directory] is the private app directory the ZIP was extracted into. Updating a pack with the
 * same name reuses this directory so the chat layer can keep a stable path.
 */
data class EraGameInstall(
    val name: String,
    val directory: File,
    val importedAtEpochMillis: Long,
    val fileCount: Int,
)
