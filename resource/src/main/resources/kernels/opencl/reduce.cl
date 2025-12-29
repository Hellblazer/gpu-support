/**
 * Parallel reduction kernels for computing sum, min, max of arrays.
 * Uses local memory for efficient work-group reduction.
 */

/**
 * Sum reduction - computes partial sums per work group.
 * Final reduction must be done on host or with another kernel call.
 *
 * @param input Input array
 * @param output Partial sums (one per work group)
 * @param scratch Local memory for work-group reduction
 * @param size Input array size
 */
__kernel void reduceSum(
    __global const float* input,
    __global float* output,
    __local float* scratch,
    const int size)
{
    int gid = get_global_id(0);
    int lid = get_local_id(0);
    int groupSize = get_local_size(0);
    int groupId = get_group_id(0);

    // Load into local memory
    scratch[lid] = (gid < size) ? input[gid] : 0.0f;
    barrier(CLK_LOCAL_MEM_FENCE);

    // Parallel reduction in local memory
    for (int stride = groupSize / 2; stride > 0; stride >>= 1) {
        if (lid < stride) {
            scratch[lid] += scratch[lid + stride];
        }
        barrier(CLK_LOCAL_MEM_FENCE);
    }

    // Write result
    if (lid == 0) {
        output[groupId] = scratch[0];
    }
}

/**
 * Max reduction - computes partial max per work group.
 */
__kernel void reduceMax(
    __global const float* input,
    __global float* output,
    __local float* scratch,
    const int size)
{
    int gid = get_global_id(0);
    int lid = get_local_id(0);
    int groupSize = get_local_size(0);
    int groupId = get_group_id(0);

    scratch[lid] = (gid < size) ? input[gid] : -INFINITY;
    barrier(CLK_LOCAL_MEM_FENCE);

    for (int stride = groupSize / 2; stride > 0; stride >>= 1) {
        if (lid < stride) {
            scratch[lid] = fmax(scratch[lid], scratch[lid + stride]);
        }
        barrier(CLK_LOCAL_MEM_FENCE);
    }

    if (lid == 0) {
        output[groupId] = scratch[0];
    }
}

/**
 * Min reduction - computes partial min per work group.
 */
__kernel void reduceMin(
    __global const float* input,
    __global float* output,
    __local float* scratch,
    const int size)
{
    int gid = get_global_id(0);
    int lid = get_local_id(0);
    int groupSize = get_local_size(0);
    int groupId = get_group_id(0);

    scratch[lid] = (gid < size) ? input[gid] : INFINITY;
    barrier(CLK_LOCAL_MEM_FENCE);

    for (int stride = groupSize / 2; stride > 0; stride >>= 1) {
        if (lid < stride) {
            scratch[lid] = fmin(scratch[lid], scratch[lid + stride]);
        }
        barrier(CLK_LOCAL_MEM_FENCE);
    }

    if (lid == 0) {
        output[groupId] = scratch[0];
    }
}
