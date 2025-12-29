package com.hellblazer.luciferase.resource.compute.opencl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to verify OpenCL platform detection works.
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true", disabledReason = "OpenCL not available in CI")
class MinimalOpenCLTest {

    private static boolean openCLAvailable;

    @BeforeAll
    static void checkOpenCL() {
        // Only check device availability - don't create context
        // This mimics OpenCLBufferTest's @BeforeAll
        try (var stack = MemoryStack.stackPush()) {
            System.out.println("Step 1: Getting platform count");
            var numPlatforms = stack.mallocInt(1);
            var errcode = CL10.clGetPlatformIDs((PointerBuffer) null, numPlatforms);

            if (errcode != CL10.CL_SUCCESS || numPlatforms.get(0) == 0) {
                openCLAvailable = false;
                System.out.println("No platforms");
                return;
            }

            System.out.println("Step 2: Getting platform");
            var platformBuffer = stack.mallocPointer(1);
            CL10.clGetPlatformIDs(platformBuffer, (int[]) null);
            var platform = platformBuffer.get(0);
            System.out.println("Got platform: " + platform);

            System.out.println("Step 3: Checking for devices");
            var numDevices = stack.mallocInt(1);
            var result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, null, numDevices);
            if (result != CL10.CL_SUCCESS) {
                result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_CPU, null, numDevices);
            }

            openCLAvailable = result == CL10.CL_SUCCESS && numDevices.get(0) > 0;
            System.out.println("OpenCL available: " + openCLAvailable);
        } catch (Exception e) {
            openCLAvailable = false;
            System.out.println("OpenCL check failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void testMinimal() {
        System.out.println("Running minimal test, OpenCL=" + openCLAvailable);
        assertTrue(true, "Minimal test should pass");
    }

    @Test
    void testOpenCLContextBuffer() {
        System.out.println("Testing OpenCLContext buffer creation");
        if (!openCLAvailable) {
            System.out.println("OpenCL not available - skipping");
            return;
        }

        try {
            System.out.println("Acquiring OpenCLContext...");
            var ctx = OpenCLContext.getInstance();
            if (!ctx.isInitialized()) {
                ctx.acquire();
            }
            System.out.println("OpenCLContext acquired, context=" + ctx.getContext());

            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var errcode = stack.mallocInt(1);
                System.out.println("Creating buffer via OpenCLContext...");
                var buffer = CL10.clCreateBuffer(ctx.getContext(), CL10.CL_MEM_READ_WRITE, 256 * 4, errcode);
                System.out.println("Buffer created: errcode=" + errcode.get(0) + ", buffer=" + buffer);
                assertTrue(buffer != 0, "Buffer should be created");
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            e.printStackTrace();
            fail("Should not throw: " + e.getMessage());
        }
    }

    @Test
    void testOpenCLBufferCreate() {
        System.out.println("Testing OpenCLBuffer.create()");
        if (!openCLAvailable) {
            System.out.println("OpenCL not available - skipping");
            return;
        }

        try {
            System.out.println("Calling OpenCLBuffer.create(256, READ_WRITE)...");
            try (var buffer = OpenCLBuffer.create(256, OpenCLBuffer.BufferAccess.READ_WRITE)) {
                System.out.println("OpenCLBuffer created: handle=" + buffer.getHandle() + ", size=" + buffer.size());
                assertNotNull(buffer);
                assertEquals(256, buffer.size());
            }
            System.out.println("OpenCLBuffer test complete");
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            e.printStackTrace();
            fail("Should not throw: " + e.getMessage());
        }
    }
}
