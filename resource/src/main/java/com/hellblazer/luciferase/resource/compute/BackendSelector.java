package com.hellblazer.luciferase.resource.compute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;

/**
 * Automatic GPU backend selection.
 *
 * <p>Selects optimal backend based on platform, availability, and performance characteristics.
 *
 * <p>Priority order:
 * <ol>
 *   <li>Metal (macOS only, highest performance)</li>
 *   <li>OpenCL (cross-platform)</li>
 *   <li>CPU fallback (always available)</li>
 * </ol>
 *
 * <h3>Environment Variables</h3>
 * <ul>
 *   <li>{@code GPU_BACKEND} - Force a specific backend ("metal", "opencl", "cpu")</li>
 *   <li>{@code GPU_DISABLE} - Disable GPU and force CPU fallback ("true" or "1")</li>
 * </ul>
 *
 * <p>Legacy ART environment variables are also supported for backwards compatibility:
 * <ul>
 *   <li>{@code ART_GPU_BACKEND} - Same as GPU_BACKEND</li>
 *   <li>{@code ART_GPU_DISABLE} - Same as GPU_DISABLE</li>
 * </ul>
 *
 * @see GPUBackend
 */
public class BackendSelector {

    private static final Logger log = LoggerFactory.getLogger(BackendSelector.class);

    private static GPUBackend selectedBackend = null;
    private static boolean initialized = false;

    /**
     * Get the optimal GPU backend for the current platform.
     * Caches the result after first call.
     *
     * @return Selected backend
     */
    public static GPUBackend getOptimalBackend() {
        if (!initialized) {
            selectedBackend = selectBackend();
            initialized = true;

            log.debug("GPU Backend Selection: {}", selectedBackend.getDisplayName());
            log.debug("Platform: {}", getPlatformDescription());
            log.debug("CI Environment: {}", isCIEnvironment());
        }
        return selectedBackend;
    }

    /**
     * Select the best available backend.
     *
     * @return Selected backend
     */
    private static GPUBackend selectBackend() {
        // Check environment variables for forced selection
        var forced = getForcedBackend();
        if (forced.isPresent()) {
            var backend = forced.get();
            log.debug("Backend forced via environment: {}", backend);
            return backend;
        }

        // In CI, use CPU fallback
        if (isCIEnvironment()) {
            log.debug("CI environment detected, using CPU fallback");
            return GPUBackend.CPU_FALLBACK;
        }

        // Check if GPU is disabled
        if (isGPUDisabled()) {
            log.debug("GPU disabled via environment, using CPU fallback");
            return GPUBackend.CPU_FALLBACK;
        }

        // Select highest priority available backend
        return Arrays.stream(GPUBackend.values())
                     .filter(GPUBackend::isGPU)  // GPU backends only
                     .filter(GPUBackend::isAvailable)
                     .max(Comparator.comparingInt(GPUBackend::getPriority))
                     .orElse(GPUBackend.CPU_FALLBACK);
    }

    /**
     * Check if a specific backend is forced via environment variable.
     *
     * <p>Checks {@code GPU_BACKEND} first, then legacy {@code ART_GPU_BACKEND}.
     *
     * @return Forced backend, if any
     */
    private static Optional<GPUBackend> getForcedBackend() {
        // Check new generic env var first
        var backend = System.getenv("GPU_BACKEND");

        // Fall back to legacy ART env var
        if (backend == null) {
            backend = System.getenv("ART_GPU_BACKEND");
            if (backend != null) {
                log.debug("Using legacy ART_GPU_BACKEND env var (consider using GPU_BACKEND)");
            }
        }

        if (backend != null) {
            return switch (backend.toLowerCase()) {
                case "metal" -> Optional.of(GPUBackend.METAL);
                case "opencl" -> Optional.of(GPUBackend.OPENCL);
                case "cpu" -> Optional.of(GPUBackend.CPU_FALLBACK);
                default -> {
                    log.warn("Unknown backend specified: {}. Ignoring.", backend);
                    yield Optional.empty();
                }
            };
        }
        return Optional.empty();
    }

    /**
     * Check if GPU is disabled via environment variable.
     *
     * <p>Checks {@code GPU_DISABLE} first, then legacy {@code ART_GPU_DISABLE}.
     *
     * @return true if GPU is disabled
     */
    private static boolean isGPUDisabled() {
        // Check new generic env var first
        var disabled = System.getenv("GPU_DISABLE");

        // Fall back to legacy ART env var
        if (disabled == null) {
            disabled = System.getenv("ART_GPU_DISABLE");
            if (disabled != null) {
                log.debug("Using legacy ART_GPU_DISABLE env var (consider using GPU_DISABLE)");
            }
        }

        return "true".equalsIgnoreCase(disabled) || "1".equals(disabled);
    }

    /**
     * Check if running in a CI environment.
     *
     * @return true if CI environment detected
     */
    public static boolean isCIEnvironment() {
        return System.getenv("CI") != null ||
               System.getenv("GITHUB_ACTIONS") != null ||
               System.getenv("JENKINS_URL") != null ||
               System.getenv("GITLAB_CI") != null ||
               System.getenv("TRAVIS") != null ||
               System.getenv("CIRCLECI") != null;
    }

    /**
     * Get platform description.
     *
     * @return Platform description
     */
    public static String getPlatformDescription() {
        var os = System.getProperty("os.name");
        var arch = System.getProperty("os.arch");
        return String.format("%s %s", os, arch);
    }

    /**
     * Check if Metal is available on this platform.
     *
     * @return true if Metal is available
     */
    public static boolean isMetalAvailable() {
        return GPUBackend.METAL.isAvailable();
    }

    /**
     * Check if OpenCL is available on this platform.
     *
     * @return true if OpenCL is available
     */
    public static boolean isOpenCLAvailable() {
        return GPUBackend.OPENCL.isAvailable();
    }

    /**
     * Get environment information for debugging.
     *
     * @return Environment description
     */
    public static String getEnvironmentInfo() {
        var sb = new StringBuilder();
        sb.append("Platform: ").append(getPlatformDescription()).append("\n");
        sb.append("CI: ").append(isCIEnvironment()).append("\n");
        sb.append("Metal Available: ").append(isMetalAvailable()).append("\n");
        sb.append("OpenCL Available: ").append(isOpenCLAvailable()).append("\n");
        sb.append("Selected Backend: ").append(getOptimalBackend().getDisplayName()).append("\n");
        return sb.toString();
    }

    /**
     * Reset backend selection (for testing).
     *
     * <p><b>WARNING</b>: This method is for TESTING ONLY.
     */
    public static void testReset() {
        selectedBackend = null;
        initialized = false;
        // Also reset GPUBackend availability cache
        GPUBackend.testResetAvailability();
    }
}
