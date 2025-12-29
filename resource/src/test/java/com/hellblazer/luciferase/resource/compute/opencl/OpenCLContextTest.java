package com.hellblazer.luciferase.resource.compute.opencl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenCLContext singleton.
 * Tests the singleton pattern, reference counting, and test isolation.
 *
 * <p>Note: These tests use testReset() for isolation. Actual OpenCL
 * initialization is tested in integration tests with GPU access.
 */
class OpenCLContextTest {

    @BeforeEach
    void setUp() {
        // Reset singleton state before each test
        OpenCLContext.testReset();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        OpenCLContext.testReset();
    }

    // --- Singleton Pattern Tests ---

    @Test
    void testGetInstanceReturnsSameInstance() {
        var instance1 = OpenCLContext.getInstance();
        var instance2 = OpenCLContext.getInstance();
        assertSame(instance1, instance2, "getInstance() should return same instance");
    }

    @Test
    void testGetInstanceNotNull() {
        var instance = OpenCLContext.getInstance();
        assertNotNull(instance, "getInstance() should never return null");
    }

    @Test
    void testTestResetCreatesNewInstance() {
        var instance1 = OpenCLContext.getInstance();
        OpenCLContext.testReset();
        var instance2 = OpenCLContext.getInstance();
        assertNotSame(instance1, instance2, "testReset() should create new instance");
    }

    // --- Reference Counting Tests ---

    @Test
    void testInitialRefCountIsZero() {
        var ctx = OpenCLContext.getInstance();
        assertEquals(0, ctx.getRefCount(), "Initial refCount should be 0");
    }

    @Test
    void testNotInitializedBeforeAcquire() {
        var ctx = OpenCLContext.getInstance();
        assertFalse(ctx.isInitialized(), "Should not be initialized before acquire()");
    }

    @Test
    void testReleaseWithoutAcquireHandledGracefully() {
        var ctx = OpenCLContext.getInstance();
        // Should not throw, should log warning and reset to 0
        ctx.release();
        assertEquals(0, ctx.getRefCount(), "refCount should be 0 after invalid release");
    }

    @Test
    void testMultipleReleasesHandledGracefully() {
        var ctx = OpenCLContext.getInstance();
        // Multiple releases without acquires should not throw
        ctx.release();
        ctx.release();
        ctx.release();
        assertEquals(0, ctx.getRefCount(), "refCount should stay at 0");
    }

    // --- State Validation Tests ---

    @Test
    void testGetContextThrowsWhenNotInitialized() {
        var ctx = OpenCLContext.getInstance();
        assertThrows(IllegalStateException.class, ctx::getContext,
                "getContext() should throw when not initialized");
    }

    @Test
    void testGetCommandQueueThrowsWhenNotInitialized() {
        var ctx = OpenCLContext.getInstance();
        assertThrows(IllegalStateException.class, ctx::getCommandQueue,
                "getCommandQueue() should throw when not initialized");
    }

    @Test
    void testGetDeviceThrowsWhenNotInitialized() {
        var ctx = OpenCLContext.getInstance();
        assertThrows(IllegalStateException.class, ctx::getDevice,
                "getDevice() should throw when not initialized");
    }

    // --- Thread Safety Tests (Basic) ---

    @Test
    void testConcurrentGetInstance() throws InterruptedException {
        var instances = new OpenCLContext[10];
        var threads = new Thread[10];

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> instances[idx] = OpenCLContext.getInstance());
        }

        for (var thread : threads) {
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // All threads should get the same instance
        var first = instances[0];
        for (var instance : instances) {
            assertSame(first, instance, "All threads should get same instance");
        }
    }

    // --- Out of Order Execution Support ---

    @Test
    void testOutOfOrderSupportedDefaultFalse() {
        var ctx = OpenCLContext.getInstance();
        // Before initialization, out-of-order is not supported
        assertFalse(ctx.isOutOfOrderSupported(),
                "Out-of-order should be false before initialization");
    }

    // --- Test Reset Edge Cases ---

    @Test
    void testTestResetWhenNull() {
        // First reset to null
        OpenCLContext.testReset();
        // Second reset should not throw
        OpenCLContext.testReset();
        // Should still work
        var ctx = OpenCLContext.getInstance();
        assertNotNull(ctx);
    }

    @Test
    void testTestResetPreservesIsolation() {
        // Get instance and check state
        var ctx1 = OpenCLContext.getInstance();
        assertEquals(0, ctx1.getRefCount());

        // Reset
        OpenCLContext.testReset();

        // New instance should have fresh state
        var ctx2 = OpenCLContext.getInstance();
        assertEquals(0, ctx2.getRefCount());
        assertFalse(ctx2.isInitialized());
    }
}
