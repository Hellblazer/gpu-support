/**
 * GPU compute infrastructure for cross-platform GPU acceleration.
 *
 * <h2>Overview</h2>
 * <p>This package provides a unified API for GPU compute operations supporting
 * OpenCL and Metal backends with automatic fallback to CPU when GPU is unavailable.
 *
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.GPUBackend} - Backend enum (METAL, OPENCL, CPU_FALLBACK)</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.BackendSelector} - Automatic backend selection</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.ComputeKernel} - Kernel interface</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.GPUBuffer} - Buffer interface</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.KernelLoader} - Kernel source loading</li>
 * </ul>
 *
 * <h2>OpenCL Implementation</h2>
 * <p>The {@code opencl} subpackage provides OpenCL-specific implementations:
 * <ul>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext} - Singleton context manager</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel} - Kernel compilation and execution</li>
 *   <li>{@link com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer} - GPU buffer management</li>
 * </ul>
 *
 * <h2>Kernel Resource Conventions</h2>
 * <p>Kernels should be placed in classpath resources following these conventions:
 * <table border="1">
 *   <tr><th>Backend</th><th>Path Convention</th><th>Example</th></tr>
 *   <tr><td>OpenCL</td><td>{@code kernels/opencl/{name}.cl}</td><td>{@code kernels/opencl/vector_add.cl}</td></tr>
 *   <tr><td>Metal</td><td>{@code kernels/metal/{name}.metal}</td><td>{@code kernels/metal/vector_add.metal}</td></tr>
 *   <tr><td>Test</td><td>{@code kernels/{name}.cl}</td><td>{@code kernels/vector_add.cl}</td></tr>
 * </table>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code GPU_BACKEND} - Force backend: "metal", "opencl", or "cpu"</li>
 *   <li>{@code GPU_DISABLE} - Disable GPU: "true" or "1"</li>
 *   <li>{@code gpu.disable} - System property to disable GPU</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Load and execute a kernel
 * var source = KernelLoader.loadOpenCLKernel("vector_add");
 *
 * try (var kernel = OpenCLKernel.create("vectorAdd");
 *      var bufferA = OpenCLBuffer.createWithData(dataA, READ_ONLY);
 *      var bufferB = OpenCLBuffer.createWithData(dataB, READ_ONLY);
 *      var bufferResult = OpenCLBuffer.create(size, WRITE_ONLY)) {
 *
 *     kernel.compile(source, "vectorAdd");
 *     kernel.setBufferArg(0, bufferA, READ);
 *     kernel.setBufferArg(1, bufferB, READ);
 *     kernel.setBufferArg(2, bufferResult, WRITE);
 *     kernel.setIntArg(3, size);
 *
 *     kernel.execute(size);
 *     kernel.finish();
 *
 *     bufferResult.download(result);
 * }
 * }</pre>
 *
 * @see com.hellblazer.luciferase.resource.compute.opencl
 */
package com.hellblazer.luciferase.resource.compute;
