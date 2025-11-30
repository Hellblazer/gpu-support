package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opencl.CL10.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/**
 * Tests for CLEventHandle RAII pattern and ResourceTracker integration.
 * Tests leak detection, exception cleanup, and multi-event scenarios.
 */
@DisplayName("CLEventHandle RAII Tests")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class CLEventHandleTest {

    private static long context;
    private static long commandQueue;
    private static long device;
    private static boolean openCLAvailable;

    private ResourceTracker tracker;

    @BeforeAll
    static void setupOpenCL() {
        try {
            // Try to initialize OpenCL
            try (var stack = MemoryStack.stackPush()) {
                var numPlatforms = stack.mallocInt(1);
                var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

                if (errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0) {
                    // Get first platform
                    var platformBuffer = stack.mallocPointer(1);
                    CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
                    var platform = platformBuffer.get(0);

                    // Get first device
                    var deviceBuffer = stack.mallocPointer(1);
                    CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, deviceBuffer, (int[]) null);
                    device = deviceBuffer.get(0);

                    // Create context
                    var errcodeBuf = stack.mallocInt(1);
                    context = CL10.clCreateContext((PointerBuffer) null, device, null, 0, errcodeBuf);

                    // Create command queue
                    commandQueue = CL10.clCreateCommandQueue(context, device, 0, errcodeBuf);

                    openCLAvailable = true;
                } else {
                    openCLAvailable = false;
                }
            }
        } catch (Exception e) {
            openCLAvailable = false;
        }
    }

    @AfterAll
    static void cleanupOpenCL() {
        if (openCLAvailable) {
            if (commandQueue != 0) {
                CL10.clReleaseCommandQueue(commandQueue);
            }
            if (context != 0) {
                CL10.clReleaseContext(context);
            }
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(openCLAvailable, "OpenCL required for CLEventHandle tests");

        // Initialize tracker
        tracker = new ResourceTracker();
        ResourceTracker.setGlobalTracker(tracker);
    }

    @AfterEach
    void tearDown() {
        // Clear tracker
        ResourceTracker.setGlobalTracker(null);
    }

    /**
     * Test 3.1: EventLeakDetectionTest
     * Verify that forgetting to close an event handle is detected as a leak.
     */
    @Test
    void testNoEventLeaksWithProperCleanup() {
        tracker.reset();
        var initialCount = tracker.getActiveCount();

        // Create 100 events and properly clean them up
        for (int i = 0; i < 100; i++) {
            try (var stack = MemoryStack.stackPush()) {
                // Create event via marker (OpenCL 1.0 compatible)
                var eventPtr = stack.mallocPointer(1);
                var errcode = clEnqueueMarker(commandQueue, eventPtr);
                assertEquals(CL_SUCCESS, errcode, "Failed to create marker event");
                var event = eventPtr.get(0);

                // Wrap in handle (should be tracked)
                try (var eventHandle = new CLEventHandle(event)) {
                    // Wait for event
                    var waitResult = clWaitForEvents(eventHandle.get());
                    assertEquals(CL_SUCCESS, waitResult, "Failed to wait for event");
                }
                // Event handle auto-closes here
            }
        }

        // Verify no leaks detected
        assertEquals(initialCount, tracker.getActiveCount(),
            "All events should be cleaned up");

        assertEquals(100, tracker.getTotalAllocated(),
            "Should have allocated 100 events");
        assertEquals(100, tracker.getTotalFreed(),
            "Should have freed 100 events");
    }

    /**
     * Test 3.1b: Verify leak is detected when NOT using try-with-resources
     */
    @Test
    void testLeakDetectedWhenNotClosed() {
        tracker.reset();
        var initialCount = tracker.getActiveCount();

        try (var stack = MemoryStack.stackPush()) {
            // Create marker event
            var eventPtr = stack.mallocPointer(1);
            var errcode = clEnqueueMarker(commandQueue, eventPtr);
            assertEquals(CL_SUCCESS, errcode, "Failed to create marker event");
            var event = eventPtr.get(0);

            // Create handle but DON'T close it (leak!)
            var leakedHandle = new CLEventHandle(event, tracker);

            // Verify it was tracked
            assertEquals(initialCount + 1, tracker.getActiveCount(),
                "Event should be tracked");

            // Handle is not closed - will leak
            // Normally tracker.shutdown() would detect this, but we verify manually
            assertTrue(tracker.getActiveCount() > initialCount,
                "Leaked handle should still be in tracker");
        }
    }

    /**
     * Test 3.2: ExceptionCleanupTest
     * Verify that events are properly cleaned up even when exceptions occur.
     */
    @Test
    void testEventCleanupOnException() {
        tracker.reset();
        var initialCount = tracker.getActiveCount();

        try (var stack = MemoryStack.stackPush()) {
            // Create marker event
            var eventPtr = stack.mallocPointer(1);
            var errcode = clEnqueueMarker(commandQueue, eventPtr);
            assertEquals(CL_SUCCESS, errcode, "Failed to create marker event");
            var event = eventPtr.get(0);

            try (var eventHandle = new CLEventHandle(event)) {
                // Wait for event
                clWaitForEvents(eventHandle.get());

                // Throw exception during processing
                throw new RuntimeException("Simulated processing error");

            } catch (RuntimeException e) {
                assertEquals("Simulated processing error", e.getMessage());
            }

            // Despite exception, event should be cleaned up by try-with-resources
        }

        // Verify cleanup occurred
        assertEquals(initialCount, tracker.getActiveCount(),
            "Event should be cleaned up despite exception");
    }

    /**
     * Test 3.3: MultiEventCleanupTest
     * Verify that multiple events in a chain are properly cleaned up.
     */
    @Test
    void testCleanupMultipleEventsInChain() {
        tracker.reset();
        var initialCount = tracker.getActiveCount();

        try (var stack = MemoryStack.stackPush()) {
            // Create chain of 10 events
            var eventHandles = new CLEventHandle[10];

            for (int i = 0; i < 10; i++) {
                // Create marker event
                var eventPtr = stack.mallocPointer(1);
                var errcode = clEnqueueMarker(commandQueue, eventPtr);
                assertEquals(CL_SUCCESS, errcode, "Failed to create marker event " + i);
                var event = eventPtr.get(0);

                eventHandles[i] = new CLEventHandle(event);
            }

            // Verify all tracked
            assertEquals(initialCount + 10, tracker.getActiveCount(),
                "All 10 events should be tracked");

            // Wait for all events
            for (var handle : eventHandles) {
                clWaitForEvents(handle.get());
            }

            // Cleanup all events
            for (var handle : eventHandles) {
                handle.close();
            }

            // Verify all cleaned up
            assertEquals(initialCount, tracker.getActiveCount(),
                "All 10 events should be cleaned up");
        }

        // Double-check no leaks
        assertTrue(tracker.getTotalFreed() >= 10, "At least 10 events should be freed");
    }

    /**
     * Test 3.4: Verify null event is rejected
     */
    @Test
    void testNullEventRejected() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CLEventHandle(0L);
        }, "Should reject null (0) event handle");
    }

    /**
     * Test 3.5: Verify double-close is safe (idempotent)
     */
    @Test
    void testDoubleCloseIsSafe() {
        try (var stack = MemoryStack.stackPush()) {
            // Create marker event
            var eventPtr = stack.mallocPointer(1);
            var errcode = clEnqueueMarker(commandQueue, eventPtr);
            assertEquals(CL_SUCCESS, errcode, "Failed to create marker event");
            var event = eventPtr.get(0);

            var eventHandle = new CLEventHandle(event);
            clWaitForEvents(eventHandle.get());

            // Close once
            eventHandle.close();

            // Close again - should be safe
            assertDoesNotThrow(() -> eventHandle.close(),
                "Double close should be idempotent");
        }
    }

    /**
     * Test 3.6: Verify get() throws after close
     */
    @Test
    void testGetAfterCloseThrows() {
        try (var stack = MemoryStack.stackPush()) {
            // Create marker event
            var eventPtr = stack.mallocPointer(1);
            var errcode = clEnqueueMarker(commandQueue, eventPtr);
            assertEquals(CL_SUCCESS, errcode, "Failed to create marker event");
            var event = eventPtr.get(0);

            var eventHandle = new CLEventHandle(event);
            clWaitForEvents(eventHandle.get());

            eventHandle.close();

            // Attempt to get after close
            assertThrows(IllegalStateException.class, () -> eventHandle.get(),
                "get() should throw after close");
        }
    }
}
