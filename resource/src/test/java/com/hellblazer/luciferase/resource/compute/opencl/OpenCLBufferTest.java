package com.hellblazer.luciferase.resource.compute.opencl;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenCLBuffer.
 * Tests require OpenCL availability - skipped if not available and in CI environments.
 *
 * <p>Note: OpenCL context is acquired once for all tests and NOT released
 * to avoid macOS SIGABRT crashes. Resources are cleaned up by OS on JVM exit.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class OpenCLBufferTest {

    private static boolean openCLAvailable;

    @BeforeAll
    static void checkOpenCL() {
        // Check if OpenCL is available without creating contexts
        // This avoids the Apple driver crash from multiple contexts
        try (var stack = MemoryStack.stackPush()) {
            var numPlatforms = stack.mallocInt(1);
            var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

            if (errcode == CL10.CL_SUCCESS && numPlatforms.get(0) > 0) {
                var platformBuffer = stack.mallocPointer(1);
                CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
                var platform = platformBuffer.get(0);

                var numDevices = stack.mallocInt(1);
                var result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, null, numDevices);

                if (result != CL10.CL_SUCCESS) {
                    result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_CPU, null, numDevices);
                }

                if (result == CL10.CL_SUCCESS && numDevices.get(0) > 0) {
                    openCLAvailable = true;
                    System.out.println("OpenCL available for OpenCLBufferTest");
                } else {
                    openCLAvailable = false;
                    System.out.println("OpenCL not available - no devices");
                }
            } else {
                openCLAvailable = false;
                System.out.println("OpenCL not available - no platforms");
            }
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(openCLAvailable, "OpenCL required for this test");
    }

    // --- Creation Tests ---

    @Test
    void testCreateBuffer() {
        try (var buffer = OpenCLBuffer.create(256, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            assertNotNull(buffer);
            assertEquals(256, buffer.size());
            assertEquals(256 * Float.BYTES, buffer.sizeInBytes());
            assertTrue(buffer.isValid());
        }
    }

    @Test
    void testCreateWithData() {
        var data = new float[]{1.0f, 2.0f, 3.0f, 4.0f};
        try (var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            assertNotNull(buffer);
            assertEquals(4, buffer.size());
            assertTrue(buffer.isValid());
        }
    }

    @Test
    void testBufferAccessModes() {
        try (var readOnly = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var writeOnly = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var readWrite = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            assertEquals(OpenCLBuffer.BufferAccess.READ_ONLY, readOnly.getAccess());
            assertEquals(OpenCLBuffer.BufferAccess.WRITE_ONLY, writeOnly.getAccess());
            assertEquals(OpenCLBuffer.BufferAccess.READ_WRITE, readWrite.getAccess());
        }
    }

    // --- Upload/Download Tests (float array) ---

    @Test
    void testUploadDownloadFloatArray() {
        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            var input = new float[]{1.0f, 2.0f, 3.0f, 4.0f};
            buffer.upload(input);

            var output = new float[4];
            buffer.download(output);

            assertArrayEquals(input, output, 0.0001f);
        }
    }

    @Test
    void testUploadPartialData() {
        try (var buffer = OpenCLBuffer.create(100, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            // Upload only 10 elements to a 100-element buffer
            var input = new float[10];
            for (int i = 0; i < 10; i++) {
                input[i] = i * 1.5f;
            }
            buffer.upload(input);

            var output = new float[10];
            buffer.download(output);

            assertArrayEquals(input, output, 0.0001f);
        }
    }

    // --- Upload/Download Tests (FloatBuffer) ---

    @Test
    void testUploadDownloadFloatBuffer() {
        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            FloatBuffer input = MemoryUtil.memAllocFloat(4);
            try {
                input.put(new float[]{5.0f, 6.0f, 7.0f, 8.0f});
                input.flip();

                buffer.upload(input);

                FloatBuffer output = MemoryUtil.memAllocFloat(4);
                try {
                    buffer.download(output);
                    output.flip();

                    assertEquals(5.0f, output.get(0), 0.0001f);
                    assertEquals(6.0f, output.get(1), 0.0001f);
                    assertEquals(7.0f, output.get(2), 0.0001f);
                    assertEquals(8.0f, output.get(3), 0.0001f);
                } finally {
                    MemoryUtil.memFree(output);
                }
            } finally {
                MemoryUtil.memFree(input);
            }
        }
    }

    // --- Validation Tests ---

    @Test
    void testUploadTooMuchDataThrows() {
        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            var tooMuchData = new float[10];
            assertThrows(IllegalArgumentException.class, () -> buffer.upload(tooMuchData));
        }
    }

    @Test
    void testDownloadTooMuchDataThrows() {
        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            var tooLargeArray = new float[10];
            assertThrows(IllegalArgumentException.class, () -> buffer.download(tooLargeArray));
        }
    }

    // --- Lifecycle Tests ---

    @Test
    void testCloseMarksInvalid() {
        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        assertTrue(buffer.isValid());

        buffer.close();
        assertFalse(buffer.isValid());
    }

    @Test
    void testDoubleCloseIsSafe() {
        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        buffer.close();
        // Second close should not throw
        buffer.close();
        assertFalse(buffer.isValid());
    }

    @Test
    void testOperationsAfterCloseThrow() {
        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        buffer.close();

        assertThrows(IllegalStateException.class, () -> buffer.upload(new float[4]));
        assertThrows(IllegalStateException.class, () -> buffer.download(new float[4]));
        assertThrows(IllegalStateException.class, buffer::getHandle);
    }

    // --- Handle Tests ---

    @Test
    void testGetHandle() {
        try (var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            long handle = buffer.getHandle();
            assertTrue(handle != 0, "Handle should be non-zero");
        }
    }

    // --- Edge Cases ---

    @Test
    void testEmptyArrayUploadDownload() {
        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            // Upload/download empty arrays should work (no-op)
            buffer.upload(new float[0]);
            buffer.download(new float[0]);
        }
    }

    @Test
    void testLargerBuffer() {
        // Test with a larger buffer (64KB of floats)
        int size = 16384;
        try (var buffer = OpenCLBuffer.create(size, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            var input = new float[size];
            for (int i = 0; i < size; i++) {
                input[i] = (float) Math.sin(i * 0.01);
            }
            buffer.upload(input);

            var output = new float[size];
            buffer.download(output);

            assertArrayEquals(input, output, 0.0001f);
        }
    }
}
