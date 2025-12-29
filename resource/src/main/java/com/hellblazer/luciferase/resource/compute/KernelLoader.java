package com.hellblazer.luciferase.resource.compute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for loading GPU kernel source code from classpath resources.
 *
 * <p>Provides caching to avoid repeated I/O operations and convenience methods
 * for loading kernels following standard path conventions.
 *
 * <h3>Path Conventions</h3>
 * <ul>
 *   <li>OpenCL: {@code kernels/opencl/{name}.cl}</li>
 *   <li>Metal: {@code kernels/metal/{name}.metal}</li>
 *   <li>Generic: {@code kernels/{name}.cl} (for test kernels)</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Load OpenCL kernel following convention
 * String source = KernelLoader.loadOpenCLKernel("vector_add");
 * // Loads from: kernels/opencl/vector_add.cl
 *
 * // Load from explicit path
 * String source = KernelLoader.loadKernel("kernels/my_kernel.cl");
 *
 * // Load from test convention (flat structure)
 * String source = KernelLoader.loadTestKernel("vector_add");
 * // Loads from: kernels/vector_add.cl
 * }</pre>
 *
 * @see com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel
 */
public class KernelLoader {

    private static final Logger log = LoggerFactory.getLogger(KernelLoader.class);

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Load an OpenCL kernel from the standard location.
     *
     * <p>Follows convention: {@code kernels/opencl/{kernelName}.cl}
     *
     * @param kernelName Kernel name without path or extension (e.g., "vector_add")
     * @return Kernel source code
     * @throws KernelLoadException if kernel cannot be loaded
     */
    public static String loadOpenCLKernel(String kernelName) {
        return loadKernel("kernels/opencl/" + kernelName + ".cl");
    }

    /**
     * Load a Metal kernel from the standard location.
     *
     * <p>Follows convention: {@code kernels/metal/{kernelName}.metal}
     *
     * @param kernelName Kernel name without path or extension (e.g., "vector_add")
     * @return Kernel source code
     * @throws KernelLoadException if kernel cannot be loaded
     */
    public static String loadMetalKernel(String kernelName) {
        return loadKernel("kernels/metal/" + kernelName + ".metal");
    }

    /**
     * Load a test kernel from the flat test location.
     *
     * <p>Follows convention: {@code kernels/{kernelName}.cl}
     *
     * <p>This is the convention used by gpu-test-framework test kernels.
     *
     * @param kernelName Kernel name without path or extension (e.g., "vector_add")
     * @return Kernel source code
     * @throws KernelLoadException if kernel cannot be loaded
     */
    public static String loadTestKernel(String kernelName) {
        return loadKernel("kernels/" + kernelName + ".cl");
    }

    /**
     * Load a kernel from an explicit classpath resource path.
     *
     * <p>Results are cached; subsequent calls for the same path return cached content.
     *
     * @param resourcePath Full resource path (e.g., "kernels/opencl/vector_add.cl")
     * @return Kernel source code
     * @throws KernelLoadException if kernel cannot be loaded
     */
    public static String loadKernel(String resourcePath) {
        return cache.computeIfAbsent(resourcePath, KernelLoader::doLoadKernel);
    }

    /**
     * Check if a kernel resource exists.
     *
     * @param resourcePath Full resource path
     * @return true if the resource exists
     */
    public static boolean kernelExists(String resourcePath) {
        // Check cache first
        if (cache.containsKey(resourcePath)) {
            return true;
        }

        // Check classpath
        try (var is = getResourceStream(resourcePath)) {
            return is != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clear the kernel cache.
     *
     * <p><b>WARNING</b>: This method is for TESTING ONLY.
     */
    public static void testClearCache() {
        cache.clear();
        log.debug("Kernel cache cleared");
    }

    /**
     * Get the number of cached kernels.
     *
     * @return Number of cached kernel sources
     */
    public static int getCacheSize() {
        return cache.size();
    }

    private static String doLoadKernel(String resourcePath) {
        log.debug("Loading kernel: {}", resourcePath);

        try (var is = getResourceStream(resourcePath)) {
            if (is == null) {
                throw new KernelLoadException("Kernel not found: " + resourcePath);
            }

            var source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Loaded kernel {} ({} bytes)", resourcePath, source.length());
            return source;

        } catch (IOException e) {
            throw new KernelLoadException("Failed to read kernel: " + resourcePath, e);
        }
    }

    private static InputStream getResourceStream(String resourcePath) {
        // Try context classloader first (works in most frameworks)
        var contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            var is = contextLoader.getResourceAsStream(resourcePath);
            if (is != null) {
                return is;
            }
        }

        // Fall back to class's classloader
        return KernelLoader.class.getClassLoader().getResourceAsStream(resourcePath);
    }

    /**
     * Exception thrown when kernel loading fails.
     */
    public static class KernelLoadException extends RuntimeException {
        public KernelLoadException(String message) {
            super(message);
        }

        public KernelLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
