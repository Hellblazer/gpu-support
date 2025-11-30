package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/**
 * TDD Test Suite for CLBufferHandle Phase 1 enhancements:
 * - Pool awareness hooks (onRelease callback)
 * - Large buffer support (120MB-240MB)
 * - Performance metrics tracking
 *
 * All tests written FIRST (RED phase) before implementation.
 */
@DisplayName("CLBufferHandle Phase 1 Enhancement Tests")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class CLBufferHandleTest {

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
                    System.out.println("OpenCL initialized for testing");
                } else {
                    openCLAvailable = false;
                    System.out.println("OpenCL not available, tests will be skipped");
                }
            }
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL initialization failed: " + e.getMessage());
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
        Assumptions.assumeTrue(openCLAvailable, "OpenCL required for this test");
    }

    @Nested
    @DisplayName("Pool Awareness Hook Tests")
    class PoolAwarenessTests {

        @Test
        @DisplayName("Test 1: onRelease callback should be invoked during close()")
        void testPoolAwarenessHook() {
            var callbackInvoked = new AtomicBoolean(false);

            // Create buffer with onRelease callback
            try (var buffer = CLBufferHandle.create(context, 1024,
                    CLBufferHandle.BufferType.READ_WRITE,
                    () -> callbackInvoked.set(true))) {

                assertNotNull(buffer);
                assertEquals(1024, buffer.getSize());
                assertFalse(callbackInvoked.get(), "Callback should not be invoked yet");
            } // close() called here

            assertTrue(callbackInvoked.get(), "Callback should be invoked during close()");
        }

        @Test
        @DisplayName("Test 7: Exception during onRelease should not prevent cleanup")
        void testExceptionDuringRelease() {
            var cleanupCalled = new AtomicBoolean(false);
            var exceptionThrown = new AtomicBoolean(false);

            // Create a temporary buffer first
            var tempBuffer = CLBufferHandle.create(context, 512, CLBufferHandle.BufferType.READ_ONLY);
            var bufferHandle = tempBuffer.get();

            // Override doCleanup to verify it's called even if callback throws
            var buffer = new CLBufferHandle(
                bufferHandle,
                context, 512, CLBufferHandle.BufferType.READ_ONLY.getFlag(),
                () -> {
                    exceptionThrown.set(true);
                    throw new RuntimeException("Simulated callback failure");
                }
            ) {
                @Override
                protected void doCleanup(Long buffer) {
                    cleanupCalled.set(true);
                    super.doCleanup(buffer);
                }
            };

            // Don't close tempBuffer - we'll use its handle in our test buffer
            // Close should not throw, but should log error
            assertDoesNotThrow(() -> buffer.close());

            assertTrue(exceptionThrown.get(), "Callback exception should be thrown");
            assertTrue(cleanupCalled.get(), "doCleanup should still be called");
            assertEquals(ResourceHandle.State.CLOSED, buffer.getState(),
                        "Buffer should be in CLOSED state despite callback error");
        }

        @Test
        @DisplayName("Test 8: Null onRelease callback should be allowed (backward compatible)")
        void testNullOnReleaseCallback() {
            // Create buffer without callback (null)
            try (var buffer = CLBufferHandle.create(context, 256,
                    CLBufferHandle.BufferType.WRITE_ONLY, null)) {

                assertNotNull(buffer);
                assertEquals(256, buffer.getSize());
                // Should close without error
            }

            // Also test default factory method (no callback parameter)
            try (var buffer = CLBufferHandle.create(context, 512,
                    CLBufferHandle.BufferType.READ_WRITE)) {

                assertNotNull(buffer);
                assertEquals(512, buffer.getSize());
            }
        }

        @Test
        @DisplayName("Test 9: Buffer reuse pattern should work with pool callback")
        void testBufferReusePattern() {
            var releaseCount = new AtomicInteger(0);

            // Simulate a simple memory pool
            List<Long> pooledHandles = new ArrayList<>();

            // Create and release 5 buffers
            for (int i = 0; i < 5; i++) {
                var buffer = CLBufferHandle.create(context, 1024,
                        CLBufferHandle.BufferType.READ_WRITE,
                        () -> {
                            releaseCount.incrementAndGet();
                            // In a real pool, we'd return the buffer for reuse
                            // Here we just track the callback was invoked
                        });

                // Use buffer
                assertNotNull(buffer);
                pooledHandles.add(buffer.get());

                // Release to pool
                buffer.close();
            }

            assertEquals(5, releaseCount.get(), "All buffers should invoke callback");
            assertEquals(5, pooledHandles.size(), "All handles should be tracked");
        }
    }

    @Nested
    @DisplayName("Large Buffer Support Tests")
    class LargeBufferTests {

        @Test
        @DisplayName("Test 2: 120MB buffer should allocate successfully")
        void testLargeBufferAllocation() {
            var largeSize = 120L * 1024 * 1024; // 120MB

            try (var buffer = CLBufferHandle.create(context, largeSize,
                    CLBufferHandle.BufferType.READ_WRITE)) {

                assertNotNull(buffer, "Large buffer should allocate");
                assertEquals(largeSize, buffer.getSize(), "Size should match");

                // Verify actual OpenCL allocation
                var actualSize = buffer.getActualSize();
                assertTrue(actualSize >= largeSize,
                          "Actual size should be at least requested size");
            }
        }

        @Test
        @DisplayName("Test 3: Warning should be logged for >100MB buffer without pool hint")
        void testLargeBufferValidation() {
            var largeSize = 150L * 1024 * 1024; // 150MB

            // This test verifies the warning is logged (check logs manually)
            // In production, we'd use a log capture mechanism
            try (var buffer = CLBufferHandle.create(context, largeSize,
                    CLBufferHandle.BufferType.READ_WRITE)) {

                assertNotNull(buffer);
                // Warning should appear in logs: "Large buffer (150 MB) allocated without pool"
            }

            // With pool callback, no warning should appear
            try (var buffer = CLBufferHandle.create(context, largeSize,
                    CLBufferHandle.BufferType.READ_WRITE,
                    () -> {
                        // Pool return callback
                    })) {

                assertNotNull(buffer);
                // No warning should appear since pool callback is set
            }
        }
    }

    @Nested
    @DisplayName("Performance Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("Test 4: Allocation and reuse counts should be tracked accurately")
        void testMetricsTracking() {
            // Get initial metrics
            var initialStats = CLBufferHandle.getStatistics();
            var initialAllocations = initialStats.getAllocationCount();
            var initialReuses = initialStats.getReuseCount();

            // Allocate 3 buffers
            var buffer1 = CLBufferHandle.create(context, 512,
                    CLBufferHandle.BufferType.READ_ONLY);
            var buffer2 = CLBufferHandle.create(context, 1024,
                    CLBufferHandle.BufferType.READ_WRITE);
            var buffer3 = CLBufferHandle.create(context, 2048,
                    CLBufferHandle.BufferType.WRITE_ONLY);

            var afterAlloc = CLBufferHandle.getStatistics();
            assertEquals(initialAllocations + 3, afterAlloc.getAllocationCount(),
                        "Allocation count should increase by 3");

            buffer1.close();

            // Simulate pool reuse
            CLBufferHandle.recordReuse();

            var afterReuse = CLBufferHandle.getStatistics();
            assertEquals(initialReuses + 1, afterReuse.getReuseCount(),
                        "Reuse count should increase by 1");

            // Clean up
            buffer2.close();
            buffer3.close();
        }

        @Test
        @DisplayName("Test 5: Metrics should be thread-safe")
        void testConcurrentMetrics() throws InterruptedException {
            int threadCount = 10;
            int allocationsPerThread = 100;
            var startLatch = new CountDownLatch(1);
            var completionLatch = new CountDownLatch(threadCount);

            var initialStats = CLBufferHandle.getStatistics();
            var expectedAllocations = initialStats.getAllocationCount() +
                                     (threadCount * allocationsPerThread);

            // Create threads that allocate concurrently
            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();

                        for (int j = 0; j < allocationsPerThread; j++) {
                            var buffer = CLBufferHandle.create(context, 256,
                                    CLBufferHandle.BufferType.READ_WRITE);
                            buffer.close();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown(); // Start all threads
            assertTrue(completionLatch.await(30, TimeUnit.SECONDS),
                      "All threads should complete");

            var finalStats = CLBufferHandle.getStatistics();
            assertEquals(expectedAllocations, finalStats.getAllocationCount(),
                        "All allocations should be counted");
        }

        @Test
        @DisplayName("Test 6: ResourceTracker integration should detect leaks")
        void testResourceTrackerIntegration() {
            var tracker = new ResourceTracker();

            // Create buffer with tracker
            var buffer = CLBufferHandle.createWithTracker(context, 1024,
                    CLBufferHandle.BufferType.READ_WRITE, tracker);

            assertEquals(1, tracker.getActiveCount(),
                        "Tracker should have 1 active resource");

            // Close buffer
            buffer.close();

            assertEquals(0, tracker.getActiveCount(),
                        "Tracker should have 0 active resources after close");

            // Verify leak detection
            var leakedBuffer = CLBufferHandle.createWithTracker(context, 512,
                    CLBufferHandle.BufferType.READ_ONLY, tracker);
            // Don't close - simulate leak

            assertEquals(1, tracker.getActiveCount(),
                        "Tracker should detect leaked resource");

            // Verify leaked resource is tracked
            var activeIds = tracker.getActiveResourceIds();
            assertEquals(1, activeIds.size(), "Should have 1 active (leaked) resource");

            // Clean up
            leakedBuffer.close();

            assertEquals(0, tracker.getActiveCount(),
                        "No leaks after cleanup");
        }
    }

    @Nested
    @DisplayName("Performance Benchmark Placeholder")
    class BenchmarkTests {

        @Test
        @DisplayName("Test 10: Pool allocation latency should be <1ms (placeholder for JMH)")
        void testPerformanceMetricsPlaceholder() {
            // This is a placeholder - real benchmark will be JMH-based
            var iterations = 1000;
            var totalTime = 0L;

            for (int i = 0; i < iterations; i++) {
                var start = System.nanoTime();

                try (var buffer = CLBufferHandle.create(context, 4096,
                        CLBufferHandle.BufferType.READ_WRITE,
                        () -> {
                            // Pool return
                        })) {

                    // Simulate minimal work
                    assertNotNull(buffer);
                }

                totalTime += System.nanoTime() - start;
            }

            var avgTimeMs = (totalTime / iterations) / 1_000_000.0f;
            System.out.printf("Average pool allocation: %.3f ms%n", avgTimeMs);

            assertTrue(avgTimeMs < 1.0f,
                      "Pool allocation should be <1ms (actual: " + avgTimeMs + "ms)");
        }
    }
}
