package com.hellblazer.luciferase.resource.compute;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies GPU exceptions to determine appropriate error handling strategy.
 *
 * <p>Exception categories:
 * <ul>
 *   <li><b>Programming errors</b>: Bugs that should fail fast (never fallback)</li>
 *   <li><b>Recoverable errors</b>: Transient issues that can fallback to CPU</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * try {
 *     return processGPU(input);
 * } catch (Exception e) {
 *     if (GPUErrorClassifier.isProgrammingError(e)) {
 *         throw new IllegalStateException("GPU programming error", e);
 *     }
 *     log.warn("Recoverable GPU error, falling back: {}",
 *              GPUErrorClassifier.getErrorCategory(e));
 *     return processCPU(input);
 * }
 * }</pre>
 *
 * @see ComputeKernel.KernelCompilationException
 * @see ComputeKernel.KernelExecutionException
 */
public final class GPUErrorClassifier {

    private GPUErrorClassifier() {} // Utility class

    // OpenCL error codes that indicate programming errors
    private static final Set<Integer> PROGRAMMING_ERROR_CODES = Set.of(
            -11,  // CL_BUILD_PROGRAM_FAILURE
            -44,  // CL_INVALID_PROGRAM
            -45,  // CL_INVALID_PROGRAM_EXECUTABLE
            -46,  // CL_INVALID_KERNEL_NAME
            -47,  // CL_INVALID_KERNEL_DEFINITION
            -48,  // CL_INVALID_KERNEL
            -49,  // CL_INVALID_ARG_INDEX
            -50,  // CL_INVALID_ARG_VALUE
            -51,  // CL_INVALID_ARG_SIZE
            -52,  // CL_INVALID_KERNEL_ARGS
            -53,  // CL_INVALID_WORK_DIMENSION
            -54,  // CL_INVALID_WORK_GROUP_SIZE
            -55,  // CL_INVALID_WORK_ITEM_SIZE
            -56,  // CL_INVALID_GLOBAL_OFFSET
            -57,  // CL_INVALID_EVENT_WAIT_LIST
            -58,  // CL_INVALID_EVENT
            -30,  // CL_INVALID_VALUE
            -33,  // CL_INVALID_DEVICE
            -34,  // CL_INVALID_CONTEXT
            -35,  // CL_INVALID_QUEUE_PROPERTIES
            -36,  // CL_INVALID_COMMAND_QUEUE
            -37,  // CL_INVALID_HOST_PTR
            -38,  // CL_INVALID_MEM_OBJECT
            -63   // CL_INVALID_GLOBAL_WORK_SIZE
    );

    // OpenCL error codes that indicate recoverable errors
    private static final Set<Integer> RECOVERABLE_ERROR_CODES = Set.of(
            -4,   // CL_MEM_OBJECT_ALLOCATION_FAILURE
            -5,   // CL_OUT_OF_RESOURCES
            -6,   // CL_OUT_OF_HOST_MEMORY
            -59   // CL_INVALID_OPERATION (context-dependent)
    );

    // Pattern to extract error codes from exception messages
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("error code[:\\s]+(-?\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * Determine if an exception represents a programming error.
     * Programming errors should fail fast and not be masked by CPU fallback.
     *
     * @param t the throwable to classify
     * @return true if this is a programming error that should be rethrown
     */
    public static boolean isProgrammingError(Throwable t) {
        if (t == null) {
            return false;
        }

        // Check exception type first
        if (t instanceof IllegalStateException ||
            t instanceof IllegalArgumentException ||
            t instanceof NullPointerException ||
            t instanceof AssertionError ||
            t instanceof ComputeKernel.KernelCompilationException) {
            return true;
        }

        // Check for RuntimeException wrapping programming errors
        if (t instanceof RuntimeException && t.getCause() != null && t.getCause() != t) {
            if (isProgrammingError(t.getCause())) {
                return true;
            }
        }

        // Extract and check OpenCL error code from message
        var errorCode = extractOpenCLErrorCode(t);
        if (errorCode != 0 && PROGRAMMING_ERROR_CODES.contains(errorCode)) {
            return true;
        }

        // Check cause chain
        if (t.getCause() != null && t.getCause() != t) {
            return isProgrammingError(t.getCause());
        }

        return false;
    }

    /**
     * Determine if an exception represents a recoverable error.
     * Recoverable errors can legitimately fall back to CPU processing.
     *
     * @param t the throwable to classify
     * @return true if this error can be recovered by CPU fallback
     */
    public static boolean isRecoverable(Throwable t) {
        if (t == null) {
            return false;
        }

        // Programming errors are never recoverable
        if (isProgrammingError(t)) {
            return false;
        }

        // Check for specific recoverable error codes
        var errorCode = extractOpenCLErrorCode(t);
        if (errorCode != 0 && RECOVERABLE_ERROR_CODES.contains(errorCode)) {
            return true;
        }

        // OutOfMemoryError related to GPU is recoverable
        if (t instanceof OutOfMemoryError) {
            var message = t.getMessage();
            if (message != null &&
                (message.contains("GPU") || message.contains("OpenCL") ||
                 message.contains("buffer"))) {
                return true;
            }
        }

        // KernelExecutionException without programming error code is recoverable
        if (t instanceof ComputeKernel.KernelExecutionException) {
            return true;
        }

        // Check cause chain
        if (t.getCause() != null && t.getCause() != t) {
            return isRecoverable(t.getCause());
        }

        // Default: unknown errors are recoverable (conservative approach)
        return true;
    }

    /**
     * Get a human-readable description of the error category.
     *
     * @param t the throwable to describe
     * @return descriptive string for logging
     */
    public static String getErrorCategory(Throwable t) {
        if (t == null) {
            return "null exception";
        }

        var errorCode = extractOpenCLErrorCode(t);
        var codeDesc = errorCode != 0 ? " (CL error " + errorCode + ")" : "";

        if (isProgrammingError(t)) {
            return "PROGRAMMING ERROR" + codeDesc + ": " + t.getClass().getSimpleName();
        }

        if (errorCode != 0 && RECOVERABLE_ERROR_CODES.contains(errorCode)) {
            return switch (errorCode) {
                case -4 -> "MEMORY ALLOCATION FAILURE" + codeDesc;
                case -5 -> "OUT OF GPU RESOURCES" + codeDesc;
                case -6 -> "OUT OF HOST MEMORY" + codeDesc;
                case -59 -> "INVALID OPERATION" + codeDesc;
                default -> "RECOVERABLE ERROR" + codeDesc;
            };
        }

        return "UNKNOWN ERROR" + codeDesc + ": " + t.getClass().getSimpleName();
    }

    /**
     * Get the OpenCL error code name for logging.
     *
     * @param errorCode the OpenCL error code
     * @return human-readable error name
     */
    public static String getOpenCLErrorName(int errorCode) {
        return switch (errorCode) {
            case 0 -> "CL_SUCCESS";
            case -4 -> "CL_MEM_OBJECT_ALLOCATION_FAILURE";
            case -5 -> "CL_OUT_OF_RESOURCES";
            case -6 -> "CL_OUT_OF_HOST_MEMORY";
            case -11 -> "CL_BUILD_PROGRAM_FAILURE";
            case -30 -> "CL_INVALID_VALUE";
            case -33 -> "CL_INVALID_DEVICE";
            case -34 -> "CL_INVALID_CONTEXT";
            case -35 -> "CL_INVALID_QUEUE_PROPERTIES";
            case -36 -> "CL_INVALID_COMMAND_QUEUE";
            case -37 -> "CL_INVALID_HOST_PTR";
            case -38 -> "CL_INVALID_MEM_OBJECT";
            case -44 -> "CL_INVALID_PROGRAM";
            case -45 -> "CL_INVALID_PROGRAM_EXECUTABLE";
            case -46 -> "CL_INVALID_KERNEL_NAME";
            case -47 -> "CL_INVALID_KERNEL_DEFINITION";
            case -48 -> "CL_INVALID_KERNEL";
            case -49 -> "CL_INVALID_ARG_INDEX";
            case -50 -> "CL_INVALID_ARG_VALUE";
            case -51 -> "CL_INVALID_ARG_SIZE";
            case -52 -> "CL_INVALID_KERNEL_ARGS";
            case -53 -> "CL_INVALID_WORK_DIMENSION";
            case -54 -> "CL_INVALID_WORK_GROUP_SIZE";
            case -55 -> "CL_INVALID_WORK_ITEM_SIZE";
            case -56 -> "CL_INVALID_GLOBAL_OFFSET";
            case -57 -> "CL_INVALID_EVENT_WAIT_LIST";
            case -58 -> "CL_INVALID_EVENT";
            case -59 -> "CL_INVALID_OPERATION";
            case -63 -> "CL_INVALID_GLOBAL_WORK_SIZE";
            default -> "UNKNOWN_CL_ERROR_" + errorCode;
        };
    }

    /**
     * Extract OpenCL error code from exception message.
     * Searches the exception chain for patterns like "error code: -49".
     *
     * @param t the throwable to search
     * @return the error code, or 0 if not found
     */
    static int extractOpenCLErrorCode(Throwable t) {
        var current = t;
        while (current != null) {
            var message = current.getMessage();
            if (message != null) {
                var matcher = ERROR_CODE_PATTERN.matcher(message);
                if (matcher.find()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException e) {
                        // Continue searching
                    }
                }
            }
            current = (current.getCause() != current) ? current.getCause() : null;
        }
        return 0;
    }
}
