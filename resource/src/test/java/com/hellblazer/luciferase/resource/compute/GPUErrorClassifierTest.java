package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GPUErrorClassifier.
 * Tests error classification, code extraction, and edge cases.
 */
class GPUErrorClassifierTest {

    // --- Programming Error Tests ---

    @Test
    void testNullInputReturnsFalse() {
        assertFalse(GPUErrorClassifier.isProgrammingError(null));
        assertFalse(GPUErrorClassifier.isRecoverable(null));
    }

    @Test
    void testIllegalStateExceptionIsProgrammingError() {
        var ex = new IllegalStateException("test");
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testIllegalArgumentExceptionIsProgrammingError() {
        var ex = new IllegalArgumentException("test");
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testNullPointerExceptionIsProgrammingError() {
        var ex = new NullPointerException("test");
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testAssertionErrorIsProgrammingError() {
        var ex = new AssertionError("test");
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testKernelCompilationExceptionIsProgrammingError() {
        var ex = new ComputeKernel.KernelCompilationException("compilation failed");
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testProgrammingErrorCodes() {
        int[] programmingCodes = {-11, -44, -45, -46, -47, -48, -49, -50, -51, -52,
                                   -53, -54, -55, -56, -57, -58, -30, -33, -34, -35,
                                   -36, -37, -38, -63};
        for (int errorCode : programmingCodes) {
            var ex = new RuntimeException("OpenCL error code: " + errorCode);
            assertTrue(GPUErrorClassifier.isProgrammingError(ex),
                    "Error code " + errorCode + " should be programming error");
        }
    }

    // --- Recoverable Error Tests ---

    @Test
    void testKernelExecutionExceptionIsRecoverable() {
        var ex = new ComputeKernel.KernelExecutionException("execution failed");
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testRecoverableErrorCodes() {
        int[] recoverableCodes = {-4, -5, -6, -59};
        for (int errorCode : recoverableCodes) {
            var ex = new RuntimeException("OpenCL error code: " + errorCode);
            assertFalse(GPUErrorClassifier.isProgrammingError(ex),
                    "Error code " + errorCode + " should not be programming error");
            assertTrue(GPUErrorClassifier.isRecoverable(ex),
                    "Error code " + errorCode + " should be recoverable");
        }
    }

    @Test
    void testGPUOutOfMemoryIsRecoverable() {
        var ex = new OutOfMemoryError("GPU buffer allocation failed");
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testOpenCLOutOfMemoryIsRecoverable() {
        var ex = new OutOfMemoryError("OpenCL memory exhausted");
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testUnknownErrorIsRecoverable() {
        // Unknown errors default to recoverable (conservative approach)
        var ex = new RuntimeException("some unknown error");
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testUnknownErrorCodeIsRecoverable() {
        // Unknown error code that's not in either set
        var ex = new RuntimeException("OpenCL error code: -999");
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    // --- Exception Chain Tests ---

    @Test
    void testWrappedProgrammingErrorDetected() {
        var cause = new IllegalArgumentException("inner");
        var ex = new RuntimeException("wrapper", cause);
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
    }

    @Test
    void testDeepChainProgrammingErrorDetected() {
        var deepCause = new NullPointerException("deep");
        var midCause = new RuntimeException("mid", deepCause);
        var ex = new RuntimeException("outer", midCause);
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
    }

    @Test
    void testChainWithErrorCodeDetected() {
        var cause = new RuntimeException("OpenCL error code: -48");
        var ex = new RuntimeException("wrapper", cause);
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
    }

    @Test
    void testSelfReferencingCauseHandled() {
        // Create exception with self-referencing cause (shouldn't infinite loop)
        var ex = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        // Should complete without stack overflow
        assertFalse(GPUErrorClassifier.isProgrammingError(ex));
    }

    // --- Error Code Extraction Tests ---

    @Test
    void testExtractErrorCodeFromMessage() {
        assertEquals(-48, GPUErrorClassifier.extractOpenCLErrorCode(
                new RuntimeException("error code: -48")));
        assertEquals(-5, GPUErrorClassifier.extractOpenCLErrorCode(
                new RuntimeException("Error Code -5")));
        assertEquals(-11, GPUErrorClassifier.extractOpenCLErrorCode(
                new RuntimeException("OpenCL error code: -11 occurred")));
    }

    @Test
    void testExtractErrorCodeReturnsZeroWhenNotFound() {
        assertEquals(0, GPUErrorClassifier.extractOpenCLErrorCode(
                new RuntimeException("no error code here")));
        assertEquals(0, GPUErrorClassifier.extractOpenCLErrorCode(
                new RuntimeException((String) null)));
    }

    // --- Error Category Description Tests ---

    @Test
    void testNullExceptionCategory() {
        assertEquals("null exception", GPUErrorClassifier.getErrorCategory(null));
    }

    @Test
    void testProgrammingErrorCategory() {
        var category = GPUErrorClassifier.getErrorCategory(new IllegalStateException("test"));
        assertTrue(category.contains("PROGRAMMING ERROR"));
    }

    @Test
    void testMemoryAllocationFailureCategory() {
        var category = GPUErrorClassifier.getErrorCategory(
                new RuntimeException("error code: -4"));
        assertTrue(category.contains("MEMORY ALLOCATION FAILURE"));
    }

    @Test
    void testOutOfResourcesCategory() {
        var category = GPUErrorClassifier.getErrorCategory(
                new RuntimeException("error code: -5"));
        assertTrue(category.contains("OUT OF GPU RESOURCES"));
    }

    @Test
    void testOutOfHostMemoryCategory() {
        var category = GPUErrorClassifier.getErrorCategory(
                new RuntimeException("error code: -6"));
        assertTrue(category.contains("OUT OF HOST MEMORY"));
    }

    @Test
    void testUnknownErrorCategory() {
        var category = GPUErrorClassifier.getErrorCategory(
                new RuntimeException("something unknown"));
        assertTrue(category.contains("UNKNOWN ERROR"));
    }

    // --- Error Name Tests ---

    @Test
    void testGetOpenCLErrorName() {
        assertEquals("CL_SUCCESS", GPUErrorClassifier.getOpenCLErrorName(0));
        assertEquals("CL_BUILD_PROGRAM_FAILURE", GPUErrorClassifier.getOpenCLErrorName(-11));
        assertEquals("CL_INVALID_KERNEL", GPUErrorClassifier.getOpenCLErrorName(-48));
        assertEquals("CL_OUT_OF_RESOURCES", GPUErrorClassifier.getOpenCLErrorName(-5));
        assertEquals("CL_OUT_OF_HOST_MEMORY", GPUErrorClassifier.getOpenCLErrorName(-6));
    }

    @Test
    void testGetOpenCLErrorNameUnknown() {
        var name = GPUErrorClassifier.getOpenCLErrorName(-999);
        assertTrue(name.contains("UNKNOWN"));
        assertTrue(name.contains("-999"));
    }

    // --- Edge Cases ---

    @Test
    void testNonGPUOutOfMemoryIsRecoverable() {
        // Regular OOM without GPU keywords
        var ex = new OutOfMemoryError("Java heap space");
        // Still recoverable by default conservative approach
        assertTrue(GPUErrorClassifier.isRecoverable(ex));
    }

    @Test
    void testProgrammingErrorCodeOverridesRecoverable() {
        // A KernelExecutionException with programming error code in message
        var ex = new ComputeKernel.KernelExecutionException("error code: -48");
        // The error code takes precedence
        assertTrue(GPUErrorClassifier.isProgrammingError(ex));
        assertFalse(GPUErrorClassifier.isRecoverable(ex));
    }
}
