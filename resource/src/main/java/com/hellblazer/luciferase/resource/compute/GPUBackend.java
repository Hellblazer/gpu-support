package com.hellblazer.luciferase.resource.compute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opencl.CL10.*;

/**
 * Supported GPU compute backends.
 *
 * <p>Priority ordering (higher = preferred):
 * <ol>
 *   <li>METAL (100) - macOS only, highest performance</li>
 *   <li>OPENCL (90) - cross-platform</li>
 *   <li>CPU_FALLBACK (10) - always available</li>
 * </ol>
 *
 * <p>Use {@link BackendSelector#getOptimalBackend()} for automatic selection
 * based on platform availability.
 *
 * @see BackendSelector
 */
public enum GPUBackend {
    /**
     * Metal 3 (macOS only, highest performance).
     */
    METAL("Metal", 100, true),

    /**
     * OpenCL 1.2+ (cross-platform).
     */
    OPENCL("OpenCL", 90, true),

    /**
     * CPU fallback (no GPU required).
     */
    CPU_FALLBACK("CPU Fallback", 10, false);

    private final String displayName;
    private final int priority;
    private final boolean isGPU;

    GPUBackend(String displayName, int priority, boolean isGPU) {
        this.displayName = displayName;
        this.priority = priority;
        this.isGPU = isGPU;
    }

    /**
     * Get human-readable display name.
     *
     * @return Display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get priority for automatic backend selection.
     * Higher values are preferred.
     *
     * @return Priority value
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Check if this is a GPU backend (vs CPU fallback).
     *
     * @return true if GPU-accelerated
     */
    public boolean isGPU() {
        return isGPU;
    }

    /**
     * Check if this backend is available on the current platform.
     *
     * <p>Availability checks are cached after first call.
     *
     * @return true if this backend can be used
     */
    public boolean isAvailable() {
        return switch (this) {
            case METAL -> MetalDetector.isAvailable();
            case OPENCL -> OpenCLDetector.isAvailable();
            case CPU_FALLBACK -> true;
        };
    }

    /**
     * Reset cached availability state for testing.
     * Forces re-detection on next isAvailable() call.
     *
     * <p><b>WARNING</b>: This method is for TESTING ONLY.
     */
    public static void testResetAvailability() {
        MetalDetector.reset();
        OpenCLDetector.reset();
    }

    /**
     * Metal availability detector.
     * Metal is only available on macOS.
     */
    private static final class MetalDetector {
        private static final Logger log = LoggerFactory.getLogger(MetalDetector.class);
        private static volatile Boolean available;

        static boolean isAvailable() {
            if (available == null) {
                synchronized (MetalDetector.class) {
                    if (available == null) {
                        available = detectMetal();
                    }
                }
            }
            return available;
        }

        static void reset() {
            synchronized (MetalDetector.class) {
                available = null;
            }
        }

        private static boolean detectMetal() {
            var os = System.getProperty("os.name", "").toLowerCase();
            var isMacOS = os.contains("mac");
            if (!isMacOS) {
                log.debug("Metal not available - not macOS (os.name={})", os);
                return false;
            }
            // Note: Full Metal detection would require native calls
            // For now, assume Metal available on macOS 10.14+
            log.debug("Metal potentially available on macOS");
            return true;
        }
    }

    /**
     * OpenCL availability detector.
     * Uses lightweight probe that doesn't require full context initialization.
     */
    private static final class OpenCLDetector {
        private static final Logger log = LoggerFactory.getLogger(OpenCLDetector.class);
        private static volatile Boolean available;

        static boolean isAvailable() {
            if (available == null) {
                synchronized (OpenCLDetector.class) {
                    if (available == null) {
                        available = detectOpenCL();
                    }
                }
            }
            return available;
        }

        static void reset() {
            synchronized (OpenCLDetector.class) {
                available = null;
            }
        }

        private static boolean detectOpenCL() {
            // Check if disabled via system property
            if (Boolean.getBoolean("gpu.disable") ||
                Boolean.getBoolean("luciferase.gpu.disable") ||
                Boolean.getBoolean("art.gpu.disable")) {
                log.debug("OpenCL disabled via system property");
                return false;
            }

            try {
                // Try to enumerate platforms using MemoryStack
                // NOTE: We deliberately do NOT call CL.create() here because it causes
                // SIGSEGV crashes in Apple's GPU drivers when running in forked JVM processes
                // (like Maven Surefire). Instead, we directly call clGetPlatformIDs which
                // works correctly and detects OpenCL availability.
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var numPlatforms = stack.mallocInt(1);
                    var result = clGetPlatformIDs((org.lwjgl.PointerBuffer) null, numPlatforms);

                    if (result != CL_SUCCESS) {
                        log.debug("OpenCL not available - clGetPlatformIDs returned {}", result);
                        return false;
                    }

                    if (numPlatforms.get(0) == 0) {
                        log.debug("OpenCL not available - no platforms found");
                        return false;
                    }

                    log.debug("OpenCL available - found {} platform(s)", numPlatforms.get(0));
                    return true;
                }

            } catch (UnsatisfiedLinkError e) {
                log.debug("OpenCL not available - native library not found: {}", e.getMessage());
                return false;
            } catch (NoClassDefFoundError e) {
                log.debug("OpenCL not available - LWJGL OpenCL classes not found: {}", e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("OpenCL not available - detection failed: {}", e.getMessage());
                return false;
            }
        }
    }
}
