package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;

/**
 * Immutable snapshot of resource metadata for leak analysis.
 * Captures key information at the time of snapshot.
 *
 * @param id Unique resource identifier (UUID)
 * @param type Resource type (class simple name: "CLBufferHandle")
 * @param ageMillis Resource age in milliseconds at snapshot time
 * @param allocationStack Allocation stack trace (nullable, debug logging only)
 */
public record ResourceInfo(
    String id,
    String type,
    long ageMillis,
    String allocationStack
) {
    /**
     * Factory method to extract ResourceInfo from ResourceHandle.
     *
     * @param handle The resource handle to extract info from
     * @return Immutable ResourceInfo snapshot
     */
    public static ResourceInfo from(ResourceHandle<?> handle) {
        return new ResourceInfo(
            handle.getId(),
            handle.getClass().getSimpleName(),
            handle.getAgeMillis(),
            handle.getAllocationStack()
        );
    }
}
