package com.hellblazer.luciferase.resource.compute.opencl;

import org.junit.jupiter.api.BeforeAll;
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
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class OpenCLBufferTest {

    private static boolean openCLAvailable;

    @BeforeAll
    static void checkOpenCL() {
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

                openCLAvailable = result == CL10.CL_SUCCESS && numDevices.get(0) > 0;
            }
            System.out.println("OpenCL available: " + openCLAvailable);
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
        }
    }

    // --- Creation Tests ---

    @Test
    void testCreateBuffer() {
        if (!openCLAvailable) return;

        try (var buffer = OpenCLBuffer.create(256, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            assertNotNull(buffer);
            assertEquals(256, buffer.size());
            assertEquals(256 * Float.BYTES, buffer.sizeInBytes());
            assertTrue(buffer.isValid());
        }
    }

    @Test
    void testCreateWithData() {
        if (!openCLAvailable) return;

        var data = new float[]{1.0f, 2.0f, 3.0f, 4.0f};
        try (var buffer = OpenCLBuffer.createWithData(data, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            assertNotNull(buffer);
            assertEquals(4, buffer.size());
            assertTrue(buffer.isValid());
        }
    }

    @Test
    void testBufferAccessModes() {
        if (!openCLAvailable) return;

        try (var readOnly = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_ONLY);
             var writeOnly = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.WRITE_ONLY);
             var readWrite = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE)) {

            assertEquals(OpenCLBuffer.BufferAccess.READ_ONLY, readOnly.getAccess());
            assertEquals(OpenCLBuffer.BufferAccess.WRITE_ONLY, writeOnly.getAccess());
            assertEquals(OpenCLBuffer.BufferAccess.READ_WRITE, readWrite.getAccess());
        }
    }

    // --- Upload/Download Tests ---

    @Test
    void testUploadDownloadFloatArray() {
        if (!openCLAvailable) return;

        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            var input = new float[]{1.0f, 2.0f, 3.0f, 4.0f};
            buffer.upload(input);

            var output = new float[4];
            buffer.download(output);

            assertArrayEquals(input, output, 0.0001f);
        }
    }

    @Test
    void testUploadDownloadFloatBuffer() {
        if (!openCLAvailable) return;

        try (var buffer = OpenCLBuffer.create(4, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            FloatBuffer input = MemoryUtil.memAllocFloat(4);
            try {
                input.put(new float[]{5.0f, 6.0f, 7.0f, 8.0f});
                input.flip();

                buffer.upload(input);

                // Reset input position for reuse check
                input.rewind();

                FloatBuffer output = MemoryUtil.memAllocFloat(4);
                try {
                    buffer.download(output);
                    // Note: download doesn't flip, data is at position 0
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

    // --- Lifecycle Tests ---

    @Test
    void testCloseMarksInvalid() {
        if (!openCLAvailable) return;

        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        assertTrue(buffer.isValid());

        buffer.close();
        assertFalse(buffer.isValid());
    }

    @Test
    void testDoubleCloseIsSafe() {
        if (!openCLAvailable) return;

        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        buffer.close();
        buffer.close(); // Should not throw
        assertFalse(buffer.isValid());
    }

    @Test
    void testOperationsAfterCloseThrow() {
        if (!openCLAvailable) return;

        var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE);
        buffer.close();

        assertThrows(IllegalStateException.class, () -> buffer.upload(new float[4]));
        assertThrows(IllegalStateException.class, () -> buffer.download(new float[4]));
        assertThrows(IllegalStateException.class, buffer::getHandle);
    }

    @Test
    void testGetHandle() {
        if (!openCLAvailable) return;

        try (var buffer = OpenCLBuffer.create(64, OpenCLBuffer.BufferAccess.READ_WRITE)) {
            long handle = buffer.getHandle();
            assertTrue(handle != 0, "Handle should be non-zero");
        }
    }

    @Test
    void testLargerBuffer() {
        if (!openCLAvailable) return;

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
