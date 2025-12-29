package com.hellblazer.luciferase.resource.compute.examples;

import com.hellblazer.luciferase.resource.compute.ComputeKernel;
import com.hellblazer.luciferase.resource.compute.KernelLoader;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLContext;
import com.hellblazer.luciferase.resource.compute.opencl.OpenCLKernel;

import static com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess.*;

/**
 * Low-level API for direct buffer and kernel control.
 *
 * <p>Use when you need:
 * <ul>
 *   <li>Buffer reuse across operations</li>
 *   <li>Fine-grained timing</li>
 *   <li>Multiple kernels sharing buffers</li>
 * </ul>
 *
 * <p>Run with: mvn test -Dtest=LowLevelExample
 */
public class LowLevelExample {

    public static void main(String[] args) throws Exception {
        // Check if OpenCL available
        if (!OpenCLContext.getInstance().isInitialized()) {
            System.out.println("OpenCL not available");
            return;
        }

        System.out.println("OpenCL initialized");

        // Load kernel source from resources
        var source = KernelLoader.loadOpenCLKernel("vector_add");

        // Test data
        float[] a = {1, 2, 3, 4, 5, 6, 7, 8};
        float[] b = {8, 7, 6, 5, 4, 3, 2, 1};
        int size = a.length;

        System.out.println("\nLow-Level Vector Addition");
        System.out.println("=========================");
        System.out.println("Input a: " + formatArray(a));
        System.out.println("Input b: " + formatArray(b));

        // Create buffers and kernel
        try (var bufA = OpenCLBuffer.createWithData(a, READ_ONLY);
             var bufB = OpenCLBuffer.createWithData(b, READ_ONLY);
             var bufResult = OpenCLBuffer.create(size, WRITE_ONLY);
             var kernel = OpenCLKernel.create("vectorAdd")) {

            // Compile kernel
            kernel.compile(source, "vectorAdd");

            // Set arguments
            kernel.setBufferArg(0, bufA, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(1, bufB, ComputeKernel.BufferAccess.READ);
            kernel.setBufferArg(2, bufResult, ComputeKernel.BufferAccess.WRITE);
            kernel.setIntArg(3, size);

            // Execute
            long start = System.nanoTime();
            kernel.execute(size);
            kernel.finish();
            long elapsed = System.nanoTime() - start;

            // Download result
            var result = new float[size];
            bufResult.download(result);

            System.out.println("Result:  " + formatArray(result));
            System.out.printf("Time:    %.3f ms%n", elapsed / 1_000_000.0);

            // Reuse buffers for another operation
            System.out.println("\nBuffer Reuse Example");
            System.out.println("====================");

            // Upload new data to same buffer
            float[] c = {10, 20, 30, 40, 50, 60, 70, 80};
            bufA.upload(c);

            // Re-execute with same kernel setup
            kernel.execute(size);
            kernel.finish();
            bufResult.download(result);

            System.out.println("New a:   " + formatArray(c));
            System.out.println("Same b:  " + formatArray(b));
            System.out.println("Result:  " + formatArray(result));
        }

        // Cleanup
        OpenCLContext.testReset();
    }

    private static String formatArray(float[] arr) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.0f", arr[i]));
        }
        return sb.append("]").toString();
    }
}
