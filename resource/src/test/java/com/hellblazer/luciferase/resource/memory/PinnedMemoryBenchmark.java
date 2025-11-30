package com.hellblazer.luciferase.resource.memory;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for pinned memory transfers (ART-530 Phase 1).
 *
 * Validates performance improvements from pinned memory (CL_MEM_ALLOC_HOST_PTR)
 * over pageable memory for CPU-GPU transfers. Pinned memory enables Direct Memory
 * Access (DMA) for zero-copy transfers, providing 1.5-3.0x speedup.
 *
 * <p>Benchmarks:
 * <ol>
 *   <li>benchmarkPageableUpload - Baseline: standard memory upload to GPU</li>
 *   <li>benchmarkPinnedUpload - Pinned memory upload via DMA (1.5-3.0x faster)</li>
 *   <li>benchmarkPageableDownload - Baseline: standard memory download from GPU</li>
 *   <li>benchmarkPinnedDownload - Pinned memory download via DMA (1.5-3.0x faster)</li>
 *   <li>benchmarkPoolHitRate - Pinned buffer pooling efficiency (target: >50% hit rate)</li>
 * </ol>
 *
 * <p>Test Sizes: 1KB, 10KB, 100KB, 1MB, 10MB
 *
 * <p>Expected Results:
 * <ul>
 *   <li>Pinned upload: 1.5-3.0x faster than pageable</li>
 *   <li>Pinned download: 1.5-3.0x faster than pageable</li>
 *   <li>Pool hit rate: >50% (demonstrating pooling effectiveness)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Quick test (Fork 0, <2 min)
 * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
 *   -Dexec.classpathScope=test -Dexec.args="-f 0 -wi 1 -i 2"
 *
 * # Production (Fork 1, accurate GPU measurements)
 * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
 *   -Dexec.classpathScope=test
 *
 * # With profiling
 * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
 *   -Dexec.classpathScope=test -Dexec.args="-prof stack:lines=20;top=30"
 * </pre>
 *
 * @author ART-530 Phase 1
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)  // GPU benchmarks require forking for accurate measurements
@State(Scope.Thread)
public class PinnedMemoryBenchmark {
    private static final Logger log = LoggerFactory.getLogger(PinnedMemoryBenchmark.class);

    // Test sizes: 1KB, 10KB, 100KB, 1MB, 10MB
    @Param({"1024", "10240", "102400", "1048576", "10485760"})
    private int transferSize;

    // OpenCL context and queue
    private long context;
    private long device;
    private long commandQueue;
    private boolean openCLAvailable;

    // Memory pools (pinned and pageable)
    private MemoryPool pinnedPool;
    private MemoryPool pageablePool;

    // ResourceTracker for leak detection
    private ResourceTracker tracker;

    @Setup(Level.Trial)
    public void setupTrial() {
        tracker = ResourceTracker.getGlobalTracker();

        // Initialize OpenCL
        openCLAvailable = initializeOpenCL();
        if (!openCLAvailable) {
            log.warn("OpenCL not available, benchmarks will skip GPU operations");
            return;
        }

        // Create GPU-enabled memory pool for pinned allocations
        pinnedPool = new MemoryPool(
            MemoryPool.Config.builder()
                .minBufferSize(256)
                .maxBufferSize(100 * 1024 * 1024)  // 100MB max
                .maxPoolSize(100)
                .maxBuffersPerSize(10)
                .build(),
            tracker,
            context,
            commandQueue
        );

        // Create standard memory pool for pageable allocations (no GPU context)
        pageablePool = new MemoryPool(
            MemoryPool.Config.builder()
                .minBufferSize(256)
                .maxBufferSize(100 * 1024 * 1024)
                .maxPoolSize(100)
                .maxBuffersPerSize(10)
                .build(),
            tracker
        );

        log.info("Pinned Memory Benchmark setup complete");
        log.info("OpenCL available: {}", openCLAvailable);
        log.info("Transfer size: {} bytes", transferSize);
    }

    @TearDown(Level.Trial)
    public void teardownTrial() {
        // CRITICAL: Clean up GPU resources to prevent leaks
        if (pinnedPool != null) {
            try {
                log.info("Closing pinned pool - Final stats: {}", pinnedPool.getStatistics());
                pinnedPool.close();
            } catch (Exception e) {
                log.error("Error closing pinned pool", e);
            }
        }

        if (pageablePool != null) {
            try {
                log.info("Closing pageable pool - Final stats: {}", pageablePool.getStatistics());
                pageablePool.close();
            } catch (Exception e) {
                log.error("Error closing pageable pool", e);
            }
        }

        // Clean up OpenCL resources
        if (openCLAvailable) {
            if (commandQueue != 0) {
                CL10.clReleaseCommandQueue(commandQueue);
                log.info("Released OpenCL command queue");
            }
            if (context != 0) {
                CL10.clReleaseContext(context);
                log.info("Released OpenCL context");
            }
        }
    }

    /**
     * Benchmark 1: Pageable memory upload (baseline).
     *
     * Allocates standard memory and uploads to GPU using clEnqueueWriteBuffer.
     * This is the traditional approach with kernel-mode copy overhead.
     *
     * Expected: Baseline performance for comparison
     */
    @Benchmark
    public void benchmarkPageableUpload() {
        if (!openCLAvailable) {
            return;
        }

        // Allocate pageable buffer
        var hostBuffer = pageablePool.allocate(transferSize);

        try {
            // Fill with test data
            for (int i = 0; i < transferSize / 4; i++) {
                hostBuffer.putFloat(i * 4, (float) i);
            }
            hostBuffer.rewind();

            // Create GPU buffer
            try (var stack = MemoryStack.stackPush()) {
                var errcode = stack.mallocInt(1);
                var gpuBuffer = CL10.clCreateBuffer(
                    context,
                    CL10.CL_MEM_READ_ONLY,
                    transferSize,
                    errcode
                );

                if (errcode.get(0) != CL10.CL_SUCCESS) {
                    log.error("Failed to create GPU buffer: {}", errcode.get(0));
                    return;
                }

                try {
                    // Upload to GPU (kernel-mode copy)
                    var error = CL10.clEnqueueWriteBuffer(
                        commandQueue,
                        gpuBuffer,
                        true,  // blocking
                        0,
                        hostBuffer,
                        null,
                        null
                    );

                    if (error != CL10.CL_SUCCESS) {
                        log.error("Failed to upload buffer: {}", error);
                    }
                } finally {
                    CL10.clReleaseMemObject(gpuBuffer);
                }
            }
        } finally {
            pageablePool.returnToPool(hostBuffer);
        }
    }

    /**
     * Benchmark 2: Pinned memory upload (DMA-optimized).
     *
     * Allocates pinned memory (CL_MEM_ALLOC_HOST_PTR) and uploads via unmap.
     * Uses Direct Memory Access (DMA) for zero-copy transfer.
     *
     * Expected: 1.5-3.0x faster than pageable upload
     */
    @Benchmark
    public void benchmarkPinnedUpload() {
        if (!openCLAvailable) {
            return;
        }

        // Allocate pinned buffer (GPU-visible, DMA-capable)
        try (var pinned = pinnedPool.pinnedAllocate(
                transferSize,
                context,
                MemoryPool.AccessType.READ_ONLY)) {

            // Fill with test data
            var hostBuffer = pinned.getHostBuffer();
            for (int i = 0; i < transferSize / 4; i++) {
                hostBuffer.putFloat(i * 4, (float) i);
            }
            hostBuffer.rewind();

            // Upload to GPU via unmap (DMA transfer)
            pinned.enqueueUpload(commandQueue, null, null, null);

            // Wait for completion
            CL10.clFinish(commandQueue);
        }
    }

    /**
     * Benchmark 3: Pageable memory download (baseline).
     *
     * Downloads data from GPU to standard memory using clEnqueueReadBuffer.
     * This is the traditional approach with kernel-mode copy overhead.
     *
     * Expected: Baseline performance for comparison
     */
    @Benchmark
    public void benchmarkPageableDownload() {
        if (!openCLAvailable) {
            return;
        }

        // Allocate pageable buffer
        var hostBuffer = pageablePool.allocate(transferSize);

        try {
            // Create and fill GPU buffer
            try (var stack = MemoryStack.stackPush()) {
                var errcode = stack.mallocInt(1);
                var gpuBuffer = CL10.clCreateBuffer(
                    context,
                    CL10.CL_MEM_WRITE_ONLY,
                    transferSize,
                    errcode
                );

                if (errcode.get(0) != CL10.CL_SUCCESS) {
                    log.error("Failed to create GPU buffer: {}", errcode.get(0));
                    return;
                }

                try {
                    // Download from GPU (kernel-mode copy)
                    var error = CL10.clEnqueueReadBuffer(
                        commandQueue,
                        gpuBuffer,
                        true,  // blocking
                        0,
                        hostBuffer,
                        null,
                        null
                    );

                    if (error != CL10.CL_SUCCESS) {
                        log.error("Failed to download buffer: {}", error);
                    }
                } finally {
                    CL10.clReleaseMemObject(gpuBuffer);
                }
            }
        } finally {
            pageablePool.returnToPool(hostBuffer);
        }
    }

    /**
     * Benchmark 4: Pinned memory download (DMA-optimized).
     *
     * Downloads data from GPU to pinned memory via map operation.
     * Uses Direct Memory Access (DMA) for zero-copy transfer.
     *
     * Expected: 1.5-3.0x faster than pageable download
     */
    @Benchmark
    public void benchmarkPinnedDownload() {
        if (!openCLAvailable) {
            return;
        }

        // Allocate pinned buffer (GPU-visible, DMA-capable)
        try (var pinned = pinnedPool.pinnedAllocate(
                transferSize,
                context,
                MemoryPool.AccessType.WRITE_ONLY)) {

            // Download from GPU via map (DMA transfer)
            pinned.enqueueDownload(commandQueue, null, null);

            // Wait for completion
            CL10.clFinish(commandQueue);

            // Data is now available in pinned.getHostBuffer()
        }
    }

    /**
     * Benchmark 5: Pool hit rate measurement.
     *
     * Allocates and returns pinned buffers repeatedly to measure
     * pooling effectiveness. High hit rate (>50%) indicates successful
     * buffer reuse and reduced allocation overhead.
     *
     * Expected: >50% hit rate
     */
    @Benchmark
    public void benchmarkPoolHitRate() {
        if (!openCLAvailable) {
            return;
        }

        // Allocate and return buffer (should hit pool after first iteration)
        try (var pinned = pinnedPool.pinnedAllocate(
                transferSize,
                context,
                MemoryPool.AccessType.READ_WRITE)) {
            // Minimal work
            pinned.getSize();
        }
    }

    /**
     * Initialize OpenCL context and command queue.
     *
     * @return true if OpenCL is available, false otherwise
     */
    private boolean initializeOpenCL() {
        try {
            try (var stack = MemoryStack.stackPush()) {
                var numPlatforms = stack.mallocInt(1);
                var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

                if (errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0) {
                    // Get first platform
                    var platformBuffer = stack.mallocPointer(1);
                    CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
                    var platform = platformBuffer.get(0);

                    // Get first GPU device (fallback to CPU if no GPU)
                    var deviceBuffer = stack.mallocPointer(1);
                    errcode = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, deviceBuffer, (int[]) null);
                    if (errcode != CL10.CL_SUCCESS) {
                        log.warn("No GPU device found, trying CPU fallback");
                        errcode = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_CPU, deviceBuffer, (int[]) null);
                    }

                    if (errcode == CL10.CL_SUCCESS) {
                        device = deviceBuffer.get(0);

                        // Create context
                        var errcodeBuf = stack.mallocInt(1);
                        context = CL10.clCreateContext((PointerBuffer) null, device, null, 0, errcodeBuf);

                        if (errcodeBuf.get(0) != CL10.CL_SUCCESS) {
                            log.error("Failed to create OpenCL context: {}", errcodeBuf.get(0));
                            return false;
                        }

                        // Create command queue
                        commandQueue = CL10.clCreateCommandQueue(context, device, 0, errcodeBuf);

                        if (errcodeBuf.get(0) != CL10.CL_SUCCESS) {
                            log.error("Failed to create command queue: {}", errcodeBuf.get(0));
                            CL10.clReleaseContext(context);
                            return false;
                        }

                        log.info("OpenCL initialized successfully");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("OpenCL initialization failed", e);
        }

        return false;
    }

    /**
     * Main method for running benchmarks via exec-maven-plugin or standalone.
     *
     * <p>Quick test (Fork 0, <2 min):
     * <pre>
     * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
     *   -Dexec.classpathScope=test -Dexec.args="-f 0 -wi 1 -i 2"
     * </pre>
     *
     * <p>Production (Fork 1):
     * <pre>
     * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
     *   -Dexec.classpathScope=test
     * </pre>
     *
     * <p>With stack profiling:
     * <pre>
     * mvn exec:java -Dexec.mainClass="com.hellblazer.luciferase.resource.memory.PinnedMemoryBenchmark" \
     *   -Dexec.classpathScope=test -Dexec.args="-prof stack:lines=20;top=30"
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        var opts = new OptionsBuilder()
            .include(PinnedMemoryBenchmark.class.getSimpleName())
            .forks(1)
            .warmupIterations(3)
            .measurementIterations(5)
            .build();

        new Runner(opts).run();
    }
}
