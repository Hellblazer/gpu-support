package com.hellblazer.luciferase.resource.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of resource state at a point in time.
 *
 * Captures:
 * - Timestamp of snapshot
 * - All active resources grouped by type
 * - Total allocated and freed counts
 *
 * Used for before/after comparison to detect resource leaks via diff analysis.
 * The snapshot is immutable and safe for concurrent access.
 */
public final class ResourceSnapshot {

    private final long timestamp;
    private final Map<Class<?>, Set<String>> activeResourcesByType;
    private final long totalAllocated;
    private final long totalFreed;

    /**
     * Create a snapshot of current resource state from the tracker.
     *
     * @param tracker The resource tracker to snapshot
     * @return Immutable snapshot of current state
     */
    public static ResourceSnapshot captureSnapshot(ResourceTracker tracker) {
        var activeIds = tracker.getActiveResourceIds();
        var byType = new HashMap<Class<?>, Set<String>>();

        for (String id : activeIds) {
            var resource = tracker.getResource(id);
            if (resource != null) {
                byType.computeIfAbsent(resource.getClass(), k -> new HashSet<>()).add(id);
            }
        }

        return new ResourceSnapshot(
            System.nanoTime(),
            byType,
            tracker.getTotalAllocated(),
            tracker.getTotalFreed()
        );
    }

    /**
     * Create a snapshot with explicit state.
     *
     * @param timestamp Nanosecond timestamp
     * @param activeResourcesByType Map of Class to Set of resource IDs
     * @param totalAllocated Total allocated count
     * @param totalFreed Total freed count
     */
    public ResourceSnapshot(
        long timestamp,
        Map<Class<?>, Set<String>> activeResourcesByType,
        long totalAllocated,
        long totalFreed
    ) {
        this.timestamp = timestamp;
        // Copy to ensure immutability
        this.activeResourcesByType = Map.copyOf(activeResourcesByType);
        this.totalAllocated = totalAllocated;
        this.totalFreed = totalFreed;
    }

    /**
     * Get the timestamp when this snapshot was captured.
     *
     * @return Nanosecond timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get all active resources grouped by type.
     *
     * @return Immutable map of Class to Set of resource IDs
     */
    public Map<Class<?>, Set<String>> getActiveResourcesByType() {
        return activeResourcesByType;
    }

    /**
     * Get total number of active resources.
     *
     * @return Count of all active resources
     */
    public int getActiveCount() {
        return activeResourcesByType.values().stream()
            .mapToInt(Set::size)
            .sum();
    }

    /**
     * Get count of active resources of a specific type.
     *
     * @param type The resource type to count
     * @return Count of active resources of this type (0 if type not found)
     */
    public int getActiveCount(Class<?> type) {
        return activeResourcesByType.getOrDefault(type, Set.of()).size();
    }

    /**
     * Get total allocated count at snapshot time.
     *
     * @return Total allocated resources
     */
    public long getTotalAllocated() {
        return totalAllocated;
    }

    /**
     * Get total freed count at snapshot time.
     *
     * @return Total freed resources
     */
    public long getTotalFreed() {
        return totalFreed;
    }
}
