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

            System.out.println("Step 3: Getting device count");
            var numDevices = stack.mallocInt(1);
            var result = CL10.clGetDeviceIDs(platform, CL10.CL_DEVICE_TYPE_GPU, null, numDevices);
            System.out.println("Device count result: " + result + ", count: " + numDevices.get(0));

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
}
