/**
 * SAXPY kernel: Single-precision A*X Plus Y
 * result[i] = alpha * x[i] + y[i]
 *
 * Classic BLAS operation, useful for benchmarking.
 */
__kernel void saxpy(
    __global const float* x,
    __global const float* y,
    __global float* result,
    const float alpha,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = alpha * x[gid] + y[gid];
    }
}

/**
 * In-place SAXPY: y[i] = alpha * x[i] + y[i]
 */
__kernel void saxpyInPlace(
    __global const float* x,
    __global float* y,
    const float alpha,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        y[gid] = alpha * x[gid] + y[gid];
    }
}
