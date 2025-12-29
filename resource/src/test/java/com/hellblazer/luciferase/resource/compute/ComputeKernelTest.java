package com.hellblazer.luciferase.resource.compute;

import com.hellblazer.luciferase.resource.compute.ComputeKernel.BufferAccess;
import com.hellblazer.luciferase.resource.compute.ComputeKernel.KernelCompilationException;
import com.hellblazer.luciferase.resource.compute.ComputeKernel.KernelExecutionException;
import org.junit.jupiter.api.Test;
import org.lwjgl.PointerBuffer;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ComputeKernel interface contract.
 */
class ComputeKernelTest {

    /**
     * Mock implementation for testing interface contracts.
     */
    static class MockComputeKernel implements ComputeKernel {
        private boolean compiled = false;
        private boolean closed = false;
        private int executeCount = 0;

        @Override
        public void compile(String source, String entryPoint) throws KernelCompilationException {
            if (source == null || source.isEmpty()) {
                throw new KernelCompilationException("Source cannot be null or empty");
            }
            compiled = true;
        }

        @Override
        public void setBufferArg(int index, GPUBuffer buffer, BufferAccess access) {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
            if (buffer == null) throw new IllegalArgumentException("Buffer cannot be null");
        }

        @Override
        public void setFloatArg(int index, float value) {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        }

        @Override
        public void setIntArg(int index, int value) {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            if (index < 0) throw new IllegalArgumentException("Index must be non-negative");
        }

        @Override
        public void execute(int globalWorkSize) throws KernelExecutionException {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            executeCount++;
        }

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY) throws KernelExecutionException {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            executeCount++;
        }

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ)
                throws KernelExecutionException {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            executeCount++;
        }

        @Override
        public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                            int localWorkSizeX, int localWorkSizeY, int localWorkSizeZ)
                throws KernelExecutionException {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            executeCount++;
        }

        @Override
        public void executeAsync(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                                 PointerBuffer waitEvents, PointerBuffer signalEvent)
                throws KernelExecutionException {
            if (!compiled) throw new IllegalStateException("Kernel not compiled");
            executeCount++;
        }

        @Override
        public void finish() {
            // No-op for mock
        }

        @Override
        public GPUBackend getBackend() {
            return GPUBackend.CPU_FALLBACK;
        }

        @Override
        public boolean isCompiled() {
            return compiled;
        }

        @Override
        public void close() {
            closed = true;
            compiled = false;
        }

        public int getExecuteCount() {
            return executeCount;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    @Test
    void testKernelCompilation() throws KernelCompilationException {
        try (var kernel = new MockComputeKernel()) {
            assertFalse(kernel.isCompiled());

            kernel.compile("__kernel void test() {}", "test");

            assertTrue(kernel.isCompiled());
        }
    }

    @Test
    void testCompilationWithEmptySourceThrows() {
        try (var kernel = new MockComputeKernel()) {
            assertThrows(KernelCompilationException.class, () -> kernel.compile("", "test"));
        }
    }

    @Test
    void testCompilationWithNullSourceThrows() {
        try (var kernel = new MockComputeKernel()) {
            assertThrows(KernelCompilationException.class, () -> kernel.compile(null, "test"));
        }
    }

    @Test
    void testSetArgumentsBeforeCompileThrows() {
        try (var kernel = new MockComputeKernel()) {
            assertThrows(IllegalStateException.class, () -> kernel.setFloatArg(0, 1.0f));
            assertThrows(IllegalStateException.class, () -> kernel.setIntArg(0, 1));
        }
    }

    @Test
    void testExecuteBeforeCompileThrows() {
        try (var kernel = new MockComputeKernel()) {
            assertThrows(IllegalStateException.class, () -> kernel.execute(1024));
        }
    }

    @Test
    void testExecuteVariants() throws KernelCompilationException, KernelExecutionException {
        try (var kernel = new MockComputeKernel()) {
            kernel.compile("kernel", "main");

            kernel.execute(1024);
            kernel.execute(32, 32);
            kernel.execute(8, 8, 8);
            kernel.execute(8, 8, 8, 4, 4, 4);

            assertEquals(4, kernel.getExecuteCount());
        }
    }

    @Test
    void testBufferAccessEnum() {
        assertEquals(3, BufferAccess.values().length);
        assertNotNull(BufferAccess.READ);
        assertNotNull(BufferAccess.WRITE);
        assertNotNull(BufferAccess.READ_WRITE);
    }

    @Test
    void testExceptionConstruction() {
        var compileEx = new KernelCompilationException("test");
        assertEquals("test", compileEx.getMessage());

        var cause = new RuntimeException("cause");
        var compileExWithCause = new KernelCompilationException("test", cause);
        assertEquals(cause, compileExWithCause.getCause());

        var execEx = new KernelExecutionException("test");
        assertEquals("test", execEx.getMessage());

        var execExWithCause = new KernelExecutionException("test", cause);
        assertEquals(cause, execExWithCause.getCause());
    }

    @Test
    void testCloseInvalidatesKernel() throws KernelCompilationException {
        var kernel = new MockComputeKernel();
        kernel.compile("kernel", "main");
        assertTrue(kernel.isCompiled());

        kernel.close();
        assertFalse(kernel.isCompiled());
        assertTrue(kernel.isClosed());
    }

    @Test
    void testAutoCloseable() throws KernelCompilationException {
        MockComputeKernel kernel;
        try (var k = new MockComputeKernel()) {
            kernel = k;
            kernel.compile("kernel", "main");
            assertTrue(kernel.isCompiled());
        }
        assertTrue(kernel.isClosed());
    }

    @Test
    void testGetBackend() {
        try (var kernel = new MockComputeKernel()) {
            assertEquals(GPUBackend.CPU_FALLBACK, kernel.getBackend());
        }
    }

    @Test
    void testNegativeIndexThrows() throws KernelCompilationException {
        try (var kernel = new MockComputeKernel()) {
            kernel.compile("kernel", "main");
            assertThrows(IllegalArgumentException.class, () -> kernel.setFloatArg(-1, 1.0f));
            assertThrows(IllegalArgumentException.class, () -> kernel.setIntArg(-1, 1));
        }
    }
}
