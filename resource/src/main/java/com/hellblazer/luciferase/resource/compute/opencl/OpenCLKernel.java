package com.hellblazer.luciferase.resource.compute.opencl;

import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.GPUBackend;
import com.hellblazer.luciferase.resource.compute.GPUBuffer;
import org.lwjgl.PointerBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * OpenCL compute kernel implementation.
 *
 * <p>Provides OpenCL kernel compilation and execution for cross-platform GPU compute.
 * Uses the singleton {@link OpenCLContext} for context and command queue.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var kernel = OpenCLKernel.create("myKernel")) {
 *     kernel.compile(kernelSource, "main");
 *     kernel.setBufferArg(0, inputBuffer, BufferAccess.READ);
 *     kernel.setBufferArg(1, outputBuffer, BufferAccess.WRITE);
 *     kernel.setIntArg(2, dataSize);
 *     kernel.execute(dataSize);
 *     kernel.finish();
 * }
 * }</pre>
 *
 * @see ComputeKernel
 * @see OpenCLContext
 * @see OpenCLBuffer
 */
public class OpenCLKernel implements ComputeKernel {

    private static final Logger log = LoggerFactory.getLogger(OpenCLKernel.class);

    private final String name;
    private final long context;
    private final long commandQueue;
    private final long device;
    private long program = NULL;
    long kernel = NULL;  // Package-private for testing
    private final AtomicBoolean compiled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<Integer, BufferBinding> bufferBindings = new HashMap<>();

    /**
     * Create a new OpenCL kernel.
     *
     * @param name Descriptive name for the kernel (for logging)
     * @return New OpenCL kernel
     * @throws IllegalStateException if OpenCL is not available
     */
    public static OpenCLKernel create(String name) {
        var ctx = OpenCLContext.getInstance();
        if (!ctx.isInitialized()) {
            ctx.acquire();
        }

        return new OpenCLKernel(
                name,
                ctx.getContext(),
                ctx.getCommandQueue(),
                ctx.getDevice()
        );
    }

    private OpenCLKernel(String name, long context, long commandQueue, long device) {
        this.name = name;
        this.context = context;
        this.commandQueue = commandQueue;
        this.device = device;
        log.debug("Created OpenCL kernel: {}", name);
    }

    @Override
    public void compile(String source, String entryPoint) throws KernelCompilationException {
        checkNotClosed();
        if (compiled.get()) {
            throw new KernelCompilationException("Kernel already compiled");
        }

        try (var stack = stackPush()) {
            // Create program from source
            var errcode = stack.mallocInt(1);
            program = clCreateProgramWithSource(context, source, errcode);
            checkCLError(errcode.get(0), "Failed to create OpenCL program");

            // Build program for specific device
            var devices = stack.mallocPointer(1);
            devices.put(0, device);
            var buildStatus = clBuildProgram(program, devices, "", null, NULL);
            if (buildStatus != CL_SUCCESS) {
                // Get build log
                var logSize = stack.mallocPointer(1);
                clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (PointerBuffer) null, logSize);

                if (logSize.get(0) > 0) {
                    var logBuffer = stack.malloc((int) logSize.get(0));
                    clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, logBuffer, null);

                    var buildLog = memUTF8(logBuffer);
                    throw new KernelCompilationException(
                            "OpenCL kernel compilation failed:\n" + buildLog
                    );
                } else {
                    throw new KernelCompilationException(
                            "OpenCL kernel compilation failed (no build log available)"
                    );
                }
            }

            // Create kernel
            kernel = clCreateKernel(program, entryPoint, errcode);
            checkCLError(errcode.get(0), "Failed to create OpenCL kernel: " + entryPoint);

            compiled.set(true);
            log.debug("Compiled OpenCL kernel: {} (entry point: {})", name, entryPoint);

        } catch (Exception e) {
            cleanup();
            if (e instanceof KernelCompilationException kce) {
                throw kce;
            }
            throw new KernelCompilationException("OpenCL kernel compilation failed: " + name, e);
        }
    }

    @Override
    public void setBufferArg(int index, GPUBuffer buffer, BufferAccess access) {
        checkNotClosed();
        checkCompiled();

        if (!(buffer instanceof OpenCLBuffer openCLBuffer)) {
            throw new IllegalArgumentException("Buffer must be OpenCLBuffer");
        }

        var binding = new BufferBinding(openCLBuffer, access);
        bufferBindings.put(index, binding);

        // Set kernel argument
        try {
            checkCLError(
                    clSetKernelArg1p(kernel, index, openCLBuffer.getHandle()),
                    "Failed to set buffer argument " + index
            );
        } catch (KernelCompilationException e) {
            throw new RuntimeException(e);  // Convert to unchecked for setter
        }
    }

    @Override
    public void setFloatArg(int index, float value) {
        checkNotClosed();
        checkCompiled();

        try (var stack = stackPush()) {
            var buffer = stack.mallocFloat(1).put(0, value);
            try {
                checkCLError(
                        clSetKernelArg(kernel, index, buffer),
                        "Failed to set float argument " + index
                );
            } catch (KernelCompilationException e) {
                throw new RuntimeException(e);  // Convert to unchecked for setter
            }
        }
    }

    @Override
    public void setIntArg(int index, int value) {
        checkNotClosed();
        checkCompiled();

        try (var stack = stackPush()) {
            var buffer = stack.mallocInt(1).put(0, value);
            try {
                checkCLError(
                        clSetKernelArg(kernel, index, buffer),
                        "Failed to set int argument " + index
                );
            } catch (KernelCompilationException e) {
                throw new RuntimeException(e);  // Convert to unchecked for setter
            }
        }
    }

    /**
     * Set local memory argument (for __local buffers).
     *
     * @param index       Argument index
     * @param sizeInBytes Size of local memory in bytes
     */
    public void setLocalMemoryArg(int index, int sizeInBytes) {
        checkNotClosed();
        checkCompiled();

        try {
            checkCLError(
                    clSetKernelArg(kernel, index, sizeInBytes),
                    "Failed to set local memory argument " + index
            );
        } catch (KernelCompilationException e) {
            throw new RuntimeException(e);  // Convert to unchecked for setter
        }
    }

    /**
     * Set a raw OpenCL memory object as a kernel argument.
     *
     * <p>This method allows setting raw cl_mem handles directly, useful when
     * working with buffers created outside the GPUBuffer abstraction (e.g.,
     * ByteBuffer uploads via clCreateBuffer).
     *
     * @param index Argument index
     * @param clMem Raw OpenCL memory object handle (cl_mem)
     * @throws IllegalStateException if kernel is closed or not compiled
     */
    public void setRawBufferArg(int index, long clMem) {
        checkNotClosed();
        checkCompiled();

        try {
            checkCLError(
                    clSetKernelArg1p(kernel, index, clMem),
                    "Failed to set raw buffer argument " + index
            );
        } catch (KernelCompilationException e) {
            throw new RuntimeException(e);  // Convert to unchecked for setter
        }
    }

    /**
     * Set a float3 vector argument.
     *
     * <p>Passes a 3-component float vector to the kernel. Useful for
     * scene bounds, positions, directions, etc.
     *
     * @param index Argument index
     * @param x     X component
     * @param y     Y component
     * @param z     Z component
     * @throws IllegalStateException if kernel is closed or not compiled
     */
    public void setFloat3Arg(int index, float x, float y, float z) {
        checkNotClosed();
        checkCompiled();

        try (var stack = stackPush()) {
            var buffer = stack.mallocFloat(3);
            buffer.put(0, x);
            buffer.put(1, y);
            buffer.put(2, z);
            try {
                checkCLError(
                        clSetKernelArg(kernel, index, buffer),
                        "Failed to set float3 argument " + index
                );
            } catch (KernelCompilationException e) {
                throw new RuntimeException(e);  // Convert to unchecked for setter
            }
        }
    }

    @Override
    public void execute(int globalWorkSize) throws KernelExecutionException {
        execute(globalWorkSize, 1, 1);
    }

    @Override
    public void execute(int globalWorkSizeX, int globalWorkSizeY) throws KernelExecutionException {
        execute(globalWorkSizeX, globalWorkSizeY, 1);
    }

    @Override
    public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ)
            throws KernelExecutionException {

        checkNotClosed();
        if (!compiled.get()) {
            throw new KernelExecutionException("Kernel not compiled");
        }

        try (var stack = stackPush()) {
            // Set global work size
            var globalWorkSize = stack.mallocPointer(3);
            globalWorkSize.put(0, globalWorkSizeX);
            globalWorkSize.put(1, globalWorkSizeY);
            globalWorkSize.put(2, globalWorkSizeZ);

            // Enqueue kernel
            var errcode = clEnqueueNDRangeKernel(
                    commandQueue,
                    kernel,
                    3,  // work_dim
                    null,  // global_work_offset
                    globalWorkSize,
                    null,  // local_work_size (auto-select)
                    null,  // event_wait_list
                    null   // event
            );

            checkCLErrorExecution(errcode, "Failed to enqueue OpenCL kernel");

            log.trace("Executed OpenCL kernel: {} with work size [{}, {}, {}]",
                    name, globalWorkSizeX, globalWorkSizeY, globalWorkSizeZ);

        } catch (Exception e) {
            throw new KernelExecutionException("OpenCL kernel execution failed: " + name, e);
        }
    }

    @Override
    public void execute(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                        int localWorkSizeX, int localWorkSizeY, int localWorkSizeZ)
            throws KernelExecutionException {

        checkNotClosed();
        if (!compiled.get()) {
            throw new KernelExecutionException("Kernel not compiled");
        }

        try (var stack = stackPush()) {
            // Set global work size
            var globalWorkSize = stack.mallocPointer(3);
            globalWorkSize.put(0, globalWorkSizeX);
            globalWorkSize.put(1, globalWorkSizeY);
            globalWorkSize.put(2, globalWorkSizeZ);

            // Set local work size (explicit work group dimensions)
            var localWorkSize = stack.mallocPointer(3);
            localWorkSize.put(0, localWorkSizeX);
            localWorkSize.put(1, localWorkSizeY);
            localWorkSize.put(2, localWorkSizeZ);

            // Enqueue kernel with explicit local work size
            var errcode = clEnqueueNDRangeKernel(
                    commandQueue,
                    kernel,
                    3,  // work_dim
                    null,  // global_work_offset
                    globalWorkSize,
                    localWorkSize,  // explicit local work size for optimal GPU occupancy
                    null,  // event_wait_list
                    null   // event
            );

            checkCLErrorExecution(errcode, "Failed to enqueue OpenCL kernel");

            log.trace("Executed OpenCL kernel: {} with global work size [{}, {}, {}] and local work size [{}, {}, {}]",
                    name, globalWorkSizeX, globalWorkSizeY, globalWorkSizeZ,
                    localWorkSizeX, localWorkSizeY, localWorkSizeZ);

        } catch (Exception e) {
            throw new KernelExecutionException("OpenCL kernel execution failed: " + name, e);
        }
    }

    @Override
    public void executeAsync(int globalWorkSizeX, int globalWorkSizeY, int globalWorkSizeZ,
                             PointerBuffer waitEvents, PointerBuffer signalEvent)
            throws KernelExecutionException {

        checkNotClosed();
        if (!compiled.get()) {
            throw new KernelExecutionException("Kernel not compiled");
        }

        try (var stack = stackPush()) {
            // Set up global work size
            var globalWorkSize = stack.mallocPointer(3);
            globalWorkSize.put(0, globalWorkSizeX);
            globalWorkSize.put(1, globalWorkSizeY);
            globalWorkSize.put(2, globalWorkSizeZ);

            // Execute kernel with event parameters (enables async pipelining)
            var errcode = clEnqueueNDRangeKernel(
                    commandQueue,
                    kernel,
                    3,                    // work_dim
                    null,                 // global_work_offset
                    globalWorkSize,       // global_work_size
                    null,                 // local_work_size (auto)
                    waitEvents,           // event_wait_list (passed through)
                    signalEvent           // event (passed through)
            );

            if (errcode != CL_SUCCESS) {
                throw new KernelExecutionException(
                        String.format("Failed to enqueue async kernel: %s (error code: %d)",
                                name, errcode)
                );
            }

            log.trace("Executed async OpenCL kernel: {} with work size [{}, {}, {}]",
                    name, globalWorkSizeX, globalWorkSizeY, globalWorkSizeZ);

        } catch (KernelExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new KernelExecutionException(
                    "OpenCL async kernel execution failed: " + name, e);
        }
    }

    @Override
    public void finish() {
        checkNotClosed();
        // Wait for command queue to finish
        clFinish(commandQueue);
    }

    @Override
    public GPUBackend getBackend() {
        return GPUBackend.OPENCL;
    }

    @Override
    public boolean isCompiled() {
        return compiled.get();
    }

    /**
     * Get the kernel name.
     *
     * @return Kernel name
     */
    public String getName() {
        return name;
    }

    /**
     * Check if the kernel is still valid (not closed).
     *
     * @return true if kernel is valid
     */
    public boolean isValid() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanup();
        }
    }

    private void cleanup() {
        if (kernel != NULL) {
            clReleaseKernel(kernel);
            kernel = NULL;
        }
        if (program != NULL) {
            clReleaseProgram(program);
            program = NULL;
        }
        compiled.set(false);
        bufferBindings.clear();
        log.debug("Released OpenCL kernel: {}", name);
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Kernel has been closed");
        }
    }

    private void checkCompiled() {
        if (!compiled.get()) {
            throw new IllegalStateException("Kernel not compiled");
        }
    }

    private void checkCLError(int errcode, String message) throws KernelCompilationException {
        if (errcode != CL_SUCCESS) {
            throw new KernelCompilationException(
                    String.format("%s (error code: %d - %s)", message, errcode,
                            OpenCLBuffer.translateError(errcode))
            );
        }
    }

    private void checkCLErrorExecution(int errcode, String message) throws KernelExecutionException {
        if (errcode != CL_SUCCESS) {
            throw new KernelExecutionException(
                    String.format("%s (error code: %d - %s)", message, errcode,
                            OpenCLBuffer.translateError(errcode))
            );
        }
    }

    private record BufferBinding(OpenCLBuffer buffer, BufferAccess access) {
    }
}
