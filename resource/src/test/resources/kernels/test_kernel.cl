/**
 * Test kernel for KernelLoader unit tests.
 */
__kernel void testKernel(__global float* data, const int size) {
    int gid = get_global_id(0);
    if (gid < size) {
        data[gid] = data[gid] * 2.0f;
    }
}
