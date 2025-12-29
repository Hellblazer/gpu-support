package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GPUBackend enum.
 */
class GPUBackendTest {

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
}
