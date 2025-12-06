package com.hellblazer.luciferase.resource.memory;

import com.hellblazer.luciferase.resource.ResourceTracker;
import com.hellblazer.luciferase.resource.memory.MemoryPool.AccessType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opencl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.lwjgl.opencl.CL10.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/**
 * TDD tests for pinned memory functionality (ART-530 Phase 1).
 *
 * Test Strategy:
 * - Test 1: Verify pinned buffer allocation (basic infrastructure)
 * - Test 2: Measure DMA upload bandwidth (>8 GB/s target)
 * - Test 3: Verify pinned buffer pooling and reuse (>50% hit rate)
 */
@Tag("gpu")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class PinnedMemoryTest {

    private ResourceTracker tracker;
    private long platform;
    private long device;
    private long context;
    private long queue;
    private MemoryPool pool;

    @BeforeEach
    void setUp() {
        tracker = new ResourceTracker();

        // Initialize OpenCL
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer numPlatforms = stack.mallocInt(1);
            clGetPlatformIDs(null, numPlatforms);

            if (numPlatforms.get(0) == 0) {
                throw new RuntimeException("No OpenCL platforms found");
            }

            var platformBuffer = stack.mallocPointer(numPlatforms.get(0));
            clGetPlatformIDs(platformBuffer, (IntBuffer) null);
            platform = platformBuffer.get(0);

            IntBuffer numDevices = stack.mallocInt(1);
            long deviceType = CL_DEVICE_TYPE_GPU;
            clGetDeviceIDs(platform, deviceType, null, numDevices);

            if (numDevices.get(0) == 0) {
                // Fallback to CPU if GPU not available
                deviceType = CL_DEVICE_TYPE_CPU;
                clGetDeviceIDs(platform, deviceType, null, numDevices);
            }

            // Skip test if no OpenCL devices available
            assumeTrue(numDevices.get(0) > 0, "No OpenCL devices (GPU or CPU) available - skipping test");

            var deviceBuffer = stack.mallocPointer(numDevices.get(0));
            clGetDeviceIDs(platform, deviceType, deviceBuffer, (IntBuffer) null);
            device = deviceBuffer.get(0);

            IntBuffer errcode = stack.mallocInt(1);
            context = clCreateContext(null, device, null, MemoryUtil.NULL, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create OpenCL context: " + errcode.get(0));
            }

            queue = clCreateCommandQueue(context, device, 0, errcode);
            if (errcode.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create command queue: " + errcode.get(0));
            }
        }

        // Create GPU-enabled MemoryPool
        var config = MemoryPool.Config.builder()
            .maxBufferSize(100 * 1024 * 1024) // 100MB max
            .build();

        pool = new MemoryPool(config, tracker, context, queue);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (queue != 0) {
            clReleaseCommandQueue(queue);
        }
        if (context != 0) {
            clReleaseContext(context);
        }

        // Verify no leaks
        var activeCount = tracker.getActiveCount();
        assertEquals(0, activeCount,
            "Memory leaks detected: " + activeCount + " resources still active");
    }

    /**
     * Test 1: Pinned Buffer Allocation
     *
     * Verifies that pinnedAllocate():
     * - Creates a valid PinnedBuffer
     * - Returns non-null host buffer (ByteBuffer)
     * - Returns non-null GPU buffer (CLBufferHandle)
     * - Allocates correct size
     * - Properly implements AutoCloseable
     */
    @Test
    void testPinnedBufferAllocation() {
        var size = 1024;

        try (var buffer = pool.pinnedAllocate(size, context, AccessType.WRITE_ONLY)) {
            assertNotNull(buffer, "PinnedBuffer should not be null");
            assertNotNull(buffer.getHostBuffer(), "Host buffer should not be null");
            assertNotNull(buffer.getGPUBuffer(), "GPU buffer should not be null");
            assertEquals(size, buffer.getSize(), "Buffer size should match requested size");
        }

        // Verify ResourceTracker shows zero active resources after close
        var activeCount = tracker.getActiveCount();
        assertEquals(0, activeCount,
            "All resources should be released after close()");
    }

    /**
     * Test 2: DMA Upload Bandwidth
     *
     * Verifies that pinned memory achieves >8 GB/s DMA bandwidth.
     * This test:
     * - Allocates a 10MB pinned buffer
     * - Fills host buffer with data
     * - Measures upload time to GPU
     * - Calculates bandwidth in GB/s
     *
     * Target: >8 GB/s (typical 10-15 GB/s for PCIe 3.0)
     */
    @Test
    void testDMAUploadBandwidth() {
        var sizeBytes = 10 * 1024 * 1024;  // 10MB
        var floatCount = sizeBytes / Float.BYTES;
        var data = new float[floatCount];

        // Fill with test data
        for (int i = 0; i < floatCount; i++) {
            data[i] = (float) i / floatCount;
        }

        try (var pinnedBuffer = pool.pinnedAllocate(sizeBytes, context, AccessType.WRITE_ONLY)) {
            // Copy data to host buffer
            pinnedBuffer.getHostBuffer().asFloatBuffer().put(data);
            pinnedBuffer.getHostBuffer().rewind();

            // Measure upload time
            var start = System.nanoTime();
            pinnedBuffer.enqueueUpload(queue, null, null, null);
            clFinish(queue);
            var end = System.nanoTime();

            var durationSeconds = (end - start) / 1e9;
            var bandwidthGBps = (sizeBytes / 1e9) / durationSeconds;

            System.out.printf("DMA Upload: %.2f MB in %.3f ms = %.2f GB/s%n",
                sizeBytes / 1e6, durationSeconds * 1000, bandwidthGBps);

            assertTrue(bandwidthGBps > 8.0,
                String.format("DMA bandwidth should be >8 GB/s, got %.2f GB/s", bandwidthGBps));
        }
    }

    /**
     * Test 3: Pinned Buffer Pooling/Reuse
     *
     * Verifies that pinnedAllocate() reuses buffers from pool.
     * This test:
     * - Allocates and releases a pinned buffer (miss)
     * - Allocates same size again (should be hit)
     * - Verifies pool statistics show reuse
     *
     * Target: >50% hit rate after warmup
     */
    @Test
    void testPinnedBufferReuse() {
        var size = 4096;

        // First allocation (miss)
        try (var buffer1 = pool.pinnedAllocate(size, context, AccessType.READ_WRITE)) {
            assertNotNull(buffer1);
        }

        // Second allocation (should be hit if pooling works)
        var statsBefore = pool.getPoolStatistics();
        try (var buffer2 = pool.pinnedAllocate(size, context, AccessType.READ_WRITE)) {
            assertNotNull(buffer2);
        }
        var statsAfter = pool.getPoolStatistics();

        // Verify pool hit occurred
        assertTrue(statsAfter.poolHits > statsBefore.poolHits,
            "Second allocation should reuse pinned buffer from pool");

        // Multiple allocations to test hit rate
        for (int i = 0; i < 10; i++) {
            try (var buffer = pool.pinnedAllocate(size, context, AccessType.READ_WRITE)) {
                assertNotNull(buffer);
            }
        }

        var finalStats = pool.getPoolStatistics();
        var hitRate = finalStats.getHitRate();

        System.out.printf("Pinned buffer pool hit rate: %.1f%% (%d hits / %d allocations)%n",
            hitRate * 100, finalStats.poolHits, finalStats.totalAllocations);

        assertTrue(hitRate >= 0.50f,
            String.format("Pool hit rate should be >=50%%, got %.1f%%", hitRate * 100));
    }
}
