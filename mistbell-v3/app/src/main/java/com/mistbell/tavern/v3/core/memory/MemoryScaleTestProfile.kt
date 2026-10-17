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
    BASELINE(
        recordCount = 10_000,
        label = "Baseline · 10k",
        purpose = "Baseline retrieval quality, latency, storage, and memory use.",
    ),
    MEDIUM(
        recordCount = 50_000,
        label = "Medium · 50k",
        purpose = "Measure retrieval quality and resource growth at five times the baseline scale.",
    ),
    LARGE(
        recordCount = 100_000,
        label = "Large · 100k",
        purpose = "Stress-test retrieval quality, latency, storage, and memory use at ten times the baseline scale.",
    );

    companion object {
        fun fromRecordCount(recordCount: Int): MemoryScaleTestProfile? =
            entries.firstOrNull { it.recordCount == recordCount }
    }
}
