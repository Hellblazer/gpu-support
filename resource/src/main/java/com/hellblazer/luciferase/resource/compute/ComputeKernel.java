package com.hellblazer.luciferase.resource.compute;

import org.lwjgl.PointerBuffer;

/**
 * Unified interface for GPU compute kernels across Metal and OpenCL backends.
 * Provides a common API for executing compute operations on the GPU.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (ComputeKernel kernel = context.createKernel("myKernel")) {
 *     kernel.compile(kernelSource, "main");
 *     kernel.setBufferArg(0, inputBuffer, BufferAccess.READ);
 *     kernel.setBufferArg(1, outputBuffer, BufferAccess.WRITE);
 *     kernel.setIntArg(2, dataSize);
 *     kernel.execute(dataSize);
 *     kernel.finish();
 * }
 * }</pre>
 *
 * @see GPUBuffer
 * @see GPUBackend
 */
public interface ComputeKernel extends AutoCloseable {

    /**
     * Compile the kernel from source code.
     *
     * @param source     Kernel source code (Metal or OpenCL)
     * @param entryPoint Kernel entry point function name
     * @throws KernelCompilationException if compilation fails
     */
    void compile(String source, String entryPoint) throws KernelCompilationException;

    /**
     * Compile the kernel from source code with build options for GPU auto-tuning.
     *
     * <p>Build options enable runtime kernel customization through preprocessor defines
     * and compiler flags, essential for GPU auto-tuning and performance optimization.
     *
     * <h3>Build Options Examples:</h3>
     * <ul>
     *   <li><b>Preprocessor Defines:</b> {@code "-DBLOCK_SIZE=256 -DENABLE_SHARED_MEMORY=1"}</li>
     *   <li><b>Compiler Flags:</b> {@code "-cl-fast-relaxed-math -cl-mad-enable"}</li>
     *   <li><b>Warning Control:</b> {@code "-Werror"} (treat warnings as errors)</li>
     *   <li><b>Vendor-Specific:</b> {@code "-D__CUDA_ARCH__=700"} (NVIDIA), {@code "-D__GCN__"} (AMD)</li>
     * </ul>
     *
     * <h3>Use Cases:</h3>
     * <ul>
     *   <li>Runtime work group size tuning: {@code "-DWORK_GROUP_SIZE=256"}</li>
     *   <li>Feature toggling: {@code "-DENABLE_FEATURE=1"}</li>
     *   <li>Math optimizations: {@code "-cl-fast-relaxed-math"}</li>
     *   <li>Architecture-specific tuning: {@code "-D__GCN_REV__=2"}</li>
     * </ul>
     *
     * <p><b>Security Note:</b> Build options are passed directly to the backend compiler without
     * sanitization. Ensure options originate from trusted sources only to prevent compiler-based
     * denial-of-service or unexpected behavior.
     *
     * @param source       Kernel source code (Metal or OpenCL)
     * @param entryPoint   Kernel entry point function name
     * @param buildOptions Compiler flags and preprocessor defines (null or empty for defaults).
     *                     Passed directly to the backend compiler without validation.
     * @throws KernelCompilationException if compilation fails
     * @see #recompile(String, String, String)
     */
    default void compile(String source, String entryPoint, String buildOptions)
            throws KernelCompilationException {
        throw new UnsupportedOperationException("Build options not supported by this compute backend");
    }

    /**
     * Recompile an already-compiled kernel with different build options.
     *
     * <p>Enables runtime GPU auto-tuning by recompiling kernels with different optimization
     * parameters. Useful for performance experiments and adaptive optimization strategies.
     *
     * <h3>Recompilation Workflow:</h3>
     * <pre>{@code
     * // Initial compilation
     * kernel.compile(source, "myKernel", "-DBLOCK_SIZE=128");
     * kernel.execute(globalSize);  // Test performance
     *
     * // Recompile with different block size
     * kernel.recompile(source, "myKernel", "-DBLOCK_SIZE=256");
     * kernel.execute(globalSize);  // Compare performance
     * }</pre>
     *
     * <p><b>Note:</b> Recompilation releases the old kernel and program resources, then compiles
     * a fresh kernel. The kernel object itself remains valid and usable after recompilation.
     *
     * <p><b>Thread Safety:</b> During recompilation, {@link #isCompiled()} may briefly return false
     * as resources are released and replaced. Concurrent kernel execution from other threads during
     * recompilation will fail with IllegalStateException. Callers must ensure exclusive access to
     * the kernel object during recompilation.
     *
     * @param source       Kernel source code (must match original source for consistency)
     * @param entryPoint   Kernel entry point function name
     * @param buildOptions New compiler flags and preprocessor defines
     * @throws KernelCompilationException if recompilation fails
     * @see #compile(String, String, String)
     */
    default void recompile(String source, String entryPoint, String buildOptions)
            throws KernelCompilationException {
        throw new UnsupportedOperationException("Recompilation not supported by this compute backend");
    }

    /**
     * Set a buffer argument for the kernel.
     *
     * @param index  Argument index (0-based)
     * @param buffer Buffer to bind
     * @param access Access mode (READ, WRITE, READ_WRITE)
     * @throws IllegalArgumentException if index is negative or buffer is null
     * @throws IllegalStateException    if kernel is not compiled
     */
    void setBufferArg(int index, GPUBuffer buffer, BufferAccess access);

    /**
     * Set a scalar float argument for the kernel.
     *
     * @param index Argument index (0-based)
     * @param value Float value
     * @throws IllegalArgumentException if index is negative
     * @throws IllegalStateException    if kernel is not compiled
     */
    void setFloatArg(int index, float value);

    /**
     * Set a scalar int argument for the kernel.
     *
     * @param index Argument index (0-based)
     * @param value Int value
     * @throws IllegalArgumentException if index is negative
     * @throws IllegalStateException    if kernel is not compiled
     */
    void setIntArg(int index, int value);

    /**
     * Execute the kernel with specified global work size (1D).
     *
     * @param globalWorkSize Number of work items
     * @throws KernelExecutionException if execution fails
     * @throws IllegalStateException    if kernel is not compiled
     */
    void execute(int globalWorkSize) throws KernelExecutionException;

    /**
     * Execute the kernel with specified global work size (2D).
     *
     * @param globalWorkSizeX Number of work items in X dimension
     * @param globalWorkSizeY Number of work items in Y dimension
     * @throws KernelExecutionException if execution fails
     * @throws IllegalStateException    if kernel is not compiled
     */
    void execute(int globalWorkSizeX, int globalWorkSizeY) throws KernelExecutionException;

    /**
     * Execute the kernel with specified global work size (3D).
     *
     * @param globalWorkSizeX Number of work items in X dimension
     * @param globalWorkSizeY Number of work items in Y dimension
     * @param globalWorkSizeZ Number of work items in Z dimension
     * @throws KernelExecutionException if execution fails
     * @throws IllegalStateException    if kernel is not compiled
     */
    void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ)
            throws KernelExecutionException;

    /**
     * Execute the kernel with explicit local work group sizes (3D).
     * Allows tuning work group dimensions for optimal GPU occupancy.
     *
     * <p>For Apple M4 Max optimal configurations:
     * <ul>
     *   <li>Total work items per group: localX × localY × localZ ≤ 1024</li>
     *   <li>Network dimension (X): 256-512 for best compute/memory balance</li>
     *   <li>Batch dimension (Y): 2-8 to reach 1024 total work items</li>
     * </ul>
     *
     * @param globalWorkSizeX Number of work items in X dimension
     * @param globalWorkSizeY Number of work items in Y dimension
     * @param globalWorkSizeZ Number of work items in Z dimension
     * @param localWorkSizeX  Work group size in X dimension
     * @param localWorkSizeY  Work group size in Y dimension
     * @param localWorkSizeZ  Work group size in Z dimension
     * @throws KernelExecutionException if execution fails
     * @throws IllegalStateException    if kernel is not compiled
     */
    void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                 int localWorkSizeX, int localWorkSizeY, int localWorkSizeZ)
            throws KernelExecutionException;

    /**
     * Execute kernel asynchronously with event-based synchronization.
     *
     * <p>This method enables compute/transfer pipelining by passing event handles
     * to OpenCL/Metal for proper dependency management. Events allow operations
     * to execute in parallel while maintaining correct ordering.
     *
     * <h3>Event Lifecycle</h3>
     * <ul>
     *   <li><b>waitEvents</b>: Events to wait on before execution (caller owns, we read)</li>
     *   <li><b>signalEvent</b>: Event to signal when complete (we create, caller must release)</li>
     * </ul>
     *
     * <h3>Usage Example</h3>
     * <pre>{@code
     * try (var stack = MemoryStack.stackPush()) {
     *     var uploadEvent = stack.mallocPointer(1);
     *     var computeEvent = stack.mallocPointer(1);
     *
     *     // Upload data (creates uploadEvent)
     *     clEnqueueWriteBuffer(..., uploadEvent);
     *
     *     // Compute waits on upload, signals computeEvent
     *     kernel.executeAsync(batchSize, 1, 1, uploadEvent, computeEvent);
     *
     *     // Download waits on compute
     *     clEnqueueReadBuffer(..., computeEvent, null);
     *
     *     // Cleanup events
     *     clReleaseEvent(uploadEvent.get(0));
     *     clReleaseEvent(computeEvent.get(0));
     * }
     * }</pre>
     *
     * <p><b>IMPORTANT</b>: Caller is responsible for releasing signalEvent via
     * {@code clReleaseEvent(signalEvent.get(0))} to prevent resource leaks.
     *
     * <p><b>Note</b>: If both waitEvents and signalEvent are null, this behaves
     * identically to {@link #execute(int, int, int)} (blocking execution).
     *
     * @param globalWorkSizeX Number of work items in X dimension
     * @param globalWorkSizeY Number of work items in Y dimension
     * @param globalWorkSizeZ Number of work items in Z dimension
     * @param waitEvents      Events to wait on before execution (null = no wait)
     * @param signalEvent     Event to signal on completion (null = no signal)
     * @throws KernelExecutionException if execution fails
     * @throws IllegalStateException    if kernel is not compiled
     * @see <a href="https://registry.khronos.org/OpenCL/specs/3.0-unified/html/OpenCL_API.html#event-objects">OpenCL Event Objects</a>
     */
    void executeAsync(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                      PointerBuffer waitEvents, PointerBuffer signalEvent)
            throws KernelExecutionException;

    /**
     * Wait for kernel execution to complete.
     * Blocks until all queued operations finish.
     */
    void finish();

    /**
     * Get the backend type for this kernel.
     *
     * @return Backend type (METAL, OPENCL, or CPU_FALLBACK)
     */
    GPUBackend getBackend();

    /**
     * Check if the kernel is compiled and ready to execute.
     *
     * @return true if kernel is compiled
     */
    boolean isCompiled();

    /**
     * Release GPU resources.
     */
    @Override
    void close();

    /**
     * Buffer access modes for kernel arguments.
     */
    enum BufferAccess {
        /** Buffer is read-only in kernel */
        READ,
        /** Buffer is write-only in kernel */
        WRITE,
        /** Buffer is read-write in kernel */
        READ_WRITE
    }

    /**
     * Exception thrown when kernel compilation fails.
     */
    class KernelCompilationException extends Exception {
        public KernelCompilationException(String message) {
            super(message);
        }

        public KernelCompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when kernel execution fails.
     */
    class KernelExecutionException extends Exception {
        public KernelExecutionException(String message) {
            super(message);
        }

        public KernelExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
