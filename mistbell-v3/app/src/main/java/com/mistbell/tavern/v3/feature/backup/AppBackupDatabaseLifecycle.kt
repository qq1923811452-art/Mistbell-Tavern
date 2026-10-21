package com.mistbell.tavern.v3.feature.backup

import com.mistbell.tavern.v3.core.backup.BackupDatabaseLifecycle
import com.mistbell.tavern.v3.feature.chat.SqliteChatStore
import com.mistbell.tavern.v3.feature.sessions.SessionStore

/**
 * Closes every live SQLite helper before a restore swaps database files.
 *
 * Reopening is lazy: both helpers create their handle on the next read, so an explicit reopen
 * would only duplicate work and risk opening a half-swapped file.
 */
class AppBackupDatabaseLifecycle : BackupDatabaseLifecycle {
    override fun closeBeforeRestore() {
        SqliteChatStore.closeAllOpen()
        SessionStore.closeAllOpen()
    }

    override fun reopenAfterRestore() = Unit
}
