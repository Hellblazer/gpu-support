package com.hellblazer.luciferase.resource.compute;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GPUBuffer interface contract.
 * Tests use a mock implementation to verify interface behavior.
 */
class GPUBufferTest {

    /**
     * Mock implementation for testing interface contracts.
     */
    static class MockGPUBuffer implements GPUBuffer {
        private final int size;
        private boolean valid = true;
        private float[] data;

        MockGPUBuffer(int size) {
            this.size = size;
            this.data = new float[size];
        }

        @Override
        public void upload(FloatBuffer data) {
            if (!valid) throw new IllegalStateException("Buffer not valid");
            if (data.remaining() > size) throw new IllegalArgumentException("Data exceeds capacity");
            data.get(this.data, 0, Math.min(data.remaining(), size));
        }

        @Override
        public void upload(float[] data) {
            if (!valid) throw new IllegalStateException("Buffer not valid");
            if (data.length > size) throw new IllegalArgumentException("Data exceeds capacity");
            System.arraycopy(data, 0, this.data, 0, Math.min(data.length, size));
        }

        @Override
        public void download(FloatBuffer data) {
            if (!valid) throw new IllegalStateException("Buffer not valid");
            if (data.remaining() < size) throw new IllegalArgumentException("Buffer capacity insufficient");
            data.put(this.data);
        }

        @Override
        public void download(float[] data) {
            if (!valid) throw new IllegalStateException("Buffer not valid");
            if (data.length < size) throw new IllegalArgumentException("Array size insufficient");
            System.arraycopy(this.data, 0, data, 0, size);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int sizeInBytes() {
            return size * Float.BYTES;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void close() {
            valid = false;
            data = null;
        }
    }

    @Test
    void testBufferCreation() {
        try (var buffer = new MockGPUBuffer(1024)) {
            assertTrue(buffer.isValid());
            assertEquals(1024, buffer.size());
            assertEquals(1024 * Float.BYTES, buffer.sizeInBytes());
        }
    }

    @Test
    void testUploadDownloadArray() {
        try (var buffer = new MockGPUBuffer(4)) {
            var input = new float[]{1.0f, 2.0f, 3.0f, 4.0f};
            buffer.upload(input);

            var output = new float[4];
            buffer.download(output);

            assertArrayEquals(input, output);
        }
    }

    @Test
    void testUploadDownloadFloatBuffer() {
        try (var buffer = new MockGPUBuffer(4)) {
            var input = FloatBuffer.wrap(new float[]{1.0f, 2.0f, 3.0f, 4.0f});
            buffer.upload(input);

            var output = FloatBuffer.allocate(4);
            buffer.download(output);
            output.flip();

            assertEquals(1.0f, output.get(0));
            assertEquals(4.0f, output.get(3));
        }
    }

    @Test
    void testCloseInvalidatesBuffer() {
        var buffer = new MockGPUBuffer(4);
        assertTrue(buffer.isValid());

        buffer.close();
        assertFalse(buffer.isValid());
    }

    @Test
    void testOperationsOnClosedBufferThrow() {
        var buffer = new MockGPUBuffer(4);
        buffer.close();

        assertThrows(IllegalStateException.class, () -> buffer.upload(new float[4]));
        assertThrows(IllegalStateException.class, () -> buffer.download(new float[4]));
    }

    @Test
    void testUploadExceedingCapacityThrows() {
        try (var buffer = new MockGPUBuffer(4)) {
            assertThrows(IllegalArgumentException.class, () -> buffer.upload(new float[10]));
        }
    }

    @Test
    void testDownloadInsufficientCapacityThrows() {
        try (var buffer = new MockGPUBuffer(4)) {
            assertThrows(IllegalArgumentException.class, () -> buffer.download(new float[2]));
        }
    }

    @Test
    void testAutoCloseable() {
        MockGPUBuffer buffer;
        try (var b = new MockGPUBuffer(4)) {
            buffer = b;
            assertTrue(buffer.isValid());
        }
        assertFalse(buffer.isValid());
    }
}
