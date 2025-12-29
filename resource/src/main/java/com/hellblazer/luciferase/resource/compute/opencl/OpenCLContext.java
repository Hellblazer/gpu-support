package com.hellblazer.luciferase.resource.compute.opencl;

import org.lwjgl.PointerBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Singleton OpenCL context manager.
 *
 * <p>Ensures only ONE OpenCL context and command queue exist per JVM,
 * preventing resource leaks and state corruption.
 *
 * <p>The command queue is created with CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE flag
 * when supported, which allows GPU drivers to automatically schedule kernels across
 * all GPU cores. This enables better hardware utilization without sacrificing
 * correctness, as OpenCL event dependencies ensure proper kernel ordering where needed.
 *
 * <p>Usage:
 * <pre>{@code
 * var ctx = OpenCLContext.getInstance();
 * ctx.acquire();
 * try {
 *     long context = ctx.getContext();
 *     long commandQueue = ctx.getCommandQueue();
 *     // Use OpenCL resources...
 * } finally {
 *     ctx.release();
 * }
 * }</pre>
 *
 * <h3>Test Isolation</h3>
 * <p>For test isolation, use {@link #testReset()} which resets internal state
 * without releasing OpenCL resources (avoiding macOS SIGABRT crashes).
 * For true isolation, use JVM fork (Maven Surefire forkCount).
 *
 * @see com.hellblazer.luciferase.resource.compute.GPUBackend
 * @see com.hellblazer.luciferase.resource.compute.BackendSelector
 */
public class OpenCLContext {

    private static final Logger log = LoggerFactory.getLogger(OpenCLContext.class);
    private static final Object LOCK = new Object();
    private static volatile OpenCLContext INSTANCE;

    private long context = 0L;
    private long commandQueue = 0L;
    private long device = 0L;
    private final AtomicInteger refCount = new AtomicInteger(0);
    private volatile boolean initialized = false;
    private volatile boolean outOfOrderSupported = false;

    private OpenCLContext() {
        // Private constructor for singleton
    }

    /**
     * Get the singleton instance.
     *
     * @return The singleton OpenCLContext instance
     */
    public static OpenCLContext getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new OpenCLContext();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Acquire the OpenCL context (increment reference count).
     * Initializes OpenCL on first acquisition.
     *
     * @throws RuntimeException if OpenCL initialization fails
     */
    public void acquire() {
        synchronized (LOCK) {
            if (refCount.incrementAndGet() == 1) {
                // First acquire - initialize OpenCL
                initialize();
            }
        }
    }

    /**
     * Release the OpenCL context (decrement reference count).
     *
     * <p>NOTE: Does NOT cleanup on last release - context persists until JVM shutdown.
     * OpenCL cannot be reliably re-initialized after cleanup in the same JVM,
     * and cleanup causes SIGABRT on macOS.
     */
    public void release() {
        synchronized (LOCK) {
            int count = refCount.decrementAndGet();
            if (count < 0) {
                log.warn("OpenCL context released more times than acquired!");
                refCount.set(0);
                return;
            }

            // NOTE: Do NOT cleanup when count reaches 0!
            // OpenCL context persists across test classes to avoid re-initialization issues.
            // Cleanup happens via shutdown hook or forceCleanup() only.
            if (count == 0) {
                log.debug("OpenCL context reference count reached 0 - context remains active until JVM shutdown");
            }
        }
    }

    /**
     * Get the OpenCL context handle.
     *
     * @return The native OpenCL context pointer
     * @throws IllegalStateException if not initialized
     */
    public long getContext() {
        if (!initialized || context == 0L) {
            throw new IllegalStateException("OpenCL context not initialized. Call acquire() first.");
        }
        return context;
    }

    /**
     * Get the OpenCL command queue handle.
     *
     * @return The native OpenCL command queue pointer
     * @throws IllegalStateException if not initialized
     */
    public long getCommandQueue() {
        if (!initialized || commandQueue == 0L) {
            throw new IllegalStateException("OpenCL command queue not initialized. Call acquire() first.");
        }
        return commandQueue;
    }

    /**
     * Get the OpenCL device handle.
     *
     * @return The native OpenCL device pointer
     * @throws IllegalStateException if not initialized
     */
    public long getDevice() {
        if (!initialized || device == 0L) {
            throw new IllegalStateException("OpenCL device not initialized. Call acquire() first.");
        }
        return device;
    }

    /**
     * Check if OpenCL is initialized.
     *
     * @return true if context, command queue, and device are all initialized
     */
    public boolean isInitialized() {
        return initialized && context != 0L && commandQueue != 0L;
    }

    /**
     * Get current reference count (for debugging).
     *
     * @return The current reference count
     */
    public int getRefCount() {
        return refCount.get();
    }

    /**
     * Check if out-of-order execution is supported on this device.
     *
     * @return true if command queue was created with CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE
     */
    public boolean isOutOfOrderSupported() {
        return outOfOrderSupported;
    }

    /**
     * Reset context state for testing. DOES NOT release OpenCL resources.
     *
     * <p><b>WARNING</b>: This method is for TESTING ONLY. It resets internal state
     * without releasing OpenCL resources to avoid macOS SIGABRT crashes.
     * For true isolation, use separate JVM forks.
     *
     * <p>After calling this method:
     * <ul>
     *   <li>getInstance() returns a new instance</li>
     *   <li>The old context/queue leak intentionally (macOS cleanup crashes JVM)</li>
     *   <li>Next acquire() will initialize a fresh context</li>
     * </ul>
     */
    public static void testReset() {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                log.debug("testReset() called - resetting singleton state (resources leak intentionally)");
                INSTANCE = null;
            }
        }
    }

    private void initialize() {
        if (initialized) {
            log.debug("OpenCL already initialized");
            return;
        }

        // Check system property to completely disable GPU (useful for headless testing)
        // Support both new and legacy property names
        var gpuDisabled = Boolean.getBoolean("gpu.disable");
        if (!gpuDisabled) {
            gpuDisabled = Boolean.getBoolean("luciferase.gpu.disable");
        }
        if (!gpuDisabled) {
            // Legacy support for ART
            gpuDisabled = Boolean.getBoolean("art.gpu.disable");
            if (gpuDisabled) {
                log.warn("art.gpu.disable is deprecated, use gpu.disable instead");
            }
        }

        if (gpuDisabled) {
            log.info("GPU disabled via system property - OpenCL not initialized");
            return;
        }

        // NOTE: We deliberately do NOT call CL.create() here.
        // CL.create() causes SIGSEGV crashes in Apple's GPU drivers when running
        // in forked JVM processes (like Maven Surefire). The OpenCL ICD loader
        // handles initialization automatically when we call clGetPlatformIDs,
        // and this approach is used by the existing gpu-test-framework tests.

        try (var stack = stackPush()) {
            // Get platform count first
            var numPlatforms = stack.mallocInt(1);
            var errcode = clGetPlatformIDs((PointerBuffer) null, numPlatforms);

            if (errcode != CL_SUCCESS || numPlatforms.get(0) == 0) {
                throw new RuntimeException("No OpenCL platforms found");
            }

            // Get first platform
            var platforms = stack.mallocPointer(1);
            clGetPlatformIDs(platforms, (int[]) null);
            var platform = platforms.get(0);

            // Get GPU device count first
            var numDevices = stack.mallocInt(1);
            var result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, null, numDevices);

            boolean useGPU = (result == CL_SUCCESS && numDevices.get(0) > 0);

            if (!useGPU) {
                // Fallback to CPU - check device count
                result = clGetDeviceIDs(platform, CL_DEVICE_TYPE_CPU, null, numDevices);
                if (result != CL_SUCCESS || numDevices.get(0) == 0) {
                    throw new RuntimeException("No OpenCL devices found (tried GPU and CPU)");
                }
            }

            // Now get the actual device
            var devices = stack.mallocPointer(1);
            if (useGPU) {
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices, (int[]) null);
            } else {
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_CPU, devices, (int[]) null);
            }

            device = devices.get(0);

            // Create context
            var error = stack.mallocInt(1);
            context = clCreateContext(null, device, null, 0L, error);

            if (error.get(0) != CL_SUCCESS) {
                throw new RuntimeException("Failed to create OpenCL context: " + error.get(0));
            }

            // Create command queue with out-of-order execution if supported
            // Try with out-of-order flag first (better performance on Apple GPUs)
            commandQueue = clCreateCommandQueue(context, device,
                    CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE, error);

            if (error.get(0) != CL_SUCCESS) {
                // Out-of-order execution not supported, try without flags
                log.debug("Out-of-order execution not supported (error {}), creating standard queue", error.get(0));
                commandQueue = clCreateCommandQueue(context, device, 0, error);

                if (error.get(0) != CL_SUCCESS) {
                    throw new RuntimeException("Failed to create OpenCL command queue: " + error.get(0));
                }
                outOfOrderSupported = false;
                log.info("OpenCL command queue created without out-of-order execution");
            } else {
                outOfOrderSupported = true;
                log.info("OpenCL command queue created with out-of-order execution enabled");
            }

            initialized = true;
            log.info("OpenCL context initialized successfully (refCount=1)");

        } catch (Exception e) {
            log.error("OpenCL initialization exception", e);
            cleanup();
            throw new RuntimeException("Failed to initialize OpenCL", e);
        }
    }

    /**
     * Cleanup is intentionally NOT implemented to avoid macOS OpenCL driver crashes.
     * The OS will reclaim OpenCL resources when the process terminates.
     *
     * <p>Attempting to call clReleaseCommandQueue() or clReleaseContext() on macOS
     * causes SIGABRT (Exit Code 134) during JVM shutdown due to Apple's deprecated
     * and buggy OpenCL implementation.
     *
     * <p>This is the recommended approach for LWJGL OpenCL on macOS.
     */
    private void cleanup() {
        log.debug("OpenCL cleanup skipped - resources will be reclaimed by OS on process exit");
        // Intentionally do nothing - let OS clean up on process termination
    }

    /**
     * Force cleanup is disabled to prevent macOS OpenCL driver crashes.
     * This method now does nothing - OpenCL resources persist until process exit.
     *
     * @deprecated Cleanup causes SIGABRT on macOS - resources are reclaimed by OS
     */
    @Deprecated
    public void forceCleanup() {
        log.debug("forceCleanup() called but ignored - OpenCL cleanup disabled to prevent macOS crashes");
        // Intentionally do nothing
    }
}
