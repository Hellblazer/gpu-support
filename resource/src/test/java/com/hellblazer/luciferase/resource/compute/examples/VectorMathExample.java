package com.hellblazer.luciferase.resource.compute.examples;

import com.hellblazer.luciferase.resource.compute.ComputeService;

/**
 * Basic vector math operations.
 *
 * <p>Run with: mvn test -Dtest=VectorMathExample
 */
public class VectorMathExample {

    public static void main(String[] args) {
        var compute = ComputeService.getInstance();
        System.out.println("Backend: " + compute.getBackend().getDisplayName());

        // Sample data
        float[] prices = {10.50f, 25.00f, 8.99f, 42.00f, 15.75f};
        float[] quantities = {2, 1, 5, 1, 3};

        // Vector multiply (prices * quantities) using SAXPY trick
        // We want: result = prices * quantities
        // SAXPY gives: result = alpha * x + y
        // So: set y to zeros, alpha to 1, then element-wise wouldn't work...
        // Actually, let's use custom kernel for multiply

        // For now, demonstrate what's built-in:

        // Addition
        float[] a = {1, 2, 3, 4, 5};
        float[] b = {10, 20, 30, 40, 50};
        float[] sum = compute.vectorAdd(a, b);
        System.out.println("\nVector Add:");
        System.out.println("  a = " + formatArray(a));
        System.out.println("  b = " + formatArray(b));
        System.out.println("  a + b = " + formatArray(sum));

        // SAXPY: result = 2*a + b
        float[] saxpyResult = compute.saxpy(2.0f, a, b);
        System.out.println("\nSAXPY (2*a + b):");
        System.out.println("  result = " + formatArray(saxpyResult));

        // Scale
        float[] scaled = compute.scale(a, 0.5f);
        System.out.println("\nScale (a * 0.5):");
        System.out.println("  result = " + formatArray(scaled));

        // Reductions
        System.out.println("\nReductions on a:");
        System.out.println("  sum = " + compute.sum(a));
        System.out.println("  min = " + compute.min(a));
        System.out.println("  max = " + compute.max(a));

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
