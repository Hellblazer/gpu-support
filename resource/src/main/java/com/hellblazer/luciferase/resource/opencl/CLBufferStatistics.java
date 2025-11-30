package com.hellblazer.luciferase.resource.opencl;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance metrics for CLBufferHandle allocation and reuse.
 * Tracks allocation count, reuse count, and buffer lifetimes.
 *
 * Thread-safe implementation for concurrent GPU operations.
 */
public class CLBufferStatistics {

    private final AtomicLong allocationCount = new AtomicLong(0);
    private final AtomicLong reuseCount = new AtomicLong(0);
    private final AtomicLong totalLifetimeNs = new AtomicLong(0);
    private final AtomicLong closedBufferCount = new AtomicLong(0);

    /**
     * Get total number of buffer allocations
     */
    public long getAllocationCount() {
        return allocationCount.get();
    }

    /**
     * Get total number of buffer reuses from pool
     */
    public long getReuseCount() {
        return reuseCount.get();
    }

    /**
     * Get average buffer lifetime in milliseconds
     */
    public double getAverageLifetimeMs() {
        var count = closedBufferCount.get();
        if (count == 0) {
            return 0.0;
        }
        return (totalLifetimeNs.get() / count) / 1_000_000.0;
    }

    /**
     * Get total number of closed buffers
     */
    public long getClosedCount() {
        return closedBufferCount.get();
    }

    /**
     * Record a new buffer allocation
     */
    void recordAllocation() {
        allocationCount.incrementAndGet();
    }

    /**
     * Record a buffer reuse from pool
     */
    void recordReuse() {
        reuseCount.incrementAndGet();
    }

    /**
     * Record buffer closure and lifetime
     *
     * @param lifetimeNs Buffer lifetime in nanoseconds
     */
    void recordClosure(long lifetimeNs) {
        closedBufferCount.incrementAndGet();
        totalLifetimeNs.addAndGet(lifetimeNs);
    }

    /**
     * Reset all statistics (for testing)
     */
    void reset() {
        allocationCount.set(0);
        reuseCount.set(0);
        totalLifetimeNs.set(0);
        closedBufferCount.set(0);
    }

    @Override
    public String toString() {
        return String.format(
            "CLBufferStatistics[allocations=%d, reuses=%d, closed=%d, avgLifetime=%.2fms]",
            getAllocationCount(),
            getReuseCount(),
            getClosedCount(),
            getAverageLifetimeMs()
        );
    }
}
