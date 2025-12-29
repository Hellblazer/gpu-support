package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress and edge case tests for ComputeService.
 *
 * <p>Tests boundary conditions, large arrays, memory pressure,
 * and concurrent access patterns.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class ComputeServiceStressTest {

    private static boolean openCLAvailable;
    private ComputeService compute;

    @BeforeAll
    static void checkOpenCL() {
        try (var stack = MemoryStack.stackPush()) {
            var numPlatforms = stack.mallocInt(1);
            var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);
            openCLAvailable = errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0;
        } catch (Exception e) {
            openCLAvailable = false;
        }
    }

    @BeforeEach
    void setUp() {
        ComputeService.testReset();
        compute = ComputeService.getInstance();
    }

    @AfterEach
    void tearDown() {
        ComputeService.testReset();
    }

    // ========== Edge Case Tests ==========

    @Test
    void testVectorAdd_SingleElement() {
        float[] a = {42.0f};
        float[] b = {8.0f};

        float[] result = compute.vectorAdd(a, b);

        assertEquals(1, result.length);
        assertEquals(50.0f, result[0], 0.0001f);
    }

    @Test
    void testVectorAdd_EmptyArrays() {
        float[] a = {};
        float[] b = {};

        float[] result = compute.vectorAdd(a, b);

        assertEquals(0, result.length);
    }

    @Test
    void testVectorAdd_OddSize() {
        // Non-power-of-2 size
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
        float[] b = {7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.0f, 1.0f};

        float[] result = compute.vectorAdd(a, b);

        assertEquals(7, result.length);
        for (float v : result) {
            assertEquals(8.0f, v, 0.0001f);
        }
    }

    @Test
    void testVectorAdd_PrimeSize() {
        // Prime number size (not power of 2, odd, prime)
        int size = 997;
        float[] a = new float[size];
        float[] b = new float[size];
        for (int i = 0; i < size; i++) {
            a[i] = i;
            b[i] = 1.0f;
        }

        float[] result = compute.vectorAdd(a, b);

        assertEquals(size, result.length);
        for (int i = 0; i < size; i++) {
            assertEquals(i + 1.0f, result[i], 0.0001f);
        }
    }

    @Test
    void testSaxpy_ZeroAlpha() {
        float[] x = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] y = {10.0f, 20.0f, 30.0f, 40.0f};

        // When alpha is 0, result should equal y
        float[] result = compute.saxpy(0.0f, x, y);

        assertArrayEquals(y, result, 0.0001f);
    }

    @Test
    void testSaxpy_NegativeAlpha() {
        float[] x = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] y = {10.0f, 10.0f, 10.0f, 10.0f};

        // result = -2 * x + y
        float[] result = compute.saxpy(-2.0f, x, y);

        assertArrayEquals(new float[]{8.0f, 6.0f, 4.0f, 2.0f}, result, 0.0001f);
    }

    @Test
    void testScale_ZeroScale() {
        float[] data = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

        float[] result = compute.scale(data, 0.0f);

        for (float v : result) {
            assertEquals(0.0f, v, 0.0001f);
        }
    }

    @Test
    void testScale_NegativeScale() {
        float[] data = {1.0f, -2.0f, 3.0f, -4.0f};

        float[] result = compute.scale(data, -1.0f);

        assertArrayEquals(new float[]{-1.0f, 2.0f, -3.0f, 4.0f}, result, 0.0001f);
    }

    @Test
    void testSum_SingleElement() {
        assertEquals(42.0f, compute.sum(new float[]{42.0f}), 0.0001f);
    }

    @Test
    void testSum_EmptyArray() {
        assertEquals(0.0f, compute.sum(new float[]{}), 0.0001f);
    }

    @Test
    void testSum_AllNegatives() {
        float[] data = {-1.0f, -2.0f, -3.0f, -4.0f, -5.0f};
        assertEquals(-15.0f, compute.sum(data), 0.0001f);
    }

    @Test
    void testMinMax_SingleElement() {
        float[] data = {42.0f};
        assertEquals(42.0f, compute.min(data), 0.0001f);
        assertEquals(42.0f, compute.max(data), 0.0001f);
    }

    @Test
    void testMinMax_AllSameValue() {
        float[] data = {7.0f, 7.0f, 7.0f, 7.0f};
        assertEquals(7.0f, compute.min(data), 0.0001f);
        assertEquals(7.0f, compute.max(data), 0.0001f);
    }

    @Test
    void testMinMax_Extremes() {
        float[] data = {Float.MIN_VALUE, 0.0f, Float.MAX_VALUE};
        assertEquals(Float.MIN_VALUE, compute.min(data), 0.0001f);
        assertEquals(Float.MAX_VALUE, compute.max(data), 0.0001f);
    }

    // ========== Large Array Tests ==========

    @Test
    void testVectorAdd_LargeArray_PowerOf2() {
        if (!openCLAvailable) return;

        int size = 1 << 20; // 1 million elements
        float[] a = new float[size];
        float[] b = new float[size];

        for (int i = 0; i < size; i++) {
            a[i] = 1.0f;
            b[i] = 2.0f;
        }

        float[] result = compute.vectorAdd(a, b);

        assertEquals(size, result.length);
        // Spot check
        for (int i = 0; i < size; i += 100_000) {
            assertEquals(3.0f, result[i], 0.0001f);
        }
    }

    @Test
    void testSaxpy_LargeArray() {
        if (!openCLAvailable) return;

        int size = 500_000;
        float[] x = new float[size];
        float[] y = new float[size];

        for (int i = 0; i < size; i++) {
            x[i] = i * 0.001f;
            y[i] = 100.0f;
        }

        float[] result = compute.saxpy(2.0f, x, y);

        // Spot check: result[i] = 2 * (i * 0.001) + 100
        assertEquals(100.0f, result[0], 0.0001f);
        assertEquals(100.002f, result[1], 0.0001f);
        assertEquals(101.0f, result[500], 0.0001f);
    }

    // ========== Numerical Precision Tests ==========

    @Test
    void testVectorAdd_SmallValues() {
        float[] a = {1e-7f, 2e-7f, 3e-7f};
        float[] b = {4e-7f, 5e-7f, 6e-7f};

        float[] result = compute.vectorAdd(a, b);

        assertEquals(5e-7f, result[0], 1e-12f);
        assertEquals(7e-7f, result[1], 1e-12f);
        assertEquals(9e-7f, result[2], 1e-12f);
    }

    @Test
    void testVectorAdd_LargeValues() {
        float[] a = {1e30f, 2e30f};
        float[] b = {3e30f, 4e30f};

        float[] result = compute.vectorAdd(a, b);

        assertEquals(4e30f, result[0], 1e25f);
        assertEquals(6e30f, result[1], 1e25f);
    }

    @Test
    void testVectorAdd_MixedMagnitudes() {
        // Tests precision when adding very different magnitudes
        float[] a = {1e20f, 1e-20f};
        float[] b = {1.0f, 1.0f};

        float[] result = compute.vectorAdd(a, b);

        // Large + small: the small value is lost due to float precision
        assertEquals(1e20f, result[0], 1e15f);
        // Small + 1: should be ~1.0
        assertEquals(1.0f, result[1], 0.0001f);
    }

    // ========== Concurrent Access Tests ==========

    @Test
    void testConcurrentAccess_Singleton() throws InterruptedException {
        int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var instances = new ComputeService[threadCount];
        var errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            new Thread(() -> {
                try {
                    instances[idx] = ComputeService.getInstance();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(0, errors.get());

        // All threads should get the same instance
        for (int i = 1; i < threadCount; i++) {
            assertSame(instances[0], instances[i]);
        }
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        if (!openCLAvailable) return;

        int threadCount = 4;
        int opsPerThread = 10;
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        var rand = new Random();
                        for (int i = 0; i < opsPerThread; i++) {
                            int size = 100 + rand.nextInt(900);
                            float[] a = new float[size];
                            float[] b = new float[size];
                            for (int j = 0; j < size; j++) {
                                a[j] = rand.nextFloat();
                                b[j] = rand.nextFloat();
                            }

                            // Just ensure no exceptions
                            float[] result = compute.vectorAdd(a, b);
                            assertEquals(size, result.length);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            assertEquals(0, errors.get(), "Some concurrent operations failed");
        } finally {
            executor.shutdown();
        }
    }

    // ========== Memory Pressure Tests ==========

    @Test
    void testRepeatedOperations_NoMemoryLeak() {
        if (!openCLAvailable) return;

        // Run many operations to check for memory leaks
        int iterations = 100;
        float[] a = new float[10_000];
        float[] b = new float[10_000];

        for (int i = 0; i < a.length; i++) {
            a[i] = 1.0f;
            b[i] = 2.0f;
        }

        for (int i = 0; i < iterations; i++) {
            float[] result = compute.vectorAdd(a, b);
            assertEquals(10_000, result.length);
        }
        // If we get here without OOM, we're good
    }

    @Test
    void testCustomOperation_ResourceCleanup() throws Exception {
        if (!openCLAvailable) return;

        String source = """
            __kernel void add(__global const float* a,
                              __global float* b,
                              const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    b[gid] = a[gid] + 1.0f;
                }
            }
            """;

        // Create and close many operations to verify cleanup
        for (int i = 0; i < 50; i++) {
            try (var op = compute.createOperation("cleanup_test", source, "add")) {
                float[] input = {1.0f, 2.0f, 3.0f};
                op.setInput(0, input);
                op.setOutput(1, 3);
                op.setArg(2, 3);
                float[] result = op.execute(3);
                assertEquals(3, result.length);
            }
        }
        // If we get here without resource exhaustion, cleanup works
    }
}
