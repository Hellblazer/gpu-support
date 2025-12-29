/**
 * Vector addition kernel.
 * result[i] = a[i] + b[i]
 */
__kernel void vectorAdd(
    __global const float* a,
    __global const float* b,
    __global float* result,
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = a[gid] + b[gid];
    }
}
