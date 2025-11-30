package com.hellblazer.luciferase.resource.test.lifecycle;

import java.util.List;
import java.util.Map;

/**
 * Immutable analysis report of resource leaks detected between two snapshots.
 *
 * Provides:
 * - Detection of leaks (hasLeaks())
 * - Count of total leaks (getNetLeaks())
 * - Count by resource type (getLeakCount(Class<?>))
 * - Detailed leak information (getLeakedResourcesByType())
 * - Formatted report for test failures (toString())
 *
 * A leak is detected when a resource exists in the "after" snapshot but not
 * in the "before" snapshot.
 */
public final class LeakReport {

    private final Map<Class<?>, List<ResourceInfo>> leakedResourcesByType;
    private final ResourceSnapshot before;
    private final ResourceSnapshot after;

    /**
     * Create a leak report from analysis of two snapshots.
     *
     * @param leakedResourcesByType Map of Class to List of leaked ResourceInfo
     * @param before Snapshot before the operation
     * @param after Snapshot after the operation
     */
    public LeakReport(
        Map<Class<?>, List<ResourceInfo>> leakedResourcesByType,
        ResourceSnapshot before,
        ResourceSnapshot after
    ) {
        this.leakedResourcesByType = Map.copyOf(leakedResourcesByType);
        this.before = before;
        this.after = after;
    }

    /**
     * Check if any leaks were detected.
     *
     * @return true if leakedResourcesByType is not empty
     */
    public boolean hasLeaks() {
        return !leakedResourcesByType.isEmpty();
    }

    /**
     * Get total count of all leaked resources.
     *
     * @return Sum of leaked resources across all types
     */
    public int getNetLeaks() {
        return leakedResourcesByType.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Get count of leaked resources of a specific type.
     *
     * @param type The resource type to count
     * @return Number of leaked resources of this type (0 if type not found)
     */
    public int getLeakCount(Class<?> type) {
        return leakedResourcesByType.getOrDefault(type, List.of()).size();
    }

    /**
     * Get detailed information about all leaked resources grouped by type.
     *
     * @return Immutable map of Class to List of ResourceInfo
     */
    public Map<Class<?>, List<ResourceInfo>> getLeakedResourcesByType() {
        return leakedResourcesByType;
    }

    /**
     * Generate a formatted leak report for test failures.
     *
     * Includes:
     * - Total leak count
     * - Time range between snapshots
     * - Before/after resource counts
     * - Detailed list of leaked resources (limited to first 5 per type)
     * - Allocation stacks if available
     *
     * @return Formatted report string
     */
    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("Resource Leak Report\n");
        sb.append("===================\n");
        sb.append(String.format("Total Leaks: %d\n", getNetLeaks()));
        sb.append(String.format("Time Range: %d ms\n",
            (after.getTimestamp() - before.getTimestamp()) / 1_000_000));
        sb.append("\n");

        sb.append("Before: ").append(before.getActiveCount()).append(" active resources\n");
        sb.append("After:  ").append(after.getActiveCount()).append(" active resources\n");
        sb.append("\n");

        if (!leakedResourcesByType.isEmpty()) {
            sb.append("Leaked Resources:\n");
            for (var entry : leakedResourcesByType.entrySet()) {
                var type = entry.getKey().getSimpleName();
                var resources = entry.getValue();
                sb.append(String.format("  %s: %d leaked\n", type, resources.size()));

                // Show first 5 leaked resources
                resources.stream()
                    .limit(5)
                    .forEach(info -> {
                        sb.append(String.format("    - %s (age: %d ms)\n",
                            info.resourceId(), info.age()));
                        if (info.allocationStack() != null) {
                            sb.append("      ").append(info.allocationStack()).append("\n");
                        }
                    });

                if (resources.size() > 5) {
                    sb.append(String.format("    ... and %d more\n", resources.size() - 5));
                }
            }
        }

        return sb.toString();
    }
}
