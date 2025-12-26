package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceTracker;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of active resources at a point in time.
 * Provides grouping by resource type for analysis.
 * Thread-safe, suitable for concurrent testing.
 */
public final class ResourceSnapshot {
    private final Map<String, List<ResourceInfo>> resourcesByType;
    private final long timestamp;
    private final int totalCount;

    /**
     * Capture snapshot of all active resources from tracker.
     * Handles race conditions gracefully (resource closed between query and lookup).
     *
     * @param tracker The resource tracker to query
     */
    public ResourceSnapshot(ResourceTracker tracker) {
        this.timestamp = System.currentTimeMillis();

        // Get snapshot of active IDs
        var activeIds = tracker.getActiveResourceIds();
        this.totalCount = activeIds.size();

        // Build resource info map grouped by type
        var resources = new HashMap<String, List<ResourceInfo>>();
        for (var id : activeIds) {
            var handle = tracker.getResource(id);
            if (handle != null) {  // null check for race condition
                var info = ResourceInfo.from(handle);
                resources.computeIfAbsent(info.type(), k -> new ArrayList<>())
                        .add(info);
            }
        }

        // Make immutable
        var immutableMap = new HashMap<String, List<ResourceInfo>>();
        for (var entry : resources.entrySet()) {
            immutableMap.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        this.resourcesByType = Collections.unmodifiableMap(immutableMap);
    }

    /**
     * Get total count of resources in snapshot.
     */
    public int getTotalCount() {
        return totalCount;
    }

    /**
     * Get timestamp when snapshot was captured.
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get all resource types present in snapshot.
     */
    public Set<String> getResourceTypes() {
        return resourcesByType.keySet();
    }

    /**
     * Get all resources of a specific type.
     *
     * @param type The resource type (class simple name)
     * @return List of resources, or empty list if type not present
     */
    public List<ResourceInfo> getResourcesByType(String type) {
        return resourcesByType.getOrDefault(type, Collections.emptyList());
    }

    /**
     * Lookup specific resource by ID.
     *
     * @param id The resource ID
     * @return ResourceInfo if found, empty otherwise
     */
    public Optional<ResourceInfo> getResourceById(String id) {
        return resourcesByType.values().stream()
            .flatMap(List::stream)
            .filter(info -> info.id().equals(id))
            .findFirst();
    }

    /**
     * Get all resource IDs in snapshot (for diff operations).
     */
    Set<String> getAllResourceIds() {
        return resourcesByType.values().stream()
            .flatMap(List::stream)
            .map(ResourceInfo::id)
            .collect(Collectors.toSet());
    }
}
