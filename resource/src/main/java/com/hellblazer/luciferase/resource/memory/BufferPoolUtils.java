package com.hellblazer.luciferase.resource.memory;

/**
 * Shared utilities for buffer pool implementations.
 * Provides common functionality for size management, formatting, and categorization.
 */
public class BufferPoolUtils {

    /**
     * Buffer size categories for memory management and eviction policies.
     * Different categories have different idle timeout characteristics.
     */
    public enum BufferCategory {
        SMALL(0, 64 * 1024),                          // 0-64KB: Fast to reallocate
        MEDIUM(64 * 1024, 10 * 1024 * 1024),         // 64KB-10MB: Moderate cost
        XLARGE(10 * 1024 * 1024, 100 * 1024 * 1024), // 10MB-100MB: Slow to reallocate
        BATCH(100 * 1024 * 1024, Integer.MAX_VALUE);  // 100MB+: Very slow (GPU batch operations)

        private final int minSize;
        private final int maxSize;

        BufferCategory(int minSize, int maxSize) {
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        public int getMinSize() {
            return minSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        /**
         * Determine category for a given buffer size.
         */
        public static BufferCategory fromSize(int size) {
            if (size < MEDIUM.minSize) return SMALL;
            if (size < XLARGE.minSize) return MEDIUM;
            if (size < BATCH.minSize) return XLARGE;
            return BATCH;
        }
    }

    /**
     * Round up to nearest power of 2 for efficient memory pooling.
     * This reduces fragmentation and improves reuse rates.
     *
     * @param value Size to round up
     * @return Next power of 2 >= value
     */
    public static int roundUpToPowerOf2(int value) {
        if (value <= 0) {
            return 1;
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    /**
     * Format bytes for human-readable display.
     *
     * @param bytes Number of bytes
     * @return Formatted string (e.g., "1.5 MB", "512 KB")
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Large buffer threshold for warnings.
     * Buffers larger than this should typically use pooling.
     */
    public static final long LARGE_BUFFER_THRESHOLD = 100L * 1024 * 1024; // 100MB

    /**
     * Check if a size qualifies as a large buffer.
     *
     * @param size Buffer size in bytes
     * @return true if size >= LARGE_BUFFER_THRESHOLD
     */
    public static boolean isLargeBuffer(long size) {
        return size >= LARGE_BUFFER_THRESHOLD;
    }

    private BufferPoolUtils() {
        // Utility class, prevent instantiation
    }
}
