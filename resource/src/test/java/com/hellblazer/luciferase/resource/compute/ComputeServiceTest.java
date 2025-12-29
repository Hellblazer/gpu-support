package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ComputeService high-level API.
 *
 * <p>These tests also serve as examples of how to use the ComputeService.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class ComputeServiceTest {

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
        System.out.println("OpenCL available: " + openCLAvailable);
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

    // ========== Basic API Tests ==========

    @Test
    void testSingletonInstance() {
        var instance1 = ComputeService.getInstance();
        var instance2 = ComputeService.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    void testBackendAvailable() {
        assertNotNull(compute.getBackend());
        // Should always have at least CPU fallback
        assertTrue(compute.getBackend() == GPUBackend.CPU_FALLBACK ||
                   compute.getBackend().isAvailable());
    }

    // ========== Vector Add Examples ==========

    @Test
    void testVectorAdd_Simple() {
        if (!openCLAvailable) return;

        // Example: Add two vectors
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] b = {5.0f, 6.0f, 7.0f, 8.0f};

        float[] result = compute.vectorAdd(a, b);

        assertArrayEquals(new float[]{6.0f, 8.0f, 10.0f, 12.0f}, result, 0.0001f);
    }

    @Test
    void testVectorAdd_LargeArray() {
        if (!openCLAvailable) return;

        // Example: Large array addition
        int size = 100_000;
        float[] a = new float[size];
        float[] b = new float[size];

        for (int i = 0; i < size; i++) {
            a[i] = i;
            b[i] = size - i;
        }

        float[] result = compute.vectorAdd(a, b);

        // Every element should equal size
        for (int i = 0; i < size; i++) {
            assertEquals(size, result[i], 0.0001f);
        }
    }

    @Test
    void testVectorAdd_DifferentLengthsThrows() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};

        assertThrows(IllegalArgumentException.class,
                () -> compute.vectorAdd(a, b));
    }

    // ========== SAXPY Examples ==========

    @Test
    void testSaxpy_Simple() {
        if (!openCLAvailable) return;

        // Example: SAXPY - Single-precision A*X Plus Y
        // result = 2.0 * x + y
        float alpha = 2.0f;
        float[] x = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] y = {10.0f, 20.0f, 30.0f, 40.0f};

        float[] result = compute.saxpy(alpha, x, y);

        // result[i] = 2.0 * x[i] + y[i]
        assertArrayEquals(new float[]{12.0f, 24.0f, 36.0f, 48.0f}, result, 0.0001f);
    }

    @Test
    void testSaxpy_LinearCombination() {
        if (!openCLAvailable) return;

        // Example: Linear interpolation using SAXPY
        // lerp(a, b, t) = (1-t)*a + t*b = a + t*(b-a)
        // Using saxpy: result = t * (b-a) + a

        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {10.0f, 20.0f, 30.0f};
        float t = 0.5f;

        // First compute b - a
        float[] diff = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            diff[i] = b[i] - a[i];
        }

        // Then compute t * diff + a
        float[] result = compute.saxpy(t, diff, a);

        // At t=0.5, result should be midpoint
        assertArrayEquals(new float[]{5.0f, 10.0f, 15.0f}, result, 0.0001f);
    }

    // ========== Scale Examples ==========

    @Test
    void testScale_Simple() {
        if (!openCLAvailable) return;

        // Example: Scale a vector
        float[] data = {1.0f, 2.0f, 3.0f, 4.0f};

        float[] result = compute.scale(data, 2.5f);

        assertArrayEquals(new float[]{2.5f, 5.0f, 7.5f, 10.0f}, result, 0.0001f);
    }

    @Test
    void testScale_Normalize() {
        if (!openCLAvailable) return;

        // Example: Normalize by dividing by max
        float[] data = {2.0f, 4.0f, 8.0f, 10.0f};
        float maxVal = compute.max(data);

        float[] normalized = compute.scale(data, 1.0f / maxVal);

        assertEquals(1.0f, compute.max(normalized), 0.0001f);
    }

    // ========== Reduction Examples ==========

    @Test
    void testSum() {
        float[] data = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};

        float sum = compute.sum(data);

        assertEquals(15.0f, sum, 0.0001f);
    }

    @Test
    void testMinMax() {
        float[] data = {3.0f, 1.0f, 4.0f, 1.0f, 5.0f, 9.0f, 2.0f, 6.0f};

        assertEquals(1.0f, compute.min(data), 0.0001f);
        assertEquals(9.0f, compute.max(data), 0.0001f);
    }

    // ========== Custom Operation Examples ==========

    @Test
    void testCustomOperation() throws Exception {
        if (!openCLAvailable) return;

        // Example: Custom kernel for element-wise multiply
        String source = """
            __kernel void multiply(__global const float* a,
                                   __global const float* b,
                                   __global float* result,
                                   const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    result[gid] = a[gid] * b[gid];
                }
            }
            """;

        float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] b = {2.0f, 3.0f, 4.0f, 5.0f};

        try (var op = compute.createOperation("multiply", source, "multiply")) {
            op.setInput(0, a);
            op.setInput(1, b);
            op.setOutput(2, a.length);
            op.setArg(3, a.length);

            float[] result = op.execute(a.length);

            assertArrayEquals(new float[]{2.0f, 6.0f, 12.0f, 20.0f}, result, 0.0001f);
        }
    }

    @Test
    void testCustomOperation_WithConstants() throws Exception {
        if (!openCLAvailable) return;

        // Example: Apply threshold
        String source = """
            __kernel void threshold(__global const float* input,
                                    __global float* output,
                                    const float thresh,
                                    const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    output[gid] = (input[gid] > thresh) ? 1.0f : 0.0f;
                }
            }
            """;

        float[] input = {0.1f, 0.6f, 0.3f, 0.8f, 0.4f};

        try (var op = compute.createOperation("threshold", source, "threshold")) {
            op.setInput(0, input);
            op.setOutput(1, input.length);
            op.setArg(2, 0.5f);  // threshold value
            op.setArg(3, input.length);

            float[] result = op.execute(input.length);

            assertArrayEquals(new float[]{0.0f, 1.0f, 0.0f, 1.0f, 0.0f}, result, 0.0001f);
        }
    }

    // ========== Performance Comparison Example ==========

    @Test
    void testPerformanceComparison() {
        if (!openCLAvailable) return;

        int size = 1_000_000;
        float[] a = new float[size];
        float[] b = new float[size];

        for (int i = 0; i < size; i++) {
            a[i] = i * 0.001f;
            b[i] = (size - i) * 0.001f;
        }

        // Warm up
        compute.vectorAdd(a, b);

        // Time GPU
        long gpuStart = System.nanoTime();
        float[] gpuResult = compute.vectorAdd(a, b);
        long gpuTime = System.nanoTime() - gpuStart;

        // Time CPU
        long cpuStart = System.nanoTime();
        float[] cpuResult = new float[size];
        for (int i = 0; i < size; i++) {
            cpuResult[i] = a[i] + b[i];
        }
        long cpuTime = System.nanoTime() - cpuStart;

        System.out.printf("Vector add (%d elements):%n", size);
        System.out.printf("  GPU: %.2f ms%n", gpuTime / 1_000_000.0);
        System.out.printf("  CPU: %.2f ms%n", cpuTime / 1_000_000.0);
        System.out.printf("  Speedup: %.2fx%n", (double) cpuTime / gpuTime);

        // Verify correctness
        assertArrayEquals(cpuResult, gpuResult, 0.0001f);
    }

    // ========== CPU Fallback Tests ==========

    @Test
    void testCPUFallback_VectorAdd() {
        // Force CPU by resetting and checking behavior
        // This works even without GPU
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {4.0f, 5.0f, 6.0f};

        float[] result = compute.vectorAdd(a, b);

        assertArrayEquals(new float[]{5.0f, 7.0f, 9.0f}, result, 0.0001f);
    }

    @Test
    void testCPUFallback_Saxpy() {
        float[] x = {1.0f, 2.0f, 3.0f};
        float[] y = {10.0f, 20.0f, 30.0f};

        float[] result = compute.saxpy(2.0f, x, y);

        assertArrayEquals(new float[]{12.0f, 24.0f, 36.0f}, result, 0.0001f);
    }
}
