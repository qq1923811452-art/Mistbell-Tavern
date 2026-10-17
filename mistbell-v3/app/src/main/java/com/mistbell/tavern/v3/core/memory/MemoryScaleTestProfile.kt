package com.mistbell.tavern.v3.core.memory

/**
 * Repeatable dataset sizes for comparing memory retrieval on real devices.
 *
 * These are benchmark profiles, not retention limits: selecting a profile must never delete,
 * prune, or hide real user memories. Benchmark data should be synthetic or a disposable copy.
 */
enum class MemoryScaleTestProfile(
    val recordCount: Int,
    val label: String,
    val purpose: String,
) {
    SMALL(
        recordCount = 1_000,
        label = "Small · 1k",
        purpose = "Establish baseline retrieval quality, latency, and storage.",
    ),
    MEDIUM(
        recordCount = 5_000,
        label = "Medium · 5k",
        purpose = "Measure growth in lookup latency and memory use.",
    ),
    LARGE(
        recordCount = 10_000,
        label = "Large · 10k",
        purpose = "Stress-test the largest snapshot that fits the current 8,000,000-value budget at 768 dimensions.",
    );

    companion object {
        fun fromRecordCount(recordCount: Int): MemoryScaleTestProfile? =
            entries.firstOrNull { it.recordCount == recordCount }
    }
}
