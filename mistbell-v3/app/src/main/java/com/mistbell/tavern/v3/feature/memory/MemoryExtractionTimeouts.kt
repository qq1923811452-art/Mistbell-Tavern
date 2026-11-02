package com.mistbell.tavern.v3.feature.memory

/** The worker must outlive the HTTP budget, leaving time to persist its terminal callback. */
internal object MemoryExtractionTimeouts {
    const val CALL_MILLIS = 180_000L
    const val PERSISTENCE_GRACE_MILLIS = 30_000L
    const val ATTEMPT_MILLIS = CALL_MILLIS + PERSISTENCE_GRACE_MILLIS
}
