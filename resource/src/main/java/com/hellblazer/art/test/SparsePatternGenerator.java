package com.hellblazer.art.test;

import java.util.Random;

/**
 * Test data generator for sparse and dense activation patterns.
 * Provides various pattern types commonly used in ART sparse processing tests.
 *
 * @author Hal Hildebrand
 */
public class SparsePatternGenerator {

    /**
     * Generate a random sparse pattern with specified density.
     *
     * @param size number of elements in pattern
     * @param density fraction of elements that should be active (0.0-1.0)
     * @param random random number generator for reproducibility
     * @return float array with specified sparsity
     */
    public static float[] randomSparsePattern(int size, float density, Random random) {
        if (density < 0.0f || density > 1.0f) {
            throw new IllegalArgumentException("Density must be in [0.0, 1.0], got: " + density);
        }

        var pattern = new float[size];
        var activeCount = (int) (size * density);

        // Generate random active positions using shuffle to avoid collisions
        var indices = new int[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }
        
        // Fisher-Yates shuffle of first activeCount positions
        for (int i = 0; i < activeCount; i++) {
            var j = i + random.nextInt(size - i);
            var temp = indices[i];
            indices[i] = indices[j];
            indices[j] = temp;
            pattern[indices[i]] = random.nextFloat();
        }

        return pattern;
    }

    /**
     * Generate a random dense pattern where all elements are active.
     *
     * @param size number of elements in pattern
     * @param random random number generator for reproducibility
     * @return float array with all elements in [0.0, 1.0]
     */
    public static float[] randomDensePattern(int size, Random random) {
        var pattern = new float[size];
        for (int i = 0; i < size; i++) {
            pattern[i] = random.nextFloat();
        }
        return pattern;
    }

    /**
     * Generate a uniform pattern with all elements set to same value.
     *
     * @param size number of elements in pattern
     * @param value uniform value for all elements
     * @return float array with all elements set to value
     */
    public static float[] uniformPattern(int size, float value) {
        var pattern = new float[size];
        for (int i = 0; i < size; i++) {
            pattern[i] = value;
        }
        return pattern;
    }

    /**
     * Generate a gradient pattern with linearly increasing values.
     *
     * @param size number of elements in pattern
     * @param startValue value at index 0
     * @param endValue value at index size-1
     * @return float array with linear gradient
     */
    public static float[] gradientPattern(int size, float startValue, float endValue) {
        var pattern = new float[size];
        var step = (endValue - startValue) / Math.max(1, size - 1);
        for (int i = 0; i < size; i++) {
            pattern[i] = startValue + i * step;
        }
        return pattern;
    }

    /**
     * Generate a localized activation pattern (Gaussian-like bump).
     *
     * @param size number of elements in pattern
     * @param centerIndex index of peak activation
     * @param width standard deviation of activation spread
     * @param peakValue maximum value at center
     * @return float array with localized activation
     */
    public static float[] localizedPattern(int size, int centerIndex, float width, float peakValue) {
        var pattern = new float[size];
        for (int i = 0; i < size; i++) {
            var distance = Math.abs(i - centerIndex);
            var activation = (float) Math.exp(-(distance * distance) / (2.0f * width * width));
            pattern[i] = activation * peakValue;
        }
        return pattern;
    }

    /**
     * Add Gaussian noise to a pattern.
     *
     * @param pattern input pattern (modified in place)
     * @param noiseMagnitude standard deviation of Gaussian noise
     * @param random random number generator
     * @return the modified pattern (same reference as input)
     */
    public static float[] addNoise(float[] pattern, float noiseMagnitude, Random random) {
        for (int i = 0; i < pattern.length; i++) {
            var noise = (float) (random.nextGaussian() * noiseMagnitude);
            pattern[i] = Math.max(0.0f, Math.min(1.0f, pattern[i] + noise));
        }
        return pattern;
    }

    /**
     * Threshold a pattern to create binary sparse pattern.
     *
     * @param pattern input pattern
     * @param threshold values above threshold become 1.0, below become 0.0
     * @return new binary pattern
     */
    public static float[] threshold(float[] pattern, float threshold) {
        var result = new float[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            result[i] = pattern[i] > threshold ? 1.0f : 0.0f;
        }
        return result;
    }

}
