package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KernelLoader.
 */
class KernelLoaderTest {

    @BeforeEach
    void setUp() {
        KernelLoader.testClearCache();
    }

    @AfterEach
    void tearDown() {
        KernelLoader.testClearCache();
    }

    // --- Basic Loading Tests ---

    @Test
    void testLoadKernelFromPath() {
        // Load test kernel from test resources
        var source = KernelLoader.loadKernel("kernels/test_kernel.cl");

        assertNotNull(source);
        assertFalse(source.isEmpty());
        assertTrue(source.contains("__kernel"));
    }

    @Test
    void testLoadTestKernel() {
        // Uses convention: kernels/{name}.cl
        var source = KernelLoader.loadTestKernel("test_kernel");

        assertNotNull(source);
        assertTrue(source.contains("__kernel"));
    }

    @Test
    void testLoadNonExistentKernelThrows() {
        assertThrows(KernelLoader.KernelLoadException.class,
                () -> KernelLoader.loadKernel("kernels/does_not_exist.cl"));
    }

    @Test
    void testLoadOpenCLKernelThrowsIfNotFound() {
        // No kernel at kernels/opencl/ path in test resources
        assertThrows(KernelLoader.KernelLoadException.class,
                () -> KernelLoader.loadOpenCLKernel("nonexistent"));
    }

    // --- Caching Tests ---

    @Test
    void testCaching() {
        assertEquals(0, KernelLoader.getCacheSize());

        KernelLoader.loadKernel("kernels/test_kernel.cl");
        assertEquals(1, KernelLoader.getCacheSize());

        // Load again - should use cache
        KernelLoader.loadKernel("kernels/test_kernel.cl");
        assertEquals(1, KernelLoader.getCacheSize());
    }

    @Test
    void testCacheClear() {
        KernelLoader.loadKernel("kernels/test_kernel.cl");
        assertEquals(1, KernelLoader.getCacheSize());

        KernelLoader.testClearCache();
        assertEquals(0, KernelLoader.getCacheSize());
    }

    @Test
    void testCachedContentSame() {
        var first = KernelLoader.loadKernel("kernels/test_kernel.cl");
        var second = KernelLoader.loadKernel("kernels/test_kernel.cl");

        assertSame(first, second, "Cached content should be same object");
    }

    // --- Kernel Exists Tests ---

    @Test
    void testKernelExistsTrue() {
        assertTrue(KernelLoader.kernelExists("kernels/test_kernel.cl"));
    }

    @Test
    void testKernelExistsFalse() {
        assertFalse(KernelLoader.kernelExists("kernels/nonexistent.cl"));
    }

    @Test
    void testKernelExistsUsesCache() {
        // Pre-load into cache
        KernelLoader.loadKernel("kernels/test_kernel.cl");

        // kernelExists should find it in cache
        assertTrue(KernelLoader.kernelExists("kernels/test_kernel.cl"));
    }

    // --- Content Validation Tests ---

    @Test
    void testLoadedContentIsValid() {
        var source = KernelLoader.loadKernel("kernels/test_kernel.cl");

        // Should be valid OpenCL source
        assertTrue(source.contains("__kernel"));
        assertTrue(source.contains("void"));
    }

    @Test
    void testLoadedContentPreservesNewlines() {
        var source = KernelLoader.loadKernel("kernels/test_kernel.cl");

        // Multi-line kernel should have newlines
        assertTrue(source.contains("\n"));
    }
}
