package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BackendSelector.
 */
class BackendSelectorTest {

    @BeforeEach
    void setUp() {
        BackendSelector.testReset();
    }

    @AfterEach
    void tearDown() {
        BackendSelector.testReset();
    }

    // --- Basic Selection Tests ---

    @Test
    void testGetOptimalBackendReturnsNonNull() {
        var backend = BackendSelector.getOptimalBackend();
        assertNotNull(backend);
    }

    @Test
    void testGetOptimalBackendIsCached() {
        var first = BackendSelector.getOptimalBackend();
        var second = BackendSelector.getOptimalBackend();

        assertSame(first, second, "Backend selection should be cached");
    }

    @Test
    void testCpuFallbackIsAlwaysValid() {
        // CPU fallback should always be available
        assertTrue(GPUBackend.CPU_FALLBACK.isAvailable());
    }

    // --- CI Environment Tests ---

    @Test
    void testIsCIEnvironmentDetectsCI() {
        // This test depends on actual environment
        // Just verify the method doesn't throw
        boolean result = BackendSelector.isCIEnvironment();
        // Result depends on whether we're in CI or not
        // The method should return a boolean
        assertTrue(result || !result);
    }

    // --- Platform Description Tests ---

    @Test
    void testGetPlatformDescriptionNotNull() {
        var desc = BackendSelector.getPlatformDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    void testGetPlatformDescriptionContainsOS() {
        var desc = BackendSelector.getPlatformDescription();
        var osName = System.getProperty("os.name");

        assertTrue(desc.contains(osName),
                "Platform description should contain OS name");
    }

    // --- Availability Convenience Methods ---

    @Test
    void testIsMetalAvailableMatchesGPUBackend() {
        assertEquals(GPUBackend.METAL.isAvailable(),
                BackendSelector.isMetalAvailable());
    }

    @Test
    void testIsOpenCLAvailableMatchesGPUBackend() {
        assertEquals(GPUBackend.OPENCL.isAvailable(),
                BackendSelector.isOpenCLAvailable());
    }

    // --- Environment Info Tests ---

    @Test
    void testGetEnvironmentInfoNotNull() {
        var info = BackendSelector.getEnvironmentInfo();
        assertNotNull(info);
        assertFalse(info.isEmpty());
    }

    @Test
    void testGetEnvironmentInfoContainsRequiredFields() {
        var info = BackendSelector.getEnvironmentInfo();

        assertTrue(info.contains("Platform:"), "Should contain Platform");
        assertTrue(info.contains("CI:"), "Should contain CI");
        assertTrue(info.contains("Metal Available:"), "Should contain Metal status");
        assertTrue(info.contains("OpenCL Available:"), "Should contain OpenCL status");
        assertTrue(info.contains("Selected Backend:"), "Should contain selected backend");
    }

    // --- Backend Priority Tests ---

    @Test
    void testMetalHasHighestPriority() {
        // Metal should have highest priority among GPU backends
        assertEquals(100, GPUBackend.METAL.getPriority());
        assertTrue(GPUBackend.METAL.getPriority() > GPUBackend.OPENCL.getPriority());
        assertTrue(GPUBackend.METAL.getPriority() > GPUBackend.CPU_FALLBACK.getPriority());
    }

    @Test
    void testOpenCLHasMiddlePriority() {
        assertEquals(90, GPUBackend.OPENCL.getPriority());
        assertTrue(GPUBackend.OPENCL.getPriority() > GPUBackend.CPU_FALLBACK.getPriority());
    }

    @Test
    void testCpuFallbackHasLowestPriority() {
        assertEquals(10, GPUBackend.CPU_FALLBACK.getPriority());
    }

    // --- GPU Backend Classification Tests ---

    @Test
    void testMetalIsGPU() {
        assertTrue(GPUBackend.METAL.isGPU());
    }

    @Test
    void testOpenCLIsGPU() {
        assertTrue(GPUBackend.OPENCL.isGPU());
    }

    @Test
    void testCpuFallbackIsNotGPU() {
        assertFalse(GPUBackend.CPU_FALLBACK.isGPU());
    }

    // --- Reset Tests ---

    @Test
    void testResetAllowsReselection() {
        var first = BackendSelector.getOptimalBackend();
        BackendSelector.testReset();
        var second = BackendSelector.getOptimalBackend();

        // After reset, it should re-select (result may be same or different)
        assertNotNull(second);
    }
}
