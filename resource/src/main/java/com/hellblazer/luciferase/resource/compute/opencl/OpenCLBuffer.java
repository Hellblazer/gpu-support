package com.hellblazer.luciferase.resource.compute.opencl;

import com.hellblazer.luciferase.resource.compute.GPUBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opencl.CL10.*;

/**
 * OpenCL implementation of GPUBuffer.
 *
 * <p>Provides RAII lifecycle management for OpenCL buffer objects.
 * Uses the singleton {@link OpenCLContext} for context and command queue.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var buffer = OpenCLBuffer.create(1024, BufferAccess.READ_WRITE)) {
 *     float[] data = new float[256];
 *     buffer.upload(data);
 *     // Use buffer in kernel...
 *     buffer.download(data);
 * }
 * }</pre>
 *
 * @see GPUBuffer
 * @see OpenCLContext
 */
public class OpenCLBuffer implements GPUBuffer {

    private static final Logger log = LoggerFactory.getLogger(OpenCLBuffer.class);

    /**
     * Buffer access mode flags.
     */
    public enum BufferAccess {
        READ_ONLY(CL_MEM_READ_ONLY),
        WRITE_ONLY(CL_MEM_WRITE_ONLY),
        READ_WRITE(CL_MEM_READ_WRITE);

        private final int flag;

        BufferAccess(int flag) {
            this.flag = flag;
        }

        public int getFlag() {
            return flag;
        }
    }

    private final long buffer;
    private final int sizeInFloats;
    private final int sizeInBytes;
    private final BufferAccess access;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Create a new OpenCL buffer.
     *
     * @param sizeInFloats Number of floats the buffer can hold
     * @param access       Buffer access mode
     * @return New OpenCL buffer
     * @throws IllegalStateException if OpenCL is not available
     */
    public static OpenCLBuffer create(int sizeInFloats, BufferAccess access) {
        var ctx = OpenCLContext.getInstance();
        if (!ctx.isInitialized()) {
            ctx.acquire();
        }

        try (var stack = MemoryStack.stackPush()) {
            var errcode = stack.mallocInt(1);
            long sizeBytes = (long) sizeInFloats * Float.BYTES;

            long buffer = clCreateBuffer(
                    ctx.getContext(),
                    access.getFlag(),
                    sizeBytes,
                    errcode
            );

            checkError(errcode.get(0), "clCreateBuffer");

            if (buffer == 0) {
                throw new IllegalStateException("Failed to create OpenCL buffer - null handle returned");
            }

            log.debug("Created OpenCL buffer: handle={}, size={} floats ({} bytes), access={}",
                    buffer, sizeInFloats, sizeBytes, access);

            return new OpenCLBuffer(buffer, sizeInFloats, (int) sizeBytes, access);
        }
    }

    /**
     * Create a buffer initialized with data.
     *
     * @param data   Initial data to upload
     * @param access Buffer access mode
     * @return New OpenCL buffer with data
     */
    public static OpenCLBuffer createWithData(float[] data, BufferAccess access) {
        var buffer = create(data.length, access);
        buffer.upload(data);
        return buffer;
    }

    private OpenCLBuffer(long buffer, int sizeInFloats, int sizeInBytes, BufferAccess access) {
        this.buffer = buffer;
        this.sizeInFloats = sizeInFloats;
        this.sizeInBytes = sizeInBytes;
        this.access = access;
    }

    @Override
    public void upload(FloatBuffer data) {
        checkValid();
        if (data.remaining() > sizeInFloats) {
            throw new IllegalArgumentException("Data size " + data.remaining() +
                    " exceeds buffer size " + sizeInFloats);
        }

        var ctx = OpenCLContext.getInstance();
        int bytesToWrite = data.remaining() * Float.BYTES;

        int error = clEnqueueWriteBuffer(
                ctx.getCommandQueue(),
                buffer,
                true, // blocking
                0,
                data,
                null,
                null
        );
        checkError(error, "clEnqueueWriteBuffer");

        log.trace("Uploaded {} floats ({} bytes) to buffer", data.remaining(), bytesToWrite);
    }

    @Override
    public void upload(float[] data) {
        checkValid();
        if (data.length > sizeInFloats) {
            throw new IllegalArgumentException("Data size " + data.length +
                    " exceeds buffer size " + sizeInFloats);
        }

        // Allocate direct buffer for OpenCL
        ByteBuffer byteBuffer = MemoryUtil.memAlloc(data.length * Float.BYTES);
        try {
            byteBuffer.asFloatBuffer().put(data);
            byteBuffer.rewind();

            var ctx = OpenCLContext.getInstance();
            int error = clEnqueueWriteBuffer(
                    ctx.getCommandQueue(),
                    buffer,
                    true, // blocking
                    0,
                    byteBuffer,
                    null,
                    null
            );
            checkError(error, "clEnqueueWriteBuffer");

            log.trace("Uploaded {} floats ({} bytes) to buffer", data.length, data.length * Float.BYTES);
        } finally {
            MemoryUtil.memFree(byteBuffer);
        }
    }

    @Override
    public void download(FloatBuffer data) {
        checkValid();
        if (data.remaining() > sizeInFloats) {
            throw new IllegalArgumentException("Buffer capacity " + data.remaining() +
                    " exceeds buffer size " + sizeInFloats);
        }

        var ctx = OpenCLContext.getInstance();

        int error = clEnqueueReadBuffer(
                ctx.getCommandQueue(),
                buffer,
                true, // blocking
                0,
                data,
                null,
                null
        );
        checkError(error, "clEnqueueReadBuffer");

        log.trace("Downloaded {} floats ({} bytes) from buffer",
                data.remaining(), data.remaining() * Float.BYTES);
    }

    @Override
    public void download(float[] data) {
        checkValid();
        if (data.length > sizeInFloats) {
            throw new IllegalArgumentException("Array size " + data.length +
                    " exceeds buffer size " + sizeInFloats);
        }

        // Allocate direct buffer for OpenCL
        ByteBuffer byteBuffer = MemoryUtil.memAlloc(data.length * Float.BYTES);
        try {
            var ctx = OpenCLContext.getInstance();
            int error = clEnqueueReadBuffer(
                    ctx.getCommandQueue(),
                    buffer,
                    true, // blocking
                    0,
                    byteBuffer,
                    null,
                    null
            );
            checkError(error, "clEnqueueReadBuffer");

            byteBuffer.asFloatBuffer().get(data);

            log.trace("Downloaded {} floats ({} bytes) from buffer",
                    data.length, data.length * Float.BYTES);
        } finally {
            MemoryUtil.memFree(byteBuffer);
        }
    }

    @Override
    public int size() {
        return sizeInFloats;
    }

    @Override
    public int sizeInBytes() {
        return sizeInBytes;
    }

    @Override
    public boolean isValid() {
        return !closed.get() && buffer != 0;
    }

    /**
     * Get the native OpenCL buffer handle.
     *
     * @return Native buffer pointer
     * @throws IllegalStateException if buffer is closed
     */
    public long getHandle() {
        checkValid();
        return buffer;
    }

    /**
     * Get the buffer access mode.
     *
     * @return Buffer access mode
     */
    public BufferAccess getAccess() {
        return access;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            int error = clReleaseMemObject(buffer);
            if (error != CL_SUCCESS) {
                log.error("Failed to release OpenCL buffer {}: error {}", buffer, error);
            } else {
                log.debug("Released OpenCL buffer: handle={}", buffer);
            }
        }
    }

    private void checkValid() {
        if (closed.get()) {
            throw new IllegalStateException("Buffer has been closed");
        }
    }

    private static void checkError(int error, String operation) {
        if (error != CL_SUCCESS) {
            throw new RuntimeException("OpenCL " + operation + " failed with error code: " + error +
                    " (" + translateError(error) + ")");
        }
    }

    /**
     * Translate OpenCL error code to human-readable message.
     */
    public static String translateError(int error) {
        return switch (error) {
            case CL_SUCCESS -> "Success";
            case CL_DEVICE_NOT_FOUND -> "Device not found";
            case CL_DEVICE_NOT_AVAILABLE -> "Device not available";
            case CL_COMPILER_NOT_AVAILABLE -> "Compiler not available";
            case CL_MEM_OBJECT_ALLOCATION_FAILURE -> "Memory object allocation failure";
            case CL_OUT_OF_RESOURCES -> "Out of resources";
            case CL_OUT_OF_HOST_MEMORY -> "Out of host memory";
            case CL_INVALID_VALUE -> "Invalid value";
            case CL_INVALID_CONTEXT -> "Invalid context";
            case CL_INVALID_COMMAND_QUEUE -> "Invalid command queue";
            case CL_INVALID_MEM_OBJECT -> "Invalid memory object";
            case CL_INVALID_BUFFER_SIZE -> "Invalid buffer size";
            default -> "Unknown error (" + error + ")";
        };
    }
}
