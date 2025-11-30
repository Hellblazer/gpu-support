package com.hellblazer.luciferase.resource.opencl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for CLBufferHandle pool allocation latency.
 *
 * Target: <1ms pool allocation latency
 *
 * Usage:
 * mvn test -Dtest=CLBufferHandleBenchmark -Dexec.classpathScope=test
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(0) // Run in same JVM for development (use @Fork(1) for production benchmarks)
@State(Scope.Thread)
public class CLBufferHandleBenchmark {

    private long context;
    private long commandQueue;
    private long device;
    private boolean openCLAvailable;

    @Setup(Level.Trial)
    public void setupOpenCL() {
        try {
            // Try to initialize OpenCL
            try (var stack = MemoryStack.stackPush()) {
                var numPlatforms = stack.mallocInt(1);
                var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

                if (errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0) {
                    // Get first platform
                    var platformBuffer = stack.mallocPointer(1);
                    CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
                    var platform = platformBuffer.get(0);

                    // Get first device
                    var deviceBuffer = stack.mallocPointer(1);
                    CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, deviceBuffer, (int[]) null);
                    device = deviceBuffer.get(0);

                    // Create context
                    var errcodeBuf = stack.mallocInt(1);
                    context = CL10.clCreateContext((PointerBuffer) null, device, null, 0, errcodeBuf);

                    // Create command queue
                    commandQueue = CL10.clCreateCommandQueue(context, device, 0, errcodeBuf);

                    openCLAvailable = true;
                    System.out.println("OpenCL initialized for benchmarking");
                } else {
                    openCLAvailable = false;
                    System.out.println("OpenCL not available, benchmark will be skipped");
                }
            }
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL initialization failed: " + e.getMessage());
        }
    }

    @TearDown(Level.Trial)
    public void cleanupOpenCL() {
        if (openCLAvailable) {
            if (commandQueue != 0) {
                CL10.clReleaseCommandQueue(commandQueue);
            }
            if (context != 0) {
                CL10.clReleaseContext(context);
            }
        }
    }

    @Benchmark
    public void benchmarkColdAllocation() {
        if (!openCLAvailable) {
            return;
        }

        // Cold allocation (no pool callback)
        try (var buffer = CLBufferHandle.create(context, 4096,
                CLBufferHandle.BufferType.READ_WRITE)) {
            // Minimal work
            buffer.getSize();
        }
    }

    @Benchmark
    public void benchmarkPoolAllocation() {
        if (!openCLAvailable) {
            return;
        }

        // Pool allocation (with callback)
        try (var buffer = CLBufferHandle.create(context, 4096,
                CLBufferHandle.BufferType.READ_WRITE,
                () -> {
                    // Simulate pool return
                    CLBufferHandle.recordReuse();
                })) {
            // Minimal work
            buffer.getSize();
        }
    }

    @Benchmark
    public void benchmarkLargeBufferAllocation() {
        if (!openCLAvailable) {
            return;
        }

        // Large buffer (120MB) with pool callback
        var largeSize = 120L * 1024 * 1024;
        try (var buffer = CLBufferHandle.create(context, largeSize,
                CLBufferHandle.BufferType.READ_WRITE,
                () -> {
                    // Simulate pool return
                })) {
            // Minimal work
            buffer.getSize();
        }
    }

    public static void main(String[] args) throws Exception {
        // Run benchmark manually (for development)
        var benchmark = new CLBufferHandleBenchmark();
        benchmark.setupOpenCL();

        if (benchmark.openCLAvailable) {
            // Warmup
            for (int i = 0; i < 100; i++) {
                benchmark.benchmarkPoolAllocation();
            }

            // Measure
            var iterations = 1000;
            var start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                benchmark.benchmarkPoolAllocation();
            }
            var duration = System.nanoTime() - start;
            var avgMs = (duration / iterations) / 1_000_000.0;

            System.out.printf("Pool allocation latency: %.3f ms%n", avgMs);
            System.out.printf("Target: <1.0 ms%n");
            System.out.printf("Result: %s%n", avgMs < 1.0 ? "PASS" : "FAIL");
        } else {
            System.out.println("OpenCL not available - benchmark skipped");
        }

        benchmark.cleanupOpenCL();
    }
}
