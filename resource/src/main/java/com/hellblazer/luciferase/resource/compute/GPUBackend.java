package com.hellblazer.luciferase.resource.compute;

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
}
