package com.hellblazer.luciferase.resource.compute.opencl;

import com.hellblazer.luciferase.resource.compute.BackendSelector;
import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.GPUBackend;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 2 OpenCL components.
 *
 * <p>Tests the complete workflow: context → buffer → kernel → execute → read.
 * Tests require OpenCL availability - skipped if not available and in CI environments.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class ComputeIntegrationTest {

    private static boolean openCLAvailable;

    // Vector addition kernel
    private static final String VECTOR_ADD_KERNEL = """
            __kernel void vectorAdd(__global const float* a,
                                     __global const float* b,
                                     __global float* result,
                                     const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    result[gid] = a[gid] + b[gid];
                }
            }
            """;

    // SAXPY kernel: result = a * x + y
    private static final String SAXPY_KERNEL = """
            __kernel void saxpy(__global const float* x,
                               __global const float* y,
                               __global float* result,
                               const float a,
                               const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    result[gid] = a * x[gid] + y[gid];
                }
            }
            """;

    // Matrix multiply kernel (simplified for small matrices)
    private static final String DOT_PRODUCT_KERNEL = """
            __kernel void dotProduct(__global const float* a,
                                     __global const float* b,
                                     __global float* result,
                                     __local float* scratch,
                                     const int size) {
                int gid = get_global_id(0);
                int lid = get_local_id(0);
                int groupSize = get_local_size(0);

                // Each work item computes partial product
                float partial = 0.0f;
                for (int i = gid; i < size; i += get_global_size(0)) {
                    partial += a[i] * b[i];
                }
                scratch[lid] = partial;

                barrier(CLK_LOCAL_MEM_FENCE);

                // Reduction within work group
                for (int stride = groupSize / 2; stride > 0; stride /= 2) {
                    if (lid < stride) {
                        scratch[lid] += scratch[lid + stride];
                    }
                    barrier(CLK_LOCAL_MEM_FENCE);
                }

                if (lid == 0) {
                    result[get_group_id(0)] = scratch[0];
                }
            }
            """;

    @BeforeAll
    static void checkOpenCL() {
        try (var stack = MemoryStack.stackPush()) {
            var numPlatforms = stack.mallocInt(1);
            var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

            if (errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0) {
                var platformBuffer = stack.mallocPointer(1);
                CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
                var platform = platformBuffer.get(0);

                var numDevices = stack.mallocInt(1);
                var result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, null, numDevices);
                if (result != CL10.CL_SUCCESS) {
                    result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_CPU, null, numDevices);
                }

                openCLAvailable = result == CL10.CL_SUCCESS && numDevices.get(0) > 0;
            }
            System.out.println("OpenCL available for integration tests: " + openCLAvailable);
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
        }
    }

    // --- Full Workflow Tests ---

    @Test
    void testFullVectorAddWorkflow() throws Exception {
        if (!openCLAvailable) return;

        int size = 1024;
        var a = new float[size];
        var b = new float[size];
        var result = new float[size];

        // Initialize input data
        for (int i = 0; i < size; i++) {
            a[i] = i * 0.5f;
            b[i] = i * 0.25f;
        }

        // Full workflow: context -> buffers -> kernel -> execute -> read
        try (var bufferA = OpenCLBuffer.createWithData(a, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.createWithData(b, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("vectorAdd")) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, size);

            kernel.execute(size);
            kernel.finish();

            bufferResult.download(result);

            // Verify results with CPU reference
            for (int i = 0; i < size; i++) {
                float expected = a[i] + b[i];
                assertEquals(expected, result[i], 0.0001f,
                        "Mismatch at index " + i);
            }
        }
    }

    @Test
    void testFullSAXPYWorkflow() throws Exception {
        if (!openCLAvailable) return;

        int size = 2048;
        float alpha = 2.5f;
        var x = new float[size];
        var y = new float[size];
        var result = new float[size];

        // Initialize input data
        for (int i = 0; i < size; i++) {
            x[i] = i;
            y[i] = size - i;
        }

        try (var bufferX = OpenCLBuffer.createWithData(x, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferY = OpenCLBuffer.createWithData(y, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("saxpy")) {

            kernel.compile(SAXPY_KERNEL, "saxpy");

            kernel.setBufferArg(0, bufferX, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferY, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setFloatArg(3, alpha);
            kernel.setIntArg(4, size);

            kernel.execute(size);
            kernel.finish();

            bufferResult.download(result);

            // Verify: result = alpha * x + y
            for (int i = 0; i < size; i++) {
                float expected = alpha * x[i] + y[i];
                assertEquals(expected, result[i], 0.0001f,
                        "SAXPY mismatch at index " + i);
            }
        }
    }

    // --- OpenCLContext Integration Tests ---

    @Test
    void testOpenCLContextAcquireRelease() {
        if (!openCLAvailable) return;

        var ctx = OpenCLContext.getInstance();

        // Acquire should initialize
        if (!ctx.isInitialized()) {
            ctx.acquire();
        }

        assertTrue(ctx.isInitialized());
        assertTrue(ctx.getContext() != 0);
        assertTrue(ctx.getCommandQueue() != 0);
        assertTrue(ctx.getDevice() != 0);

        // Multiple acquires should work
        ctx.acquire();
        ctx.acquire();
        assertTrue(ctx.getRefCount() >= 1);

        // Releases should work
        ctx.release();
        ctx.release();
    }

    @Test
    void testBackendSelectorSelectsOpenCL() {
        if (!openCLAvailable) return;

        var backend = BackendSelector.getOptimalBackend();
        assertNotNull(backend);

        // Should select OpenCL or Metal (both are GPU backends)
        assertTrue(backend.isGPU() || backend == GPUBackend.CPU_FALLBACK);

        if (backend == GPUBackend.OPENCL) {
            assertEquals("OpenCL", backend.getDisplayName());
        }
    }

    // --- Large Data Tests ---

    @Test
    void testLargeVectorAdd() throws Exception {
        if (!openCLAvailable) return;

        int size = 65536; // 64K elements
        var a = new float[size];
        var b = new float[size];
        var result = new float[size];

        // Initialize with pattern
        for (int i = 0; i < size; i++) {
            a[i] = (float) Math.sin(i * 0.01);
            b[i] = (float) Math.cos(i * 0.01);
        }

        try (var bufferA = OpenCLBuffer.createWithData(a, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.createWithData(b, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("vectorAdd")) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, size);

            kernel.execute(size);
            kernel.finish();

            bufferResult.download(result);

            // Spot check results
            for (int i = 0; i < size; i += 1024) {
                float expected = a[i] + b[i];
                assertEquals(expected, result[i], 0.0001f,
                        "Mismatch at index " + i);
            }
        }
    }

    // --- Multiple Kernel Execution Tests ---

    @Test
    void testMultipleKernelExecutions() throws Exception {
        if (!openCLAvailable) return;

        int size = 256;
        var data = new float[size];

        // Initialize
        for (int i = 0; i < size; i++) {
            data[i] = i;
        }

        try (var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE);
             var tempBuffer = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.READ_WRITE);
             var kernel = OpenCLKernel.create("vectorAdd")) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            // Execute multiple times: data = data + data (doubling each time)
            for (int iter = 0; iter < 3; iter++) {
                // Copy buffer to temp
                var temp = new float[size];
                buffer.download(temp);
                tempBuffer.upload(temp);

                // result = buffer + temp (doubles the values)
                kernel.setBufferArg(0, buffer, ComputeKernel.BufferAccess.READ);
                kernel.setBufferArg(1, tempBuffer, ComputeKernel.BufferAccess.READ);
                kernel.setBufferArg(2, buffer, ComputeKernel.BufferAccess.WRITE);
                kernel.setIntArg(3, size);

                kernel.execute(size);
                kernel.finish();
            }

            var result = new float[size];
            buffer.download(result);

            // After 3 iterations of doubling: result = data * 2^3 = data * 8
            for (int i = 0; i < size; i++) {
                float expected = data[i] * 8;
                assertEquals(expected, result[i], 0.0001f,
                        "Mismatch at index " + i + " after multiple executions");
            }
        }
    }

    // 2D vector addition kernel with proper 2D indexing
    private static final String VECTOR_ADD_2D_KERNEL = """
            __kernel void vectorAdd2D(__global const float* a,
                                       __global const float* b,
                                       __global float* result,
                                       const int width,
                                       const int height) {
                int x = get_global_id(0);
                int y = get_global_id(1);
                if (x < width && y < height) {
                    int idx = y * width + x;
                    result[idx] = a[idx] + b[idx];
                }
            }
            """;

    // --- 2D Execution Tests ---

    @Test
    void test2DExecution() throws Exception {
        if (!openCLAvailable) return;

        int width = 64;
        int height = 64;
        int size = width * height;

        var a = new float[size];
        var b = new float[size];
        var result = new float[size];

        for (int i = 0; i < size; i++) {
            a[i] = i;
            b[i] = size - i;
        }

        try (var bufferA = OpenCLBuffer.createWithData(a, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.createWithData(b, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("vectorAdd2D")) {

            kernel.compile(VECTOR_ADD_2D_KERNEL, "vectorAdd2D");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, width);
            kernel.setIntArg(4, height);

            // Execute as 2D grid
            kernel.execute(width, height);
            kernel.finish();

            bufferResult.download(result);

            // Each element should be: a[i] + b[i] = i + (size - i) = size
            for (int i = 0; i < size; i++) {
                assertEquals(size, result[i], 0.0001f,
                        "2D execution mismatch at index " + i);
            }
        }
    }

    // --- Resource Cleanup Tests ---

    @Test
    void testResourcesReleasedOnClose() throws Exception {
        if (!openCLAvailable) return;

        // Create and immediately close resources
        var kernel = OpenCLKernel.create("test");
        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);

        kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

        assertTrue(kernel.isValid());
        assertTrue(buffer.isValid());

        kernel.close();
        buffer.close();

        assertFalse(kernel.isValid());
        assertFalse(buffer.isValid());
    }

    @Test
    void testTryWithResourcesCleanup() throws Exception {
        if (!openCLAvailable) return;

        OpenCLBuffer buffer;
        OpenCLKernel kernel;

        try (var b = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
             var k = OpenCLKernel.create("test")) {
            buffer = b;
            kernel = k;
            k.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            assertTrue(buffer.isValid());
            assertTrue(kernel.isValid());
        }

        // After try-with-resources, resources should be released
        assertFalse(buffer.isValid());
        assertFalse(kernel.isValid());
    }
}
