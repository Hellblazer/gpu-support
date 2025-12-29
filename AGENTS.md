# Agent Instructions

## Workflow

This project uses **bd** (beads) for issue tracking. Run `bd onboard` to get started.

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --status in_progress  # Claim work
bd close <id>         # Complete work
bd sync               # Sync with git
```

### Session Completion

Work is NOT complete until `git push` succeeds.

```bash
git pull --rebase
bd sync
git push
git status  # MUST show "up to date with origin"
```

---

## GPU Compute API Reference

### Quick Start

```java
var compute = ComputeService.getInstance();
float[] result = compute.vectorAdd(a, b);
```

### Package Structure

```
com.hellblazer.luciferase.resource.compute
├── ComputeService          # High-level API (start here)
├── GPUBackend              # METAL | OPENCL | CPU_FALLBACK
├── BackendSelector         # Auto-selects best backend
├── KernelLoader            # Load .cl files from resources
└── opencl/
    ├── OpenCLContext       # Singleton, manages device/queue
    ├── OpenCLBuffer        # GPU memory, use try-with-resources
    └── OpenCLKernel        # Compile and execute kernels
```

### ComputeService Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `getInstance` | `() → ComputeService` | Singleton |
| `isGPUAvailable` | `() → boolean` | Check before custom ops |
| `getBackend` | `() → GPUBackend` | METAL, OPENCL, or CPU_FALLBACK |
| `vectorAdd` | `(float[], float[]) → float[]` | a + b |
| `saxpy` | `(float, float[], float[]) → float[]` | alpha*x + y |
| `scale` | `(float[], float) → float[]` | data * scalar |
| `sum` | `(float[]) → float` | Sum all |
| `min` | `(float[]) → float` | Minimum |
| `max` | `(float[]) → float` | Maximum |
| `createOperation` | `(String, String, String) → ComputeOperation` | Custom kernel |

### Patterns

**Built-in operations:**
```java
var compute = ComputeService.getInstance();
float[] sum = compute.vectorAdd(a, b);
float[] result = compute.saxpy(2.0f, x, y);
float[] scaled = compute.scale(data, 0.5f);
float total = compute.sum(data);
```

**Custom kernel:**
```java
String kernel = """
    __kernel void op(__global const float* in,
                     __global float* out,
                     const int size) {
        int i = get_global_id(0);
        if (i < size) out[i] = in[i] * 2.0f;
    }
    """;

try (var op = compute.createOperation("name", kernel, "op")) {
    op.setInput(0, inputArray);
    op.setOutput(1, size);
    op.setArg(2, size);
    float[] result = op.execute(size);
}
```

**Low-level control:**
```java
try (var kernel = OpenCLKernel.create("name");
     var bufIn = OpenCLBuffer.createWithData(data, READ_ONLY);
     var bufOut = OpenCLBuffer.create(size, WRITE_ONLY)) {

    kernel.compile(source, "entryPoint");
    kernel.setBufferArg(0, bufIn, READ);
    kernel.setBufferArg(1, bufOut, WRITE);
    kernel.setIntArg(2, size);
    kernel.execute(size);
    kernel.finish();
    bufOut.download(result);
}
```

### Kernel Template

```c
__kernel void name(
    __global const float* input,   // arg 0
    __global float* output,        // arg 1
    const float scalar,            // arg 2
    const int size)                // arg 3
{
    int gid = get_global_id(0);
    if (gid < size) {              // bounds check required
        output[gid] = input[gid] * scalar;
    }
}
```

### Built-in Kernels

`resources/kernels/opencl/`:

| File | Functions |
|------|-----------|
| `vector_add.cl` | `vectorAdd` |
| `saxpy.cl` | `saxpy`, `saxpyInPlace` |
| `reduce.cl` | `reduceSum`, `reduceMax`, `reduceMin` |
| `transform.cl` | `scale`, `addScalar`, `clampValues`, `absolute`, `square`, `squareRoot` |

Load: `KernelLoader.loadOpenCLKernel("vector_add")`

### Configuration

| Env Variable | Values | Effect |
|--------------|--------|--------|
| `GPU_BACKEND` | `metal`, `opencl`, `cpu` | Force backend |
| `GPU_DISABLE` | `true` | Disable GPU |

### Error Handling

```java
// GPU unavailable - built-ins auto-fallback to CPU
// Custom ops throw IllegalStateException
if (!compute.isGPUAvailable()) { /* handle */ }

// Compilation
catch (ComputeKernel.KernelCompilationException e)

// Execution
catch (ComputeKernel.KernelExecutionException e)
```

### Common Mistakes

| Wrong | Right |
|-------|-------|
| `stack.ints(N)` for arg | `clSetKernelArg1i(k, i, N)` |
| No bounds check in kernel | `if (gid < size)` |
| Forgetting `close()` | Use try-with-resources |

### Key Files

| Purpose | Path |
|---------|------|
| High-level API | `resource/.../compute/ComputeService.java` |
| Usage guide | `resource/COMPUTE.md` |
| Examples | `resource/.../compute/examples/*.java` |
| Tests | `resource/.../compute/ComputeServiceTest.java` |

### Testing

```java
// Skip if no GPU
if (!compute.isGPUAvailable()) return;

// CI annotation
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
```

### Build

```bash
./mvnw test -pl resource                    # Test compute module
./mvnw test -Dtest=ComputeServiceTest       # Specific test
```
