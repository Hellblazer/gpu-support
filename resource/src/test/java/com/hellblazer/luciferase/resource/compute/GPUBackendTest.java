package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GPUBackend enum.
 */
class GPUBackendTest {

    @BeforeEach
    void setUp() {
        GPUBackend.testResetAvailability();
    }

    @AfterEach
    void tearDown() {
        GPUBackend.testResetAvailability();
    }

    @Test
    void testEnumValues() {
        assertEquals(3, GPUBackend.values().length);
        assertNotNull(GPUBackend.METAL);
        assertNotNull(GPUBackend.OPENCL);
        assertNotNull(GPUBackend.CPU_FALLBACK);
    }

    @Test
    void testDisplayNames() {
        assertEquals("Metal", GPUBackend.METAL.getDisplayName());
        assertEquals("OpenCL", GPUBackend.OPENCL.getDisplayName());
        assertEquals("CPU Fallback", GPUBackend.CPU_FALLBACK.getDisplayName());
    }

    @Test
    void testPriorities() {
        // Metal has highest priority
        assertTrue(GPUBackend.METAL.getPriority() > GPUBackend.OPENCL.getPriority());
        // OpenCL higher than CPU fallback
        assertTrue(GPUBackend.OPENCL.getPriority() > GPUBackend.CPU_FALLBACK.getPriority());
        // CPU fallback has lowest priority
        assertEquals(10, GPUBackend.CPU_FALLBACK.getPriority());
    }

    @Test
    void testIsGPU() {
        assertTrue(GPUBackend.METAL.isGPU());
        assertTrue(GPUBackend.OPENCL.isGPU());
        assertFalse(GPUBackend.CPU_FALLBACK.isGPU());
    }

    @Test
    void testValueOf() {
        assertEquals(GPUBackend.METAL, GPUBackend.valueOf("METAL"));
        assertEquals(GPUBackend.OPENCL, GPUBackend.valueOf("OPENCL"));
        assertEquals(GPUBackend.CPU_FALLBACK, GPUBackend.valueOf("CPU_FALLBACK"));
    }

    // --- Availability Tests ---

    @Test
    void testCPUFallbackAlwaysAvailable() {
        assertTrue(GPUBackend.CPU_FALLBACK.isAvailable(),
                "CPU_FALLBACK should always be available");
    }

    @Test
    void testMetalAvailabilityMatchesPlatform() {
        var isMacOS = System.getProperty("os.name", "").toLowerCase().contains("mac");
        assertEquals(isMacOS, GPUBackend.METAL.isAvailable(),
                "Metal availability should match macOS platform");
    }

    @Test
    void testOpenCLAvailabilityIsCached() {
        // First call triggers detection
        boolean first = GPUBackend.OPENCL.isAvailable();
        // Second call should return cached value
        boolean second = GPUBackend.OPENCL.isAvailable();
        assertEquals(first, second, "Cached availability should be consistent");
    }

    @Test
    void testMetalAvailabilityIsCached() {
        // First call triggers detection
        boolean first = GPUBackend.METAL.isAvailable();
        // Second call should return cached value
        boolean second = GPUBackend.METAL.isAvailable();
        assertEquals(first, second, "Cached availability should be consistent");
    }

    @Test
    void testTestResetAvailabilityClearsCache() {
        // Get initial availability
        GPUBackend.CPU_FALLBACK.isAvailable();
        GPUBackend.METAL.isAvailable();
        GPUBackend.OPENCL.isAvailable();

        // Reset should not throw
        GPUBackend.testResetAvailability();

        // Should still work after reset
        assertTrue(GPUBackend.CPU_FALLBACK.isAvailable());
    }

    @Test
    void testAllBackendsHaveConsistentAvailability() {
        // All backends should return consistent values on repeated calls
        for (var backend : GPUBackend.values()) {
            boolean first = backend.isAvailable();
            boolean second = backend.isAvailable();
            boolean third = backend.isAvailable();
            assertEquals(first, second);
            assertEquals(second, third);
        }
    }
}
