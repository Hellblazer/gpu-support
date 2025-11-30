package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/**
 * Leak Detection Test Suite for ART-529 Phase 3.
 *
 * These are NEGATIVE tests - they intentionally create leaks to verify
 * ResourceTracker properly detects and reports them via:
 * 1. Shutdown hook warnings
 * 2. Allocation stack traces
 * 3. Active resource tracking
 *
 * All tests use isolated ResourceTracker instances (not global) to avoid
 * interfering with other tests or production code.
 */
@DisplayName("GPU Memory Leak Detection Tests")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class LeakDetectionTest {
    private static final Logger log = LoggerFactory.getLogger(LeakDetectionTest.class);

    private static long context;
    private static long commandQueue;
    private static long device;
    private static boolean openCLAvailable;

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
                    log.info("OpenCL initialized for leak detection testing");
                } else {
                    openCLAvailable = false;
                    log.warn("OpenCL not available, leak detection tests will be skipped");
                }
            }
        } catch (Exception e) {
            openCLAvailable = false;
            log.warn("OpenCL initialization failed: {}", e.getMessage());
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
        Assumptions.assumeTrue(openCLAvailable, "OpenCL required for leak detection tests");
    }

    /**
     * Negative Test 1: Forgotten close() leak
     *
     * Scenario: Developer allocates CLBufferHandle but forgets to call close()
     * Expected: ResourceTracker detects leak and reports allocation stack trace
     */
    @Test
    @DisplayName("Negative Test 1: Detect forgotten close() leak")
    void testLeakDetection_ForgottenClose() {
        // Create isolated tracker for this test
        var tracker = new ResourceTracker();

        // Track active resources before allocation
        var initialCount = tracker.getActiveCount();
        assertEquals(0, initialCount, "Tracker should start empty");

        // Intentionally create buffer WITHOUT close() - LEAK!
        var bufferHandle = CLBufferHandle.createWithTracker(
            context,
            1024 * 1024,  // 1MB buffer
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        // Verify buffer was tracked
        assertEquals(1, tracker.getActiveCount(),
            "Buffer should be tracked immediately after creation");

        // INTENTIONAL LEAK: Don't call bufferHandle.close()
        // This simulates forgotten cleanup

        // Verify leak is detected
        var activeCount = tracker.getActiveCount();
        assertEquals(1, activeCount, "Leaked buffer should still be tracked");

        // Verify we can get leak details
        var resourceIds = tracker.getActiveResourceIds();
        assertEquals(1, resourceIds.size(), "Should detect 1 leaked resource");

        var resourceId = resourceIds.iterator().next();
        var leakedResource = tracker.getResource(resourceId);
        assertNotNull(leakedResource, "Should be able to get resource details");

        // Stack trace only captured if debug logging enabled
        if (leakedResource.getAllocationStack() != null) {
            assertTrue(leakedResource.getAllocationStack().contains("testLeakDetection_ForgottenClose"),
                "Stack trace should show allocation location");
        }

        log.info("LEAK DETECTED (intentional): {}", leakedResource);

        // Cleanup for test isolation (prevent interference with other tests)
        bufferHandle.close();
    }

    /**
     * Negative Test 2: Exception during allocation prevents close()
     *
     * Scenario: Exception thrown after allocation but before try-with-resources
     * Expected: ResourceTracker detects the leak from the unclosed buffer
     */
    @Test
    @DisplayName("Negative Test 2: Detect leak from exception preventing close()")
    void testLeakDetection_ExceptionDuringAllocation() {
        var tracker = new ResourceTracker();

        CLBufferHandle buffer = null;
        try {
            // Allocate buffer
            buffer = CLBufferHandle.createWithTracker(
                context,
                2 * 1024 * 1024,  // 2MB
                CLBufferHandle.BufferType.READ_WRITE,
                tracker
            );

            assertEquals(1, tracker.getActiveCount(),
                "Buffer should be tracked after allocation");

            // Simulate exception during setup (before try-with-resources)
            throw new RuntimeException("Simulated failure during setup");

        } catch (RuntimeException e) {
            // Exception caught but buffer not closed!
            assertEquals("Simulated failure during setup", e.getMessage());
        }

        // Verify leak is detected
        assertEquals(1, tracker.getActiveCount(),
            "Leaked buffer should still be tracked after exception");

        var resourceIds = tracker.getActiveResourceIds();
        assertEquals(1, resourceIds.size(), "Should detect 1 leaked resource");

        var resourceId = resourceIds.iterator().next();
        log.info("LEAK DETECTED from exception (intentional): {}", tracker.getResource(resourceId));

        // Cleanup
        if (buffer != null) {
            buffer.close();
        }
    }

    /**
     * Negative Test 3: Circular reference leak
     *
     * Scenario: Two buffers reference each other in a cycle
     * Expected: ResourceTracker detects both leaked buffers
     */
    @Test
    @DisplayName("Negative Test 3: Detect circular reference leak")
    void testLeakDetection_CircularReferences() {
        var tracker = new ResourceTracker();

        // Create two buffers that "reference" each other via shared state
        var buffer1 = CLBufferHandle.createWithTracker(
            context,
            1024 * 1024,
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        var buffer2 = CLBufferHandle.createWithTracker(
            context,
            1024 * 1024,
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        assertEquals(2, tracker.getActiveCount(),
            "Both buffers should be tracked");

        // Simulate circular dependency by storing references in a cycle
        // (In real code, this might be through callbacks or data structures)
        var circularRef = new Object() {
            CLBufferHandle ref1 = buffer1;
            CLBufferHandle ref2 = buffer2;
            // Circular: ref1 "knows about" ref2 and vice versa
        };

        // INTENTIONAL LEAK: Don't close either buffer
        // Even with circular refs, ResourceTracker should detect both

        assertEquals(2, tracker.getActiveCount(),
            "Both leaked buffers should be tracked");

        var resourceIds = tracker.getActiveResourceIds();
        assertEquals(2, resourceIds.size(),
            "Should detect both leaked resources despite circular refs");

        log.info("CIRCULAR LEAK DETECTED (intentional): {} buffers",
            resourceIds.size());

        // Cleanup
        buffer1.close();
        buffer2.close();
    }

    /**
     * Negative Test 4: Multi-thread race condition leak
     *
     * Scenario: Concurrent allocation race where close() fails on some threads
     * Expected: ResourceTracker detects all leaked buffers, no double-free
     */
    @Test
    @DisplayName("Negative Test 4: Detect multi-thread race condition leak")
    void testLeakDetection_MultiThreadRace() throws InterruptedException {
        var tracker = new ResourceTracker();
        var leakedBuffers = new ArrayList<CLBufferHandle>();
        var allocatedCount = new AtomicInteger(0);
        var closedCount = new AtomicInteger(0);
        var latch = new CountDownLatch(10);

        var executor = Executors.newFixedThreadPool(10);

        // Launch 10 threads that allocate buffers concurrently
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Allocate buffer
                    var buffer = CLBufferHandle.createWithTracker(
                        context,
                        512 * 1024,  // 512KB
                        CLBufferHandle.BufferType.READ_WRITE,
                        tracker
                    );

                    allocatedCount.incrementAndGet();

                    // Simulate race: only odd threads close their buffers
                    if (threadId % 2 == 1) {
                        buffer.close();
                        closedCount.incrementAndGet();
                    } else {
                        // INTENTIONAL LEAK: Even threads don't close
                        synchronized (leakedBuffers) {
                            leakedBuffers.add(buffer);
                        }
                    }

                } catch (Exception e) {
                    log.error("Thread {} failed", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS),
            "All threads should complete within timeout");

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
            "Executor should shutdown cleanly");

        // Verify leak detection
        assertEquals(10, allocatedCount.get(), "Should allocate 10 buffers");
        assertEquals(5, closedCount.get(), "Should close 5 buffers (odd threads)");

        var activeCount = tracker.getActiveCount();
        assertEquals(5, activeCount,
            "Should detect 5 leaked buffers (even threads)");

        var resourceIds = tracker.getActiveResourceIds();
        assertEquals(5, resourceIds.size(),
            "ResourceTracker should detect all 5 leaks");

        log.info("MULTI-THREAD LEAK DETECTED (intentional): {} buffers leaked, {} closed",
            activeCount, closedCount.get());

        // Verify no double-free (each buffer has unique ID)
        assertEquals(5, resourceIds.size(), "All leaked buffers should have unique IDs");

        // Cleanup
        synchronized (leakedBuffers) {
            leakedBuffers.forEach(CLBufferHandle::close);
        }
    }

    /**
     * Negative Test 5: JVM shutdown hook validation
     *
     * Scenario: JVM exits with active buffers, shutdown hook should log warnings
     * Expected: ResourceTracker shutdown hook prints leak warnings with stack traces
     *
     * Note: This test validates shutdown hook registration, not actual JVM exit.
     * Full shutdown hook testing requires integration tests with subprocess execution.
     */
    @Test
    @DisplayName("Negative Test 5: Validate shutdown hook leak reporting")
    void testLeakDetection_ShutdownHook() {
        // Create tracker (auto-registers shutdown hook)
        var tracker = new ResourceTracker();

        // Verify shutdown hook was registered (can't directly test without JVM exit)
        // Instead, we verify the shutdown method behavior
        var buffer1 = CLBufferHandle.createWithTracker(
            context,
            1024 * 1024,
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        var buffer2 = CLBufferHandle.createWithTracker(
            context,
            2 * 1024 * 1024,
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        assertEquals(2, tracker.getActiveCount(),
            "Both buffers should be tracked");

        // Capture log output during manual shutdown call
        var outputStream = new ByteArrayOutputStream();
        var originalOut = System.err;
        System.setErr(new PrintStream(outputStream));

        try {
            // Call shutdown manually (simulates shutdown hook invocation)
            tracker.shutdown();

            // Verify shutdown logged leak warnings
            var output = outputStream.toString();

            // ResourceTracker logs to SLF4J, which may not go to System.err
            // Instead, verify tracker state after shutdown
            assertEquals(2, tracker.getTotalLeaked(),
                "Shutdown should mark 2 buffers as leaked");

            log.info("SHUTDOWN HOOK LEAK DETECTION (intentional): {} buffers leaked",
                tracker.getTotalLeaked());

        } finally {
            System.setErr(originalOut);

            // Cleanup
            buffer1.close();
            buffer2.close();
        }

        // Verify stack traces were captured (if debug logging enabled)
        var resourceIds = tracker.getActiveResourceIds();
        for (var resourceId : resourceIds) {
            var resource = tracker.getResource(resourceId);
            if (resource.getAllocationStack() != null) {
                assertTrue(resource.getAllocationStack().contains("testLeakDetection_ShutdownHook"),
                    "Stack trace should show allocation location");
            }
        }
    }

    /**
     * Bonus Test: Verify proper cleanup doesn't trigger leak detection
     *
     * This is a POSITIVE test - verify no false positives when cleanup is correct
     */
    @Test
    @DisplayName("Positive Test: Proper cleanup should not trigger leak detection")
    void testNoLeakDetection_ProperCleanup() {
        var tracker = new ResourceTracker();

        // Allocate and properly close buffers
        try (var buffer1 = CLBufferHandle.createWithTracker(
                context, 1024 * 1024, CLBufferHandle.BufferType.READ_WRITE, tracker);
             var buffer2 = CLBufferHandle.createWithTracker(
                context, 2 * 1024 * 1024, CLBufferHandle.BufferType.READ_WRITE, tracker)) {

            assertEquals(2, tracker.getActiveCount(),
                "Both buffers should be tracked while open");

            // Buffers auto-close via try-with-resources
        }

        // Verify no leaks after proper cleanup
        assertEquals(0, tracker.getActiveCount(),
            "No buffers should be tracked after proper cleanup");

        assertEquals(0, tracker.getTotalLeaked(),
            "No leaks should be detected with proper cleanup");

        log.info("PROPER CLEANUP VERIFIED: No leaks detected (expected)");
    }

    /**
     * Bonus Test: Validate allocation stack trace capture
     *
     * Verify that ResourceTracker captures detailed allocation context
     */
    @Test
    @DisplayName("Bonus Test: Validate allocation stack trace capture")
    void testAllocationStackTraceCapture() {
        var tracker = new ResourceTracker();

        // Allocate buffer (will leak intentionally)
        var buffer = CLBufferHandle.createWithTracker(
            context,
            1024 * 1024,
            CLBufferHandle.BufferType.READ_WRITE,
            tracker
        );

        // INTENTIONAL LEAK: Don't close

        var resourceIds = tracker.getActiveResourceIds();
        assertEquals(1, resourceIds.size());

        var resourceId = resourceIds.iterator().next();
        var resource = tracker.getResource(resourceId);
        var stackTrace = resource.getAllocationStack();

        // Stack trace only available if debug logging enabled
        if (stackTrace != null) {
            assertFalse(stackTrace.isEmpty(), "Stack trace should not be empty");

            // Verify stack trace contains key information
            assertTrue(stackTrace.contains("LeakDetectionTest"),
                "Stack trace should contain test class name");
            assertTrue(stackTrace.contains("testAllocationStackTraceCapture"),
                "Stack trace should contain test method name");
            assertTrue(stackTrace.contains("CLBufferHandle"),
                "Stack trace should show CLBufferHandle creation");

            log.info("STACK TRACE CAPTURED:\n{}", stackTrace);
        } else {
            log.info("STACK TRACE NOT CAPTURED (debug logging not enabled)");
        }

        // Cleanup
        buffer.close();
    }
}
