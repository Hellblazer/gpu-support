package com.hellblazer.luciferase.resource.compute;

import com.hellblazer.luciferase.resource.compute.ComputeKernel.BufferAccess;
import com.hellblazer.luciferase.resource.compute.ComputeKernel.KernelCompilationException;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ComputeKernel build options support.
 * Tests runtime kernel compilation with preprocessor defines and compiler flags
 * for GPU auto-tuning capabilities.
 *
 * <p>Tests require OpenCL hardware - skipped in CI environments.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
    disabledReason = "OpenCL hardware not available in CI")
class ComputeKernelBuildOptionsTest {

    private static boolean openCLAvailable;

    // Kernel with preprocessor define
    private static final String KERNEL_WITH_DEFINE = """
            __kernel void add(__global float* a) {
                int gid = get_global_id(0);
                a[gid] += OFFSET;
            }
            """;

    // Kernel with feature flag
    private static final String KERNEL_WITH_FEATURE_FLAG = """
            __kernel void process(__global float* data) {
                int gid = get_global_id(0);
                #ifdef ENABLE_SHARED_MEMORY
                    __local float temp[256];
                    temp[gid % 256] = data[gid];
                    barrier(CLK_LOCAL_MEM_FENCE);
                    data[gid] = temp[gid % 256] * 2.0f;
                #else
                    data[gid] = data[gid] * 2.0f;
                #endif
            }
            """;

    // Kernel with runtime multiplier
    private static final String KERNEL_WITH_MULTIPLIER = """
            __kernel void multiply(__global float* data) {
                int gid = get_global_id(0);
                data[gid] *= MULTIPLIER;
            }
            """;

    // Kernel for math optimizations
    private static final String MATH_KERNEL = """
            __kernel void mathOps(__global float* x) {
                int gid = get_global_id(0);
                x[gid] = sqrt(x[gid]) * 2.0f + 1.0f;
            }
            """;

    // Simple kernel without defines
    private static final String SIMPLE_KERNEL = """
            __kernel void simple(__global float* x) {
                int gid = get_global_id(0);
                x[gid] = 42.0f;
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
            System.out.println("OpenCL available for build options tests: " + openCLAvailable);
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
        }
    }

    // --- Basic Build Options Tests ---

    @Test
    void testCompileWithDefine() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("addWithOffset")) {
            var buildOptions = "-DOFFSET=100";

            assertDoesNotThrow(() -> kernel.compile(KERNEL_WITH_DEFINE, "add", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testCompileWithMultipleDefines() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("multiDefine")) {
            var buildOptions = "-DOFFSET=100 -DBLOCK_SIZE=256";

            assertDoesNotThrow(() -> kernel.compile(KERNEL_WITH_DEFINE, "add", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testCompileWithFeatureFlag() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("withSharedMem")) {
            var buildOptions = "-DENABLE_SHARED_MEMORY=1";

            assertDoesNotThrow(() -> kernel.compile(KERNEL_WITH_FEATURE_FLAG, "process", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testCompileWithoutFeatureFlag() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("withoutSharedMem")) {
            // Compile without ENABLE_SHARED_MEMORY - should use #else branch
            var buildOptions = "";

            assertDoesNotThrow(() -> kernel.compile(KERNEL_WITH_FEATURE_FLAG, "process", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    // --- Compiler Flags Tests ---

    @Test
    void testCompileWithCompilerFlags() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("fastMath")) {
            var buildOptions = "-cl-fast-relaxed-math -cl-mad-enable";

            assertDoesNotThrow(() -> kernel.compile(MATH_KERNEL, "mathOps", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testCompileWithWarningsAsErrors() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("strictCompile")) {
            var buildOptions = "-Werror";

            // Simple kernel should compile without warnings
            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    // --- Recompilation Tests ---

    @Test
    void testRecompileWithDifferentOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("multiplier")) {
            // First compilation with MULTIPLIER=2
            var buildOptions1 = "-DMULTIPLIER=2";
            assertDoesNotThrow(() -> kernel.compile(KERNEL_WITH_MULTIPLIER, "multiply", buildOptions1));
            assertTrue(kernel.isCompiled());

            // Recompile with MULTIPLIER=10
            var buildOptions2 = "-DMULTIPLIER=10";
            assertDoesNotThrow(() -> kernel.recompile(KERNEL_WITH_MULTIPLIER, "multiply", buildOptions2));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testRecompileChangesDefineValue() throws Exception {
        if (!openCLAvailable) return;

        int size = 64;
        var data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = 1.0f;
        }

        try (var kernel = OpenCLKernel.create("multiplier");
             var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            // First compilation: multiply by 2
            kernel.compile(KERNEL_WITH_MULTIPLIER, "multiply", "-DMULTIPLIER=2");
            kernel.setBufferArg(0, buffer, BufferAccess.READ_WRITE);
            kernel.execute(size);
            kernel.finish();

            var result1 = new float[size];
            buffer.download(result1);
            assertEquals(2.0f, result1[0], 0.0001f, "First execution should multiply by 2");

            // Recompile: multiply by 5
            kernel.recompile(KERNEL_WITH_MULTIPLIER, "multiply", "-DMULTIPLIER=5");
            kernel.setBufferArg(0, buffer, BufferAccess.READ_WRITE);
            kernel.execute(size);
            kernel.finish();

            var result2 = new float[size];
            buffer.download(result2);
            assertEquals(10.0f, result2[0], 0.0001f, "Second execution should multiply by 5 (2*5=10)");
        }
    }

    // --- Edge Cases ---

    @Test
    void testEmptyBuildOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("simple")) {
            var buildOptions = "";

            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testNullBuildOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("simple")) {
            String buildOptions = null;

            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple", buildOptions));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testInvalidBuildOption() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("invalid")) {
            // Invalid option should be caught by OpenCL compiler
            var buildOptions = "-invalid-option-xyz";

            // May throw or may be ignored by OpenCL driver - both acceptable
            assertDoesNotThrow(() -> {
                try {
                    kernel.compile(SIMPLE_KERNEL, "simple", buildOptions);
                } catch (KernelCompilationException e) {
                    // Expected: some drivers reject invalid options
                }
            });
        }
    }

    @Test
    void testConflictingDefines() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("conflict")) {
            // Define same symbol twice - OpenCL should handle this
            var buildOptions = "-DVALUE=5 -DVALUE=10";

            // Behavior depends on OpenCL implementation
            assertDoesNotThrow(() -> {
                try {
                    kernel.compile(KERNEL_WITH_DEFINE, "add", buildOptions);
                } catch (KernelCompilationException e) {
                    // Some implementations may reject conflicting defines
                }
            });
        }
    }

    // --- Vendor-Specific Options Tests ---

    @Test
    void testNVIDIASpecificOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("nvidia")) {
            // NVIDIA-specific compute capability define
            var buildOptions = "-D__CUDA_ARCH__=700";

            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple", buildOptions));
        }
    }

    @Test
    void testAMDSpecificOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("amd")) {
            // AMD GCN architecture defines
            var buildOptions = "-D__GCN__ -D__GCN_REV__=2";

            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple", buildOptions));
        }
    }

    @Test
    void testIntelSpecificOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("intel")) {
            // Intel-specific optimization flags
            var buildOptions = "-cl-intel-greater-than-4GB-buffer-required";

            assertDoesNotThrow(() -> {
                try {
                    kernel.compile(SIMPLE_KERNEL, "simple", buildOptions);
                } catch (KernelCompilationException e) {
                    // Option may not be supported on all Intel devices
                }
            });
        }
    }

    // --- Backward Compatibility Tests ---

    @Test
    void testBackwardCompatibilityWithoutBuildOptions() {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("backward")) {
            // Existing compile() method without buildOptions should still work
            assertDoesNotThrow(() -> kernel.compile(SIMPLE_KERNEL, "simple"));
            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testOldAndNewMethodsBothWork() {
        if (!openCLAvailable) return;

        try (var kernel1 = OpenCLKernel.create("old");
             var kernel2 = OpenCLKernel.create("new")) {

            // Old method (no build options)
            assertDoesNotThrow(() -> kernel1.compile(SIMPLE_KERNEL, "simple"));

            // New method (with build options)
            assertDoesNotThrow(() -> kernel2.compile(SIMPLE_KERNEL, "simple", "-DTEST=1"));

            assertTrue(kernel1.isCompiled());
            assertTrue(kernel2.isCompiled());
        }
    }

    // --- Execution Tests with Build Options ---

    @Test
    void testExecuteKernelCompiledWithDefine() throws Exception {
        if (!openCLAvailable) return;

        int size = 64;
        var data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = 0.0f;
        }

        try (var kernel = OpenCLKernel.create("addOffset");
             var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            // Compile with OFFSET=100
            kernel.compile(KERNEL_WITH_DEFINE, "add", "-DOFFSET=100");
            kernel.setBufferArg(0, buffer, BufferAccess.READ_WRITE);
            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            buffer.download(result);

            // Each element should be incremented by OFFSET (100)
            for (int i = 0; i < size; i++) {
                assertEquals(100.0f, result[i], 0.0001f, "Element " + i + " should be 0 + 100");
            }
        }
    }

    @Test
    void testExecuteKernelWithFastMathOptimizations() throws Exception {
        if (!openCLAvailable) return;

        int size = 64;
        var data = new float[size];
        for (int i = 0; i < size; i++) {
            data[i] = 4.0f;
        }

        try (var kernel = OpenCLKernel.create("mathOps");
             var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            // Compile with fast math optimizations
            kernel.compile(MATH_KERNEL, "mathOps", "-cl-fast-relaxed-math");
            kernel.setBufferArg(0, buffer, BufferAccess.READ_WRITE);
            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            buffer.download(result);

            // sqrt(4.0) * 2.0 + 1.0 = 2.0 * 2.0 + 1.0 = 5.0
            for (int i = 0; i < size; i++) {
                assertEquals(5.0f, result[i], 0.01f, "Element " + i + " should be 5.0");
            }
        }
    }

    // --- Lifecycle Tests ---

    @Test
    void testRecompileDoesNotInvalidateOldKernel() throws Exception {
        if (!openCLAvailable) return;

        try (var kernel = OpenCLKernel.create("lifecycle")) {
            kernel.compile(SIMPLE_KERNEL, "simple", "-DTEST=1");
            assertTrue(kernel.isCompiled());

            kernel.recompile(SIMPLE_KERNEL, "simple", "-DTEST=2");
            assertTrue(kernel.isCompiled(), "Kernel should still be compiled after recompilation");
        }
    }

    @Test
    void testCloseAfterRecompile() throws Exception {
        if (!openCLAvailable) return;

        var kernel = OpenCLKernel.create("closeTest");
        kernel.compile(SIMPLE_KERNEL, "simple", "-DTEST=1");
        kernel.recompile(SIMPLE_KERNEL, "simple", "-DTEST=2");

        kernel.close();
        assertFalse(kernel.isValid());
    }

    // --- Default Implementation Tests (for future backends) ---

    @Test
    void testDefaultImplementationThrowsUnsupportedOperation() {
        // Mock kernel that doesn't override new methods
        var kernel = new MockUnsupportedKernel();

        assertThrows(UnsupportedOperationException.class,
            () -> kernel.compile("source", "entry", "-DTEST=1"));

        assertThrows(UnsupportedOperationException.class,
            () -> kernel.recompile("source", "entry", "-DTEST=1"));
    }

    // --- Mock Kernel for Default Implementation Tests ---

    private static class MockUnsupportedKernel implements ComputeKernel {
        @Override
        public void compile(String source, String entryPoint) {
            // Old method implemented
        }

        @Override
        public void setBufferArg(int index, GPUBuffer buffer, BufferAccess access) {}

        @Override
        public void setFloatArg(int index, float value) {}

        @Override
        public void setIntArg(int index, int value) {}

        @Override
        public void execute(int globalWorkSize) {}

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY) {}

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ) {}

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                            int localWorkSizeX, int localWorkSizeY, int localWorkSizeZ) {}

        @Override
        public void executeAsync(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                                 PointerBuffer waitEvents, PointerBuffer signalEvent) {}

        @Override
        public void finish() {}

        @Override
        public GPUBackend getBackend() {
            return GPUBackend.CPU_FALLBACK;
        }

        @Override
        public boolean isCompiled() {
            return false;
        }

        @Override
        public void close() {}

        // Uses default implementations for new methods
    }
}
