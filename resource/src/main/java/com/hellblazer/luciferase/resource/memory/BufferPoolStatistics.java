package com.hellblazer.luciferase.resource.memory;

/**
 * Common interface for buffer pool statistics.
 * Provides unified metrics across different pool implementations.
 */
public interface BufferPoolStatistics {

    /**
     * Get total number of allocations (both new and reused).
     */
    long getTotalAllocations();

    /**
     * Get number of pool hits (reused buffers).
     */
    long getHitCount();

    /**
     * Get number of pool misses (new allocations).
     */
    long getMissCount();

    /**
     * Get total memory allocated in bytes.
     */
    long getTotalAllocated();

    /**
     * Get total memory in megabytes.
     */
    default long getTotalMemoryMB() {
        return getTotalAllocated() / (1024 * 1024);
    }

    /**
     * Get pool hit rate as a ratio (0.0 to 1.0).
     */
    default float getHitRate() {
        long total = getTotalAllocations();
        return total > 0 ? (float) getHitCount() / total : 0.0f;
    }

    /**
     * Get pool hit rate as a percentage (0.0 to 100.0).
     */
    default float getHitRatePercent() {
        return getHitRate() * 100.0f;
    }

    /**
     * Get number of buffers currently in use (borrowed/active).
     */
    int getActiveBufferCount();

    /**
     * Get number of evicted buffers.
     */
    default long getEvictionCount() {
        return 0; // Default implementation for pools without eviction tracking
    }

    /**
     * Format statistics as a human-readable string.
     */
    default String formatStatistics() {
        return String.format(
            "BufferPool[allocations=%d, hits=%d, misses=%d, hitRate=%.1f%%, memory=%s, active=%d]",
            getTotalAllocations(),
            getHitCount(),
            getMissCount(),
            getHitRatePercent(),
            BufferPoolUtils.formatBytes(getTotalAllocated()),
            getActiveBufferCount()
        );
    }
}
