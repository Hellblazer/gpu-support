package com.hellblazer.luciferase.resource.compute.examples;

import com.hellblazer.luciferase.resource.compute.ComputeService;

import java.util.Random;

/**
 * GPU vs CPU performance comparison.
 *
 * <p>Run with: mvn test -Dtest=PerformanceExample
 */
public class PerformanceExample {

    public static void main(String[] args) {
        var compute = ComputeService.getInstance();
        System.out.println("Backend: " + compute.getBackend().getDisplayName());
        System.out.println("GPU Available: " + compute.isGPUAvailable());

        // Test different array sizes
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};

        System.out.println("\nVector Addition Performance (GPU vs CPU)");
        System.out.println("=========================================");
        System.out.printf("%-12s %12s %12s %10s%n", "Size", "GPU (ms)", "CPU (ms)", "Speedup");
        System.out.println("-".repeat(48));

        var rand = new Random(42);

        for (int size : sizes) {
            // Generate test data
            float[] a = new float[size];
            float[] b = new float[size];
            for (int i = 0; i < size; i++) {
                a[i] = rand.nextFloat();
                b[i] = rand.nextFloat();
            }

            // Warm up
            compute.vectorAdd(a, b);

            // Time GPU (via ComputeService)
            long gpuStart = System.nanoTime();
            float[] gpuResult = compute.vectorAdd(a, b);
            long gpuTime = System.nanoTime() - gpuStart;

            // Time CPU
            long cpuStart = System.nanoTime();
            float[] cpuResult = new float[size];
            for (int i = 0; i < size; i++) {
                cpuResult[i] = a[i] + b[i];
            }
            long cpuTime = System.nanoTime() - cpuStart;

            // Verify
            boolean correct = true;
            for (int i = 0; i < size; i++) {
                if (Math.abs(gpuResult[i] - cpuResult[i]) > 0.0001f) {
                    correct = false;
                    break;
                }
            }

            double gpuMs = gpuTime / 1_000_000.0;
            double cpuMs = cpuTime / 1_000_000.0;
            double speedup = cpuMs / gpuMs;

            System.out.printf("%-12s %12.2f %12.2f %9.2fx %s%n",
                    formatSize(size), gpuMs, cpuMs, speedup,
                    correct ? "" : "[MISMATCH]");
        }

        System.out.println("\nNote: GPU includes data transfer overhead.");
        System.out.println("      Larger arrays amortize transfer cost better.");

        // Cleanup
        ComputeService.testReset();
    }

    private static String formatSize(int size) {
        if (size >= 1_000_000) {
            return (size / 1_000_000) + "M";
        } else if (size >= 1_000) {
            return (size / 1_000) + "K";
        }
        return String.valueOf(size);
    }
}
