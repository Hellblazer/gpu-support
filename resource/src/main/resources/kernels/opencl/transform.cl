/**
 * Element-wise transformation kernels.
 */

/**
 * Scale: result[i] = data[i] * scale
 */
__kernel void scale(
    __global const float* data,
    __global float* result,
    const float scale,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = data[gid] * scale;
    }
}

/**
 * Scale in-place: data[i] *= scale
 */
__kernel void scaleInPlace(
    __global float* data,
    const float scale,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        data[gid] *= scale;
    }
}

/**
 * Add scalar: result[i] = data[i] + value
 */
__kernel void addScalar(
    __global const float* data,
    __global float* result,
    const float value,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = data[gid] + value;
    }
}

/**
 * Clamp: result[i] = clamp(data[i], minVal, maxVal)
 */
__kernel void clampValues(
    __global const float* data,
    __global float* result,
    const float minVal,
    const float maxVal,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = clamp(data[gid], minVal, maxVal);
    }
}

/**
 * Absolute value: result[i] = |data[i]|
 */
__kernel void absolute(
    __global const float* data,
    __global float* result,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = fabs(data[gid]);
    }
}

/**
 * Square: result[i] = data[i]^2
 */
__kernel void square(
    __global const float* data,
    __global float* result,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        float v = data[gid];
        result[gid] = v * v;
    }
}

/**
 * Square root: result[i] = sqrt(data[i])
 */
__kernel void squareRoot(
    __global const float* data,
    __global float* result,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = sqrt(data[gid]);
    }
}
