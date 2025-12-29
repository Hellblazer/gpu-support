package com.hellblazer.luciferase.resource.compute;

import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-level facade for GPU compute operations.
 *
 * <p>Provides a simplified API for common GPU compute tasks, hiding the complexity
 * of context management, buffer allocation, and kernel execution.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Automatic backend selection (GPU with CPU fallback)</li>
 *   <li>Built-in kernels for common operations (SAXPY, reduce, transform)</li>
 *   <li>Simplified buffer management</li>
 *   <li>Thread-safe singleton access</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var compute = ComputeService.getInstance();
 *
 * // Simple SAXPY: result = 2.0 * x + y
 * float[] result = compute.saxpy(2.0f, x, y);
 *
 * // Vector addition
 * float[] sum = compute.vectorAdd(a, b);
 *
 * // Custom kernel
 * try (var op = compute.createOperation("myKernel", kernelSource, "entryPoint")) {
 *     op.setInput(0, inputData);
 *     op.setOutput(1, outputSize);
 *     op.setArg(2, 42);
 *     float[] result = op.execute(workSize);
 * }
 * }</pre>
 *
 * @see GPUBackend
 * @see BackendSelector
 */
public class ComputeService {

    private static final Logger log = LoggerFactory.getLogger(ComputeService.class);

    private static volatile ComputeService INSTANCE;
    private static final Object LOCK = new Object();

    private final GPUBackend backend;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // Cached kernel sources
    private String vectorAddSource;
    private String saxpySource;
    private String transformSource;

    private ComputeService() {
        this.backend = BackendSelector.getOptimalBackend();
        log.info("ComputeService initialized with backend: {}", backend.getDisplayName());
    }

    /**
     * Get the singleton instance.
     *
     * @return ComputeService instance
     */
    public static ComputeService getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new ComputeService();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Check if GPU compute is available.
     *
     * @return true if GPU backend is available
     */
    public boolean isGPUAvailable() {
        return backend.isGPU() && backend.isAvailable();
    }

    /**
     * Get the active backend.
     *
     * @return Current backend
     */
    public GPUBackend getBackend() {
        return backend;
    }

    // ========== Vector Operations ==========

    /**
     * Vector addition: result[i] = a[i] + b[i]
     *
     * @param a First vector
     * @param b Second vector (must be same length as a)
     * @return Result vector
     * @throws IllegalArgumentException if vectors have different lengths
     */
    public float[] vectorAdd(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }

        if (!isGPUAvailable()) {
            return vectorAddCPU(a, b);
        }

        try {
            return vectorAddGPU(a, b);
        } catch (Exception e) {
            log.warn("GPU vectorAdd failed, falling back to CPU: {}", e.getMessage());
            return vectorAddCPU(a, b);
        }
    }

    /**
     * SAXPY: result[i] = alpha * x[i] + y[i]
     *
     * @param alpha Scalar multiplier
     * @param x     First vector
     * @param y     Second vector (must be same length as x)
     * @return Result vector
     * @throws IllegalArgumentException if vectors have different lengths
     */
    public float[] saxpy(float alpha, float[] x, float[] y) {
        if (x.length != y.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }

        if (!isGPUAvailable()) {
            return saxpyCPU(alpha, x, y);
        }

        try {
            return saxpyGPU(alpha, x, y);
        } catch (Exception e) {
            log.warn("GPU saxpy failed, falling back to CPU: {}", e.getMessage());
            return saxpyCPU(alpha, x, y);
        }
    }

    /**
     * Scale vector: result[i] = data[i] * scale
     *
     * @param data  Input vector
     * @param scale Scalar multiplier
     * @return Scaled vector
     */
    public float[] scale(float[] data, float scale) {
        if (!isGPUAvailable()) {
            return scaleCPU(data, scale);
        }

        try {
            return scaleGPU(data, scale);
        } catch (Exception e) {
            log.warn("GPU scale failed, falling back to CPU: {}", e.getMessage());
            return scaleCPU(data, scale);
        }
    }

    // ========== Reduction Operations ==========

    /**
     * Compute sum of all elements.
     *
     * @param data Input array
     * @return Sum of all elements
     */
    public float sum(float[] data) {
        // For now, CPU implementation (GPU reduction needs multiple passes)
        float sum = 0;
        for (float v : data) {
            sum += v;
        }
        return sum;
    }

    /**
     * Find maximum value.
     *
     * @param data Input array
     * @return Maximum value
     */
    public float max(float[] data) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : data) {
            if (v > max) max = v;
        }
        return max;
    }

    /**
     * Find minimum value.
     *
     * @param data Input array
     * @return Minimum value
     */
    public float min(float[] data) {
        float min = Float.POSITIVE_INFINITY;
        for (float v : data) {
            if (v < min) min = v;
        }
        return min;
    }

    // ========== Custom Operations ==========

    /**
     * Create a custom compute operation.
     *
     * @param name       Operation name (for logging)
     * @param source     Kernel source code
     * @param entryPoint Kernel entry point function name
     * @return Compute operation builder
     * @throws ComputeKernel.KernelCompilationException if compilation fails
     */
    public ComputeOperation createOperation(String name, String source, String entryPoint)
            throws ComputeKernel.KernelCompilationException {
        if (!isGPUAvailable()) {
            throw new IllegalStateException("GPU not available for custom operations");
        }
        return new ComputeOperation(name, source, entryPoint);
    }

    // ========== GPU Implementations ==========

    private float[] vectorAddGPU(float[] a, float[] b) throws Exception {
        ensureVectorAddKernel();
        int size = a.length;

        try (var bufferA = OpenCLBuffer.createWithData(a, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferB = OpenCLBuffer.createWithData(b, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("vectorAdd")) {

            kernel.compile(vectorAddSource, "vectorAdd");
            kernel.setBufferArg(0, bufferA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, size);

            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            bufferResult.download(result);
            return result;
        }
    }

    private float[] saxpyGPU(float alpha, float[] x, float[] y) throws Exception {
        ensureSaxpyKernel();
        int size = x.length;

        try (var bufferX = OpenCLBuffer.createWithData(x, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferY = OpenCLBuffer.createWithData(y, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferResult = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("saxpy")) {

            kernel.compile(saxpySource, "saxpy");
            kernel.setBufferArg(0, bufferX, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferY, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufferResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setFloatArg(3, alpha);
            kernel.setIntArg(4, size);

            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            bufferResult.download(result);
            return result;
        }
    }

    private float[] scaleGPU(float[] data, float scale) throws Exception {
        ensureTransformKernel();
        int size = data.length;

        try (var bufferIn = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_ONLY);
             var bufferOut = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var kernel = OpenCLKernel.create("scale")) {

            kernel.compile(transformSource, "scale");
            kernel.setBufferArg(0, bufferIn, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufferOut, ComputeKernel.BufferAccess.WRITE);
            kernel.setFloatArg(2, scale);
            kernel.setIntArg(3, size);

            kernel.execute(size);
            kernel.finish();

            var result = new float[size];
            bufferOut.download(result);
            return result;
        }
    }

    // ========== CPU Fallback Implementations ==========

    private float[] vectorAddCPU(float[] a, float[] b) {
        var result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    private float[] saxpyCPU(float alpha, float[] x, float[] y) {
        var result = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = alpha * x[i] + y[i];
        }
        return result;
    }

    private float[] scaleCPU(float[] data, float scale) {
        var result = new float[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] * scale;
        }
        return result;
    }

    // ========== Kernel Loading ==========

    private void ensureVectorAddKernel() {
        if (vectorAddSource == null) {
            vectorAddSource = KernelLoader.loadOpenCLKernel("vector_add");
        }
    }

    private void ensureSaxpyKernel() {
        if (saxpySource == null) {
            saxpySource = KernelLoader.loadOpenCLKernel("saxpy");
        }
    }

    private void ensureTransformKernel() {
        if (transformSource == null) {
            transformSource = KernelLoader.loadOpenCLKernel("transform");
        }
    }

    /**
     * Reset for testing.
     */
    public static void testReset() {
        synchronized (LOCK) {
            INSTANCE = null;
        }
        BackendSelector.testReset();
    }

    // ========== Custom Operation Builder ==========

    /**
     * Builder for custom compute operations.
     */
    public static class ComputeOperation implements AutoCloseable {
        private final OpenCLKernel kernel;
        private final java.util.Map<Integer, OpenCLBuffer> buffers = new java.util.HashMap<>();
        private int outputIndex = -1;
        private int outputSize = 0;

        ComputeOperation(String name, String source, String entryPoint)
                throws ComputeKernel.KernelCompilationException {
            this.kernel = OpenCLKernel.create(name);
            this.kernel.compile(source, entryPoint);
        }

        /**
         * Set input buffer at argument index.
         */
        public ComputeOperation setInput(int index, float[] data) {
            var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_ONLY);
            buffers.put(index, buffer);
            kernel.setBufferArg(index, buffer, ComputeKernel.BufferAccess.READ);
            return this;
        }

        /**
         * Set output buffer at argument index.
         */
        public ComputeOperation setOutput(int index, int size) {
            var buffer = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.WRITE_ONLY);
            buffers.put(index, buffer);
            kernel.setBufferArg(index, buffer, ComputeKernel.BufferAccess.WRITE);
            this.outputIndex = index;
            this.outputSize = size;
            return this;
        }

        /**
         * Set float argument.
         */
        public ComputeOperation setArg(int index, float value) {
            kernel.setFloatArg(index, value);
            return this;
        }

        /**
         * Set int argument.
         */
        public ComputeOperation setArg(int index, int value) {
            kernel.setIntArg(index, value);
            return this;
        }

        /**
         * Execute and return output.
         */
        public float[] execute(int workSize) throws ComputeKernel.KernelExecutionException {
            if (outputIndex < 0) {
                throw new IllegalStateException("No output buffer set");
            }

            kernel.execute(workSize);
            kernel.finish();

            var result = new float[outputSize];
            buffers.get(outputIndex).download(result);
            return result;
        }

        @Override
        public void close() {
            kernel.close();
            for (var buffer : buffers.values()) {
                buffer.close();
            }
            buffers.clear();
        }
    }
}
