package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opencl.CL10.*;

/**
 * RAII wrapper for OpenCL event handles with ResourceTracker integration.
 * Ensures events are properly released to prevent resource leaks.
 *
 * <p>Usage:
 * <pre>
 * try (var eventHandle = new CLEventHandle(event)) {
 *     clWaitForEvents(eventHandle.get());
 *     // Event automatically released on close
 * }
 * </pre>
 *
 * <p>Pattern mirrors CLBufferHandle from GPU_MEMORY_PATTERNS.md.
 *
 * <p>Thread Safety: This class is thread-safe. Multiple threads can safely
 * call get() and close() concurrently. Close is idempotent.
 */
public class CLEventHandle extends ResourceHandle<Long> {
    private static final Logger log = LoggerFactory.getLogger(CLEventHandle.class);

    /**
     * Create a new event handle with global tracker.
     *
     * @param event The OpenCL event pointer (must not be 0)
     * @throws IllegalArgumentException if event is 0
     */
    public CLEventHandle(long event) {
        this(event, ResourceTracker.getGlobalTracker());
    }

    /**
     * Create a new event handle with explicit tracker.
     *
     * @param event The OpenCL event pointer (must not be 0)
     * @param tracker The resource tracker for leak detection (null to disable)
     * @throws IllegalArgumentException if event is 0
     */
    public CLEventHandle(long event, ResourceTracker tracker) {
        super(validateEvent(event), tracker);
        log.trace("Created CLEventHandle for event 0x{}", Long.toHexString(event));
    }

    /**
     * Validate event handle is not null.
     */
    private static Long validateEvent(long event) {
        if (event == 0L) {
            throw new IllegalArgumentException("Event handle cannot be null (0)");
        }
        return event;
    }

    /**
     * Get the OpenCL event pointer.
     *
     * @return The event pointer
     * @throws IllegalStateException if event has been closed
     */
    @Override
    public Long get() {
        return super.get();
    }

    /**
     * Perform actual cleanup of OpenCL event.
     * Releases the event via clReleaseEvent().
     *
     * @param event The event pointer to release
     */
    @Override
    protected void doCleanup(Long event) {
        if (event != null && event != 0L) {
            var status = clReleaseEvent(event);
            if (status != CL_SUCCESS) {
                log.warn("Failed to release event 0x{}: error code {}",
                    Long.toHexString(event), status);
            } else {
                log.trace("Released event 0x{}", Long.toHexString(event));
            }
        }
    }

    @Override
    public String toString() {
        var event = isValid() ? get() : 0L;
        return String.format("CLEventHandle[event=0x%s, state=%s, age=%dms]",
            Long.toHexString(event), getState(), getAgeMillis());
    }
}
