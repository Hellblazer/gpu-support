package com.hellblazer.luciferase.resource.compute;

import java.nio.FloatBuffer;

/**
 * GPU buffer abstraction for compute backends (OpenCL, Metal).
 * Manages host-device memory transfers and buffer lifecycle.
 *
 * <p>This interface provides a backend-agnostic API for GPU buffer operations.
 * Implementations exist for OpenCL ({@code OpenCLBuffer}) with Metal stubs
 * for future expansion.
 *
 * <p>Typical usage:
 * <pre>{@code
 * try (GPUBuffer buffer = context.createBuffer(1024)) {
 *     buffer.upload(hostData);
 *     // ... GPU computation ...
 *     buffer.download(results);
 * }
 * }</pre>
 *
 * @see com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer
 */
public interface GPUBuffer extends AutoCloseable {

    /**
     * Upload data from host to device.
     *
     * @param data Host data to upload
     * @throws IllegalStateException if buffer is not valid
     * @throws IllegalArgumentException if data size exceeds buffer capacity
     */
    void upload(FloatBuffer data);

    /**
     * Upload data from host to device.
     *
     * @param data Host data to upload
     * @throws IllegalStateException if buffer is not valid
     * @throws IllegalArgumentException if data size exceeds buffer capacity
     */
    void upload(float[] data);

    /**
     * Download data from device to host.
     *
     * @param data Host buffer to receive data
     * @throws IllegalStateException if buffer is not valid
     * @throws IllegalArgumentException if buffer capacity is insufficient
     */
    void download(FloatBuffer data);

    /**
     * Download data from device to host.
     *
     * @param data Host array to receive data
     * @throws IllegalStateException if buffer is not valid
     * @throws IllegalArgumentException if array size is insufficient
     */
    void download(float[] data);

    /**
     * Get the size of the buffer in elements (floats).
     *
     * @return Buffer size in float elements
     */
    int size();

    /**
     * Get the size of the buffer in bytes.
     *
     * @return Buffer size in bytes
     */
    int sizeInBytes();

    /**
     * Check if the buffer is valid and allocated.
     * A buffer becomes invalid after {@link #close()} is called.
     *
     * @return true if buffer is valid and can be used
     */
    boolean isValid();

    /**
     * Release GPU resources.
     * After calling this method, {@link #isValid()} returns false.
     */
    @Override
    void close();
}
