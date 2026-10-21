package com.mistbell.tavern.v3.core.backup

/** One file included in a backup archive, with its uncompressed size and SHA-256 digest. */
data class BackupEntry(
    val path: String,
    val size: Long,
    val sha256: String,
) {
    init {
        require(size >= 0L) { "Backup entry size cannot be negative." }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Backup entry SHA-256 is invalid." }
    }
}
