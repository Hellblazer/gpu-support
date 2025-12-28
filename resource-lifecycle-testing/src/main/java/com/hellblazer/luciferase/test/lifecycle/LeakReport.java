package com.hellblazer.luciferase.test.lifecycle;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable report analyzing resource lifecycle between two snapshots.
 * Categorizes resources as: leaked, freed, or persistent.
 * Provides formatted output for test failures.
 */
public final class LeakReport {
    private final Set<ResourceInfo> leaked;      // In after, not in before (BAD)
    private final Set<ResourceInfo> freed;       // In before, not in after (GOOD)
    private final Set<ResourceInfo> persistent;  // In both (NEUTRAL)
    private final long durationMs;

    /**
     * Compute diff between two snapshots.
     *
     * @param before Snapshot before component creation
     * @param after Snapshot after component cleanup
     * @return LeakReport analyzing the difference
     */
    public static LeakReport diff(ResourceSnapshot before, ResourceSnapshot after) {
        var beforeIds = before.getAllResourceIds();
        var afterIds = after.getAllResourceIds();

        // Leaked: In after, not in before (new resources not cleaned up)
        var leakedIds = new HashSet<>(afterIds);
        leakedIds.removeAll(beforeIds);
        var leaked = leakedIds.stream()
            .map(id -> after.getResourceById(id).orElseThrow())
            .collect(Collectors.toUnmodifiableSet());

        // Freed: In before, not in after (proper cleanup)
        var freedIds = new HashSet<>(beforeIds);
        freedIds.removeAll(afterIds);
        var freed = freedIds.stream()
            .map(id -> before.getResourceById(id).orElseThrow())
            .collect(Collectors.toUnmodifiableSet());

        // Persistent: In both (long-lived resources, not this component's responsibility)
        var persistentIds = new HashSet<>(beforeIds);
        persistentIds.retainAll(afterIds);
        var persistent = persistentIds.stream()
            .map(id -> after.getResourceById(id).orElseThrow())
            .collect(Collectors.toUnmodifiableSet());

        long duration = after.getTimestamp() - before.getTimestamp();

        return new LeakReport(leaked, freed, persistent, duration);
    }

    private LeakReport(Set<ResourceInfo> leaked, Set<ResourceInfo> freed,
                       Set<ResourceInfo> persistent, long durationMs) {
        this.leaked = leaked;
        this.freed = freed;
        this.persistent = persistent;
        this.durationMs = durationMs;
    }

    /**
     * Check if any resources leaked.
     */
    public boolean hasLeaks() {
        return !leaked.isEmpty();
    }

    /**
     * Get count of leaked resources.
     */
    public int getLeakedCount() {
        return leaked.size();
    }

    /**
     * Get count of properly freed resources.
     */
    public int getFreedCount() {
        return freed.size();
    }

    /**
     * Get count of persistent resources (existed before and after).
     */
    public int getPersistentCount() {
        return persistent.size();
    }

    /**
     * Get leaked resources.
     */
    public Set<ResourceInfo> getLeakedResources() {
        return leaked;
    }

    /**
     * Get freed resources.
     */
    public Set<ResourceInfo> getFreedResources() {
        return freed;
    }

    /**
     * Get persistent resources.
     */
    public Set<ResourceInfo> getPersistentResources() {
        return persistent;
    }

    /**
     * Format human-readable report for test failure messages.
     * Includes allocation stacks for leaked resources if available.
     */
    public String formatReport() {
        var sb = new StringBuilder();
        sb.append("Resource Lifecycle Report\n");
        sb.append("========================\n");
        sb.append(String.format("Duration: %d ms\n", durationMs));
        sb.append(String.format("Leaked: %d\n", leaked.size()));
        sb.append(String.format("Freed: %d\n", freed.size()));
        sb.append(String.format("Persistent: %d\n\n", persistent.size()));

        if (!leaked.isEmpty()) {
            sb.append("LEAKED RESOURCES:\n");

            // Group leaks by type
            var leakedByType = leaked.stream()
                .collect(Collectors.groupingBy(ResourceInfo::type));

            for (var entry : leakedByType.entrySet()) {
                sb.append(String.format("  %s: %d instances\n",
                    entry.getKey(), entry.getValue().size()));

                for (var info : entry.getValue()) {
                    sb.append(String.format("    - %s (age: %d ms)\n",
                        info.id(), info.ageMillis()));

                    if (info.allocationStack() != null) {
                        sb.append("      Allocated at:");
                        sb.append(info.allocationStack());
                        sb.append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }
}
