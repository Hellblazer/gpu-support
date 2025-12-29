package com.hellblazer.luciferase.resource.compute.opencl;

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
 * Unit tests for OpenCLKernel.
 * Tests require OpenCL availability - skipped if not available and in CI environments.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class OpenCLKernelTest {

    private static boolean openCLAvailable;

    // Simple vector addition kernel for testing
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

    // Simple scale kernel for testing scalar arguments
    private static final String SCALE_KERNEL = """
            __kernel void scale(__global float* data,
                               const float factor,
                               const int size) {
                int gid = get_global_id(0);
                if (gid < size) {
                    data[gid] = data[gid] * factor;
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
            System.out.println("OpenCL available: " + openCLAvailable);
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
        }
    }

    // --- Creation Tests ---

    @Test
    void testCreateKernel() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("testKernel")) {
            assertNotNull(kernel);
            assertEquals("testKernel", kernel.getName());
            assertFalse(kernel.isCompiled());
            assertTrue(kernel.isValid());
            assertEquals(GPUBackend.OPENCL, kernel.getBackend());
        }
    }

    // --- Compilation Tests ---

    @Test
    void testCompileKernel() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd")) {
            assertFalse(kernel.isCompiled());

            assertDoesNotThrow(() -> kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd"));

            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testDoubleCompileThrows() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd")) {
            assertDoesNotThrow(() -> kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd"));

            assertThrows(ComputeKernel.KernelCompilationException.class,
                    () -> kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd"));
        }
    }

    @Test
    void testCompileInvalidSourceThrows() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("invalid")) {
            var invalidSource = "__kernel void foo() { invalid_function(); }";

            assertThrows(ComputeKernel.KernelCompilationException.class,
                    () -> kernel.compile(invalidSource, "foo"));
        }
    }

    @Test
    void testCompileWrongEntryPointThrows() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd")) {
            // Compile with wrong entry point name
            assertThrows(ComputeKernel.KernelCompilationException.class,
                    () -> kernel.compile(VECTOR_ADD_KERNEL, "wrongName"));
        }
    }

    // --- Argument Setting Tests ---

    @Test
    void testSetBufferArg() throws Exception {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd");
             var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY)) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            assertDoesNotThrow(() ->
                    kernel.setBufferArg(0, buffer, ComputeKernel.BufferAccess.READ));
        }
    }

    @Test
    void testSetScalarArgs() throws Exception {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("scale")) {
            kernel.compile(SCALE_KERNEL, "scale");

            assertDoesNotThrow(() -> kernel.setFloatArg(1, 2.5f));
            assertDoesNotThrow(() -> kernel.setIntArg(2, 64));
        }
    }

    @Test
    void testSetArgBeforeCompileThrows() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("test");
             var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY)) {

            assertThrows(IllegalStateException.class,
                    () -> kernel.setBufferArg(0, buffer, ComputeKernel.BufferAccess.READ));
            assertThrows(IllegalStateException.class,
                    () -> kernel.setFloatArg(0, 1.0f));
            assertThrows(IllegalStateException.class,
                    () -> kernel.setIntArg(0, 1));
        }
    }

    // --- Execution Tests ---

    @Test
    void testExecuteVectorAdd() throws Exception {
        if (!openCLAvailable) return;

        int size = 64;
        var a = new float[size];
        var b = new float[size];
        var result = new float[size];

        // Initialize input data
        for (int i = 0; i < size; i++) {
            a[i] = i;
            b[i] = i * 2;
        }

        try (var kernel = OpenCLKernel.create("vectorAdd");
             var bufferA = OpenCLBuffer.createWithData(a, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.createWithData(b, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY)) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, size);

            kernel.execute(size);
            kernel.finish();

            bufferResult.download(result);

            // Verify results
            for (int i = 0; i < size; i++) {
                assertEquals(a[i] + b[i], result[i], 0.0001f,
                        "Mismatch at index " + i);
            }
        }
    }

    @Test
    void testExecuteScale() throws Exception {
        if (!openCLAvailable) return;

        int size = 64;
        var data = new float[size];
        float scale = 2.5f;

        // Initialize data
        for (int i = 0; i < size; i++) {
            data[i] = i;
        }

        try (var kernel = OpenCLKernel.create("scale");
             var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            kernel.compile(SCALE_KERNEL, "scale");

            kernel.setBufferArg(0, buffer, ComputeKernel.BufferAccess.READ_WRITE);
            kernel.setFloatArg(1, scale);
            kernel.setIntArg(2, size);

            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            buffer.download(result);

            // Verify results
            for (int i = 0; i < size; i++) {
                assertEquals(i * scale, result[i], 0.0001f,
                        "Mismatch at index " + i);
            }
        }
    }

    @Test
    void testExecuteBeforeCompileThrows() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("test")) {
            assertThrows(ComputeKernel.KernelExecutionException.class,
                    () -> kernel.execute(64));
        }
    }

    @Test
    void testExecute2D() throws Exception {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd");
             var bufferA = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.WRITE_ONLY)) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, 64);

            // Execute with 2D work size (8x8 = 64)
            assertDoesNotThrow(() -> kernel.execute(8, 8));
            kernel.finish();
        }
    }

    @Test
    void testExecute3D() throws Exception {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("vectorAdd");
             var bufferA = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.WRITE_ONLY)) {

            kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd");

            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, 64);

            // Execute with 3D work size (4x4x4 = 64)
            assertDoesNotThrow(() -> kernel.execute(4, 4, 4));
            kernel.finish();
        }
    }

    // --- Lifecycle Tests ---

    @Test
    void testCloseMarksInvalid() {
        if (!openCLAvailable) return;

        var kernel = OpenCLKernel.create("test");
        assertTrue(kernel.isValid());

        kernel.close();
        assertFalse(kernel.isValid());
    }

    @Test
    void testDoubleCloseIsSafe() {
        if (!openCLAvailable) return;

        var kernel = OpenCLKernel.create("test");
        kernel.close();
        kernel.close(); // Should not throw
        assertFalse(kernel.isValid());
    }

    @Test
    void testOperationsAfterCloseThrow() throws Exception {
        if (!openCLAvailable) return;

        var kernel = OpenCLKernel.create("test");
        kernel.close();

        assertThrows(IllegalStateException.class,
                () -> kernel.compile(VECTOR_ADD_KERNEL, "vectorAdd"));
        assertThrows(IllegalStateException.class,
                () -> kernel.execute(64));
        assertThrows(IllegalStateException.class,
                kernel::finish);
    }
}
