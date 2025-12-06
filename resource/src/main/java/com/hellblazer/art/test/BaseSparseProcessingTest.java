package com.hellblazer.art.test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base test class for comparing dense vs sparse ART processing outputs.
 *
 * Provides reusable utilities for verifying that sparse processing implementations
 * maintain fidelity with their dense reference implementations. Useful for all
 * sparse processing fidelity testing across the ART architecture.
 *
 * Usage pattern:
 * <pre>
 * public class MyFidelityTest extends BaseSparseProcessingTest {
 *     @Test
 *     public void testMyAlgorithmFidelity() {
 *         float[] denseOutput = runDenseImplementation(...);
 *         float[] sparseOutput = runSparseImplementation(...);
 *         assertFidelityMatch(denseOutput, sparseOutput, 0.01f, "Algorithm name");
 *     }
 * }
 * </pre>
 *
 * Features:
 * - Array comparison with configurable tolerance
 * - L1 distance (Manhattan distance) computation
 * - Maximum absolute difference computation
 * - Sparsity analysis with histograms
 * - Detailed failure reports with diagnostics
 *
 * @author Hal Hildebrand
 */
public abstract class BaseSparseProcessingTest {

    /**
     * Record holding sparsity analysis results.
     *
     * @param activeCount number of elements exceeding threshold
     * @param totalCount total number of elements
     * @param sparsityRatio activeCount / totalCount
     * @param activationDistribution histogram of activation ranges
     */
    public record SparsityInfo(
        int activeCount,
        int totalCount,
        float sparsityRatio,
        float[] activationDistribution
    ) {
    }

    // ============ Core Assertion Method ============

    /**
     * Assert that dense and sparse arrays match within specified tolerance.
     *
     * Fails if maximum element-wise difference exceeds tolerance.
     * Provides detailed diagnostic report on failure.
     *
     * @param dense reference dense output array (FP32)
     * @param sparse sparse processing output array (FP32)
     * @param tolerance maximum acceptable element-wise difference
     * @param message descriptive message for assertion failure
     * @throws AssertionError if arrays exceed tolerance or have different lengths
     */
    protected void assertFidelityMatch(float[] dense, float[] sparse, float tolerance, String message) {
        assertNotNull(dense, message + ": dense array is null");
        assertNotNull(sparse, message + ": sparse array is null");
        assertEquals(dense.length, sparse.length, message + ": arrays have different lengths");

        var maxDiff = computeMaxDifference(dense, sparse);
        if (maxDiff > tolerance) {
            var report = generateDetailedReport(dense, sparse);
            fail(message + ": Fidelity violation - max difference " + maxDiff +
                 " exceeds tolerance " + tolerance + "\n" + report);
        }
    }

    // ============ Difference Metrics ============

    /**
     * Compute L1 distance (Manhattan distance) between two arrays.
     *
     * L1 distance = sum of absolute element-wise differences
     *
     * @param a first array
     * @param b second array
     * @return sum of |a[i] - b[i]| for all indices
     * @throws IllegalArgumentException if arrays have different lengths
     */
    protected float computeL1Difference(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        var sum = 0.0f;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum;
    }

    /**
     * Compute maximum absolute element-wise difference between arrays.
     *
     * Useful for ensuring worst-case accuracy in sparse processing.
     *
     * @param a first array
     * @param b second array
     * @return max(|a[i] - b[i]|) for all indices
     * @throws IllegalArgumentException if arrays have different lengths
     */
    protected float computeMaxDifference(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        var maxDiff = 0.0f;
        for (int i = 0; i < a.length; i++) {
            var diff = Math.abs(a[i] - b[i]);
            if (diff > maxDiff) {
                maxDiff = diff;
            }
        }
        return maxDiff;
    }

    // ============ Sparsity Analysis ============

    /**
     * Analyze sparsity characteristics of a pattern array.
     *
     * Counts active elements (values > threshold) and computes activation distribution
     * histogram across 10 bins from 0.0 to 1.0.
     *
     * @param pattern array to analyze (should be in [0, 1] range for neural outputs)
     * @param threshold minimum activation level to count as active
     * @return SparsityInfo with active count, total count, sparsity ratio, and histogram
     */
    protected SparsityInfo analyzeSparsity(float[] pattern, float threshold) {
        if (pattern.length == 0) {
            return new SparsityInfo(0, 0, 0.0f, new float[10]);
        }

        var activeCount = 0;
        var histogram = new float[10];

        for (var value : pattern) {
            // Count active elements (strictly greater than threshold)
            if (value > threshold) {
                activeCount++;
            }

            // Build histogram: map [0.0, 1.0] to 10 bins
            var binIndex = Math.min(9, (int) (value * 10.0f));
            if (binIndex >= 0 && binIndex < 10) {
                histogram[binIndex]++;
            }
        }

        var sparsityRatio = (float) activeCount / pattern.length;

        return new SparsityInfo(activeCount, pattern.length, sparsityRatio, histogram);
    }

    // ============ Detailed Reporting ============

    /**
     * Generate detailed diagnostic report comparing two arrays.
     *
     * Report includes:
     * - L1 and max difference metrics
     * - Sparsity analysis for both arrays
     * - Element-wise differences exceeding 1% of max value
     * - First 10 failing indices (if any)
     *
     * @param dense reference array
     * @param sparse test array
     * @return multi-line diagnostic report string
     */
    protected String generateDetailedReport(float[] dense, float[] sparse) {
        var sb = new StringBuilder();
        sb.append("\n====== Fidelity Analysis Report ======\n");

        // Compute metrics
        var l1 = computeL1Difference(dense, sparse);
        var maxDiff = computeMaxDifference(dense, sparse);

        sb.append("Difference Metrics:\n");
        sb.append(String.format("  L1 Distance:        %.6f\n", l1));
        sb.append(String.format("  Max Difference:     %.6f\n", maxDiff));

        if (dense.length > 0) {
            var l1Normalized = l1 / dense.length;
            sb.append(String.format("  Mean Difference:    %.6f\n", l1Normalized));
        }

        sb.append("\n");

        // Sparsity analysis
        var densityInfo = analyzeSparsity(dense, 0.01f);
        var sparseInfo = analyzeSparsity(sparse, 0.01f);

        sb.append("Sparsity Analysis (threshold 0.01):\n");
        sb.append(String.format("  Dense:  %d/%d active (%.1f%%)\n",
            densityInfo.activeCount(), densityInfo.totalCount(),
            densityInfo.sparsityRatio() * 100.0f));
        sb.append(String.format("  Sparse: %d/%d active (%.1f%%)\n",
            sparseInfo.activeCount(), sparseInfo.totalCount(),
            sparseInfo.sparsityRatio() * 100.0f));

        // Histogram comparison
        sb.append("\nActivation Distribution (histogram):\n");
        sb.append("  Bin     Dense       Sparse\n");
        var denseHist = densityInfo.activationDistribution();
        var sparseHist = sparseInfo.activationDistribution();
        for (int i = 0; i < 10; i++) {
            sb.append(String.format("  [%.1f-%.1f): %6.0f      %6.0f\n",
                i * 0.1f, (i + 1) * 0.1f, denseHist[i], sparseHist[i]));
        }

        sb.append("\n");

        // Identify problematic indices
        var failingIndices = new ArrayList<Integer>();
        var maxValue = 1e-6f;
        for (var v : dense) {
            maxValue = Math.max(maxValue, Math.abs(v));
        }
        for (var v : sparse) {
            maxValue = Math.max(maxValue, Math.abs(v));
        }

        var threshold = maxValue * 0.01f; // 1% of max value
        for (int i = 0; i < Math.min(dense.length, sparse.length); i++) {
            var diff = Math.abs(dense[i] - sparse[i]);
            if (diff > threshold) {
                failingIndices.add(i);
            }
        }

        sb.append("Element-wise Analysis:\n");
        if (failingIndices.isEmpty()) {
            sb.append("  No significant differences (within 1% of max value)\n");
        } else {
            sb.append(String.format("  %d elements exceed 1%% threshold\n", failingIndices.size()));
            sb.append("  First 10 failing indices (index: dense, sparse, diff):\n");
            for (int i = 0; i < Math.min(10, failingIndices.size()); i++) {
                int idx = failingIndices.get(i);
                var d = dense[idx];
                var s = sparse[idx];
                var diff = Math.abs(d - s);
                sb.append(String.format("    [%d]: %.6f vs %.6f (diff: %.6f)\n", idx, d, s, diff));
            }
        }

        sb.append("\n======================================\n");
        return sb.toString();
    }

}
