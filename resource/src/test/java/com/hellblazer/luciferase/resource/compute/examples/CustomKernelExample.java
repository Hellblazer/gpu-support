package com.hellblazer.luciferase.resource.compute.examples;

import com.hellblazer.luciferase.resource.compute.ComputeService;

/**
 * Custom kernel for operations not in the built-in set.
 *
 * <p>Run with: mvn test -Dtest=CustomKernelExample
 */
public class CustomKernelExample {

    public static void main(String[] args) throws Exception {
        var compute = ComputeService.getInstance();

        if (!compute.isGPUAvailable()) {
            System.out.println("GPU not available, skipping custom kernel example");
            return;
        }

        System.out.println("Backend: " + compute.getBackend().getDisplayName());

        // Element-wise multiply kernel
        String multiplyKernel = """
            __kernel void multiply(
                __global const float* a,
                __global const float* b,
                __global float* result,
                const int size)
            {
                int i = get_global_id(0);
                if (i < size) {
                    result[i] = a[i] * b[i];
                }
            }
            """;

        float[] prices = {10.50f, 25.00f, 8.99f, 42.00f, 15.75f};
        float[] quantities = {2, 1, 5, 1, 3};

        System.out.println("\nElement-wise Multiply (prices * quantities):");
        System.out.println("  prices     = " + formatArray(prices));
        System.out.println("  quantities = " + formatArray(quantities));

        try (var op = compute.createOperation("multiply", multiplyKernel, "multiply")) {
            op.setInput(0, prices);
            op.setInput(1, quantities);
            op.setOutput(2, prices.length);
            op.setArg(3, prices.length);

            float[] totals = op.execute(prices.length);
            System.out.println("  totals     = " + formatArray(totals));

            // Sum the totals
            float grandTotal = compute.sum(totals);
            System.out.println("  grand total = " + String.format("%.2f", grandTotal));
        }

        // Threshold kernel
        String thresholdKernel = """
            __kernel void threshold(
                __global const float* input,
                __global float* output,
                const float thresh,
                const int size)
            {
                int i = get_global_id(0);
                if (i < size) {
                    output[i] = (input[i] > thresh) ? 1.0f : 0.0f;
                }
            }
            """;

        float[] values = {0.1f, 0.6f, 0.3f, 0.8f, 0.4f, 0.9f, 0.2f};
        float threshold = 0.5f;

        System.out.println("\nThreshold (values > 0.5):");
        System.out.println("  input  = " + formatArray(values));

        try (var op = compute.createOperation("threshold", thresholdKernel, "threshold")) {
            op.setInput(0, values);
            op.setOutput(1, values.length);
            op.setArg(2, threshold);
            op.setArg(3, values.length);

            float[] binary = op.execute(values.length);
            System.out.println("  output = " + formatArray(binary));
        }

        // Cleanup
        ComputeService.testReset();
    }

    private static String formatArray(float[] arr) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", arr[i]));
        }
        return sb.append("]").toString();
    }
}
