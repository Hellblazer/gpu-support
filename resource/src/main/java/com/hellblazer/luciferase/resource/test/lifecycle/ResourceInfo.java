package com.hellblazer.luciferase.resource.test.lifecycle;

/**
 * Immutable record capturing individual resource metadata for leak detection.
 *
 * Contains all information needed to identify and debug a leaked resource:
 * - Unique resource identifier
 * - Resource type for categorization
 * - Age since allocation (milliseconds)
 * - Allocation stack trace (if debug logging enabled)
 *
 * @param resourceId Unique identifier of the resource
 * @param type Class of the resource (e.g., CLBufferHandle.class)
 * @param age Age of resource in milliseconds since allocation
 * @param allocationStack Stack trace at allocation time (may be null)
 */
public record ResourceInfo(
    String resourceId,
    Class<?> type,
    long age,
    String allocationStack
) {
    /**
     * Compact constructor for validation.
     *
     * @throws IllegalArgumentException if resourceId or type is null
     */
    public ResourceInfo {
        if (resourceId == null || type == null) {
            throw new IllegalArgumentException("resourceId and type must not be null");
        }
    }
}
