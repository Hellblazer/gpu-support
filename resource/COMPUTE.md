# GPU Compute Guide

GPU-accelerated vector operations with automatic CPU fallback.

## Setup

Add the dependency:

```xml
<dependency>
    <groupId>com.hellblazer</groupId>
    <artifactId>luciferase-resource</artifactId>
    <version>1.0.5-SNAPSHOT</version>
</dependency>
```

## Basic Usage

```java
var compute = ComputeService.getInstance();

// Vector addition
float[] a = {1, 2, 3, 4};
float[] b = {5, 6, 7, 8};
float[] sum = compute.vectorAdd(a, b);  // [6, 8, 10, 12]

// SAXPY: result = alpha * x + y
float[] result = compute.saxpy(2.0f, a, b);  // [7, 10, 13, 16]

// Scale
float[] scaled = compute.scale(a, 0.5f);  // [0.5, 1, 1.5, 2]

// Reductions
float total = compute.sum(a);   // 10
float max = compute.max(a);     // 4
float min = compute.min(a);     // 1
```

The service automatically uses GPU when available, falls back to CPU otherwise.

## Check Backend

```java
var compute = ComputeService.getInstance();

System.out.println(compute.getBackend());           // METAL, OPENCL, or CPU_FALLBACK
System.out.println(compute.getBackend().isGPU());   // true if GPU backend
System.out.println(compute.isGPUAvailable());       // true if GPU ready
```

## Custom Kernels

Write OpenCL kernels for operations not covered by built-ins:

```java
String source = """
    __kernel void elementwise_multiply(
        __global const float* a,
        __global const float* b,
        __global float* result,
        const int size)
    {
        int i = get_global_id(0);
        if (i < size) {
            result[i] = a[i] * b[i];
        }
    }
    """;

float[] a = {1, 2, 3, 4};
float[] b = {2, 3, 4, 5};

try (var op = compute.createOperation("multiply", source, "elementwise_multiply")) {
    op.setInput(0, a);           // arg 0: input array a
    op.setInput(1, b);           // arg 1: input array b
    op.setOutput(2, a.length);   // arg 2: output buffer
    op.setArg(3, a.length);      // arg 3: size parameter

    float[] result = op.execute(a.length);  // [2, 6, 12, 20]
}
```

### Kernel Argument Types

```java
op.setInput(index, floatArray);   // Read-only float buffer
op.setOutput(index, size);        // Write-only output buffer
op.setArg(index, floatValue);     // Float scalar
op.setArg(index, intValue);       // Int scalar
```

### Work Size

The `execute(workSize)` parameter determines how many work items run. Usually matches your array size:

```java
op.execute(arrayLength);           // 1D execution
op.execute(width * height);        // 2D as flat 1D
```

## Low-Level API

For more control, use the OpenCL classes directly:

```java
import com.hellblazer.luciferase.resource.compute.opencl.*;
import static com.hellblazer.luciferase.resource.compute.opencl.OpenCLBuffer.BufferAccess.*;

var source = KernelLoader.loadOpenCLKernel("vector_add");

try (var kernel = OpenCLKernel.create("vectorAdd");
     var bufA = OpenCLBuffer.createWithData(a, READ_ONLY);
     var bufB = OpenCLBuffer.createWithData(b, READ_ONLY);
     var bufOut = OpenCLBuffer.create(size, WRITE_ONLY)) {

    kernel.compile(source, "vectorAdd");
    kernel.setBufferArg(0, bufA, ComputeKernel.BufferAccess.READ);
    kernel.setBufferArg(1, bufB, ComputeKernel.BufferAccess.READ);
    kernel.setBufferArg(2, bufOut, ComputeKernel.BufferAccess.WRITE);
    kernel.setIntArg(3, size);

    kernel.execute(size);
    kernel.finish();

    var result = new float[size];
    bufOut.download(result);
}
```

## Built-in Kernels

Available in `kernels/opencl/`:

| File | Functions | Description |
|------|-----------|-------------|
| `vector_add.cl` | `vectorAdd`, `vectorAddInPlace` | Element-wise addition |
| `saxpy.cl` | `saxpy`, `saxpyInPlace` | alpha*x + y |
| `reduce.cl` | `reduceSum`, `reduceMax`, `reduceMin` | Parallel reductions |
| `transform.cl` | `scale`, `scaleInPlace`, `addScalar`, `clampValues`, `absolute`, `square`, `squareRoot` | Element transforms |

Load them:

```java
var source = KernelLoader.loadOpenCLKernel("reduce");
```

## Configuration

### Force Backend

Environment variable:
```bash
GPU_BACKEND=cpu ./myapp        # Force CPU
GPU_BACKEND=opencl ./myapp     # Force OpenCL
GPU_BACKEND=metal ./myapp      # Force Metal
```

### Disable GPU

```bash
GPU_DISABLE=true ./myapp
```

Or system property:
```bash
java -Dgpu.disable=true -jar myapp.jar
```

## Error Handling

```java
// Compilation errors
try {
    var op = compute.createOperation("bad", "invalid kernel", "main");
} catch (ComputeKernel.KernelCompilationException e) {
    System.err.println("Compile failed: " + e.getMessage());
}

// Execution errors
try {
    result = op.execute(size);
} catch (ComputeKernel.KernelExecutionException e) {
    System.err.println("Execution failed: " + e.getMessage());
}

// GPU unavailable for custom ops
if (!compute.isGPUAvailable()) {
    // createOperation() will throw IllegalStateException
    // Built-in ops (vectorAdd, etc.) fall back to CPU automatically
}
```

## Writing Kernels

### Basic Pattern

```c
__kernel void my_operation(
    __global const float* input,    // Read-only input
    __global float* output,         // Write-only output
    const int size)                 // Array size
{
    int gid = get_global_id(0);     // Current work item index
    if (gid < size) {               // Bounds check
        output[gid] = input[gid] * 2.0f;
    }
}
```

### Multiple Inputs

```c
__kernel void blend(
    __global const float* a,
    __global const float* b,
    __global float* result,
    const float t,                  // Blend factor
    const int size)
{
    int gid = get_global_id(0);
    if (gid < size) {
        result[gid] = a[gid] * (1.0f - t) + b[gid] * t;
    }
}
```

### Reductions

Parallel reductions need local memory:

```c
__kernel void reduce_sum(
    __global const float* input,
    __global float* output,
    __local float* scratch,         // Shared within work group
    const int size)
{
    int gid = get_global_id(0);
    int lid = get_local_id(0);
    int group_size = get_local_size(0);
    int group_id = get_group_id(0);

    // Load to local memory
    scratch[lid] = (gid < size) ? input[gid] : 0.0f;
    barrier(CLK_LOCAL_MEM_FENCE);

    // Parallel reduction
    for (int stride = group_size / 2; stride > 0; stride >>= 1) {
        if (lid < stride) {
            scratch[lid] += scratch[lid + stride];
        }
        barrier(CLK_LOCAL_MEM_FENCE);
    }

    // Write group result
    if (lid == 0) {
        output[group_id] = scratch[0];
    }
}
```

## Performance Notes

- Small arrays (< 1000 elements): CPU may be faster due to transfer overhead
- GPU shines with large arrays (10K+ elements) and parallel operations
- Avoid frequent small transfers; batch operations when possible
- Reuse buffers in low-level API for repeated operations

## Thread Safety

- `ComputeService.getInstance()` is thread-safe (singleton)
- Built-in operations (`vectorAdd`, etc.) are thread-safe
- `ComputeOperation` instances are not thread-safe; create one per thread
- `OpenCLBuffer` and `OpenCLKernel` are not thread-safe

## Testing

```java
@Test
void testWithGPU() {
    var compute = ComputeService.getInstance();

    // Skip if no GPU
    if (!compute.isGPUAvailable()) {
        return;
    }

    float[] a = {1, 2, 3};
    float[] b = {4, 5, 6};
    float[] result = compute.vectorAdd(a, b);

    assertArrayEquals(new float[]{5, 7, 9}, result, 0.0001f);
}
```

For CI environments without GPU:

```java
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class GPUOnlyTest {
    // Tests that require actual GPU hardware
}
```
