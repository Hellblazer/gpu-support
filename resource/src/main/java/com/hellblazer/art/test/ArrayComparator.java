package com.hellblazer.art.test;

/**
 * Utility methods for comparing float arrays with various metrics.
 * Useful for validating numerical computations in tests.
 *
 * @author Hal Hildebrand
 */
public class ArrayComparator {

    /**
     * Result of array comparison with detailed metrics.
     *
     * @param matches true if arrays match within tolerance
     * @param maxDifference maximum absolute element-wise difference
     * @param l1Distance sum of absolute differences
     * @param l2Distance Euclidean distance
     * @param relativeDifference max difference relative to max value
     */
    public record ComparisonResult(
        boolean matches,
        float maxDifference,
        float l1Distance,
        float l2Distance,
        float relativeDifference
    ) {
    }

    /**
     * Compare two arrays with multiple distance metrics.
     *
     * @param a first array
     * @param b second array
     * @param tolerance maximum acceptable difference
     * @return ComparisonResult with all computed metrics
     * @throws IllegalArgumentException if arrays have different lengths
     */
    public static ComparisonResult compare(float[] a, float[] b, float tolerance) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        var maxDiff = 0.0f;
        var l1 = 0.0f;
        var l2 = 0.0f;
        var maxValue = 1e-10f; // Avoid division by zero

        for (int i = 0; i < a.length; i++) {
            var diff = Math.abs(a[i] - b[i]);
            maxDiff = Math.max(maxDiff, diff);
            l1 += diff;
            l2 += diff * diff;
            maxValue = Math.max(maxValue, Math.max(Math.abs(a[i]), Math.abs(b[i])));
        }

        l2 = (float) Math.sqrt(l2);
        var relativeDiff = maxDiff / maxValue;
        var matches = maxDiff <= tolerance;

        return new ComparisonResult(matches, maxDiff, l1, l2, relativeDiff);
    }

    /**
     * Compute cosine similarity between two arrays.
     *
     * Cosine similarity = (a · b) / (||a|| ||b||)
     * Returns value in [-1, 1] where 1 = identical direction.
     *
     * @param a first array
     * @param b second array
     * @return cosine similarity
     * @throws IllegalArgumentException if arrays have different lengths or zero norm
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        var dotProduct = 0.0f;
        var normA = 0.0f;
        var normB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        normA = (float) Math.sqrt(normA);
        normB = (float) Math.sqrt(normB);

        if (normA < 1e-10f || normB < 1e-10f) {
            throw new IllegalArgumentException("Cannot compute cosine similarity for zero-norm vector");
        }

        return dotProduct / (normA * normB);
    }

    /**
     * Compute Pearson correlation coefficient between two arrays.
     *
     * @param a first array
     * @param b second array
     * @return correlation coefficient in [-1, 1]
     * @throws IllegalArgumentException if arrays have different lengths
     */
    public static float pearsonCorrelation(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        // Compute means
        var meanA = 0.0f;
        var meanB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        // Compute correlation
        var numerator = 0.0f;
        var denominatorA = 0.0f;
        var denominatorB = 0.0f;

        for (int i = 0; i < a.length; i++) {
            var diffA = a[i] - meanA;
            var diffB = b[i] - meanB;
            numerator += diffA * diffB;
            denominatorA += diffA * diffA;
            denominatorB += diffB * diffB;
        }

        var denominator = (float) Math.sqrt(denominatorA * denominatorB);
        if (denominator < 1e-10f) {
            return 0.0f; // Constant arrays have undefined correlation
        }

        return numerator / denominator;
    }

    /**
     * Check if two arrays are approximately equal within tolerance.
     *
     * @param a first array
     * @param b second array
     * @param tolerance maximum acceptable element-wise difference
     * @return true if all elements match within tolerance
     */
    public static boolean approximatelyEqual(float[] a, float[] b, float tolerance) {
        if (a.length != b.length) {
            return false;
        }

        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > tolerance) {
                return false;
            }
        }

        return true;
    }

    /**
     * Find indices where two arrays differ by more than tolerance.
     *
     * @param a first array
     * @param b second array
     * @param tolerance maximum acceptable difference
     * @return array of indices where difference exceeds tolerance
     */
    public static int[] findDifferences(float[] a, float[] b, float tolerance) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Arrays must have same length: " + a.length + " vs " + b.length);
        }

        var temp = new int[a.length];
        var count = 0;

        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > tolerance) {
                temp[count++] = i;
            }
        }

        // Copy to exact-size array
        var result = new int[count];
        System.arraycopy(temp, 0, result, 0, count);
        return result;
    }

}
