# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A GPU support framework for Java providing resource lifecycle management and comprehensive testing infrastructure for OpenCL, OpenGL, and Metal GPU compute workloads. Built with LWJGL 3.3.6, designed for the Luciferase project's spatial computing needs.

**Language:** Java 25 (using modern Java features)

## Project Structure

Multi-module Maven project with two main modules:

- **resource**: RAII-based GPU resource lifecycle management with memory pooling
- **gpu-test-framework**: Headless GPU testing framework with CI/CD compatibility

## Build Commands

### Basic Operations
```bash
mvn clean install              # Full build
mvn clean compile              # Compile only
mvn test                       # Run all tests
mvn verify                     # Run integration tests
```

### Module-Specific
```bash
mvn test -pl resource                    # Test resource module
mvn test -pl gpu-test-framework         # Test GPU framework
```

### GPU-Specific Testing
```bash
# Platform validation (run first!)
mvn test -Dtest=HeadlessPlatformValidationTest

# GPU-specific tests
mvn test -Pgpu-tests

# Performance benchmarks
mvn test -Pgpu-benchmark

# Single test with GPU access (requires disabling sandbox)
mvn test -Dtest=ClassName#methodName
```

### Parallel Testing
```bash
mvn test -Pparallel-tests      # Resource module: 2-3x faster
```

## GPU Testing Requirements

GPU/OpenCL tests require `dangerouslyDisableSandbox: true` when using Bash tool because the sandbox blocks GPU/device access in background processes.

Example:
```java
@Test
void testOpenCLKernel() {
    // This test needs GPU access
    // When running via Bash: use dangerouslyDisableSandbox: true
}
```

## JVM Requirements & Known Warnings

### Required JVM Arguments (Already Configured)

The build automatically configures these JVM arguments for all tests:

- `--enable-native-access=ALL-UNNAMED` - Required for LWJGL native library loading (Java 21+)
- `--add-modules jdk.incubator.vector` - Enables Vector API for SIMD operations
- `--add-opens java.base/java.lang=ALL-UNNAMED` - Required for LWJGL reflection
- `--add-opens java.base/java.nio=ALL-UNNAMED` - Required for LWJGL ByteBuffer operations

### Expected Warnings in Logs

**LWJGL Unsafe API Deprecation** (Upstream Issue - Safe to Ignore):
```
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.lwjgl.system.MemoryUtil
WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release
```
- **Root Cause:** LWJGL 3.3.6 uses deprecated Unsafe APIs for performance
- **Impact:** LWJGL dependency issue, not under our control
- **Status:** Tracked by LWJGL maintainers, will be fixed in future LWJGL release
- **Action:** None required - functionality works correctly

**Incubator Module Usage** (Expected):
```
WARNING: Using incubator modules: jdk.incubator.vector
```
- **Root Cause:** Vector API is still incubating in Java 25
- **Impact:** Required for SIMD operations; may stabilize in future Java versions
- **Action:** This is intentional - Vector API provides significant performance benefits

### CI/CD Environment Behavior

Tests automatically skip in CI environments without GPU drivers:
- **Resource module:** 21 OpenCL tests skip gracefully
- **GPU Test Framework:** All GPU-dependent tests skip
- **Mock Platform:** Automatically used in CI for testing framework logic
- **Detection:** Uses `CICompatibleGPUTest` base class for automatic OpenCL detection

## Key Architecture Patterns

### Resource Management (RAII Pattern)

The resource module implements Resource Acquisition Is Initialization (RAII) in Java using AutoCloseable:

```java
// UnifiedResourceManager: Central manager for all GPU resources
var manager = new UnifiedResourceManager();

// ResourceHandle<T>: Base class for native resource wrappers
// GPUResource: Common interface (getId(), getType(), getSizeBytes(), etc.)
// MemoryPool: ByteBuffer pooling with LRU/FIFO/LFU eviction

// Always use try-with-resources:
try (var buffer = manager.createSSBO(data, "my-buffer")) {
    // Use buffer - automatically released on close
}
```

### GPU Test Framework Hierarchy

Base class hierarchy for GPU testing:

1. **LWJGLHeadlessTest**: Base for all LWJGL headless tests
2. **OpenCLHeadlessTest**: OpenCL initialization/cleanup
3. **GPUComputeHeadlessTest**: Platform/device discovery
4. **CICompatibleGPUTest**: CI-compatible with automatic OpenCL detection + mock fallback

Use `CICompatibleGPUTest` for new tests (automatically handles missing OpenCL).

### Testing Strategy

- **Platform validation first**: Always run `HeadlessPlatformValidationTest` before other GPU tests
- **Graceful degradation**: Tests skip when GPU unavailable (using JUnit `assumeTrue()`)
- **Mock platform support**: `MockPlatform` provides fallback when OpenCL unavailable
- **Cross-validation**: CPU vs GPU result validation in tests

## Native Library Management

LWJGL native libraries are automatically selected via Maven profiles based on OS/architecture:

- macOS: ARM64 (`natives-macos-arm64`), x86_64 (`natives-macos`)
- Linux: AMD64, ARM64 (`natives-linux`, `natives-linux-arm64`)
- Windows: AMD64 (`natives-windows`)

## Configuration Conventions

### JVM Arguments for GPU Tests

Tests automatically configure:
- `-Djava.awt.headless=true` - Headless operation
- `-Dlwjgl.opencl.explicitInit=true` - Explicit OpenCL init
- `--add-modules jdk.incubator.vector` - Vector API support
- `--add-opens java.base/java.lang=ALL-UNNAMED` - Reflection access

### Resource Configuration

```java
var config = ResourceConfiguration.builder()
    .maxPoolSizeBytes(100 * 1024 * 1024)  // 100MB pool
    .maxIdleTime(Duration.ofMinutes(5))
    .enableLeakDetection(true)
    .build();
```

## Important Implementation Details

### Memory Pooling

- `UnifiedResourceManager` uses `MemoryPool` for ByteBuffer pooling
- Identity-based mapping (`IdentityHashMap`) for ByteBuffer tracking
- Pool eviction on maintenance: `manager.performMaintenance()`
- Always call `releaseMemory(buffer)` to return to pool

### OpenCL Detection

`CICompatibleGPUTest` automatically detects OpenCL availability:
- Static flag `openCLAvailable` cached across tests
- `@BeforeEach` ensures OpenCL available or skips test
- CI environment detection via env vars (CI, GITHUB_ACTIONS, etc.)

### Resource Lifecycle Tracking

- `ResourceTracker`: Leak detection and resource age tracking
- `ResourceStatistics`: Per-type and aggregate statistics
- `ResourceLifecycleListener`: Hook into allocation/release events
- Test utilities: `ResourceLifecycleTestSupport`, `LeakReport`

## Testing Conventions

### GPU Test Pattern

```java
public class MyGPUTest extends CICompatibleGPUTest {
    @Test
    void testGPUKernel() {
        var platforms = discoverPlatforms();
        // Test automatically skipped if OpenCL unavailable

        var platform = platforms.get(0);
        if (MockPlatform.isMockPlatform(platform)) {
            log.info("Using mock platform - skipping GPU kernel test");
            return;
        }

        // Real GPU test code
    }
}
```

### Platform-Specific Testing

```java
PlatformTestSupport.requirePlatform(Platform.LINUX);  // Linux-only
PlatformTestSupport.require64Bit();                   // 64-bit only
PlatformTestSupport.skipOnARMForStackTests();         // Skip on ARM
```

### Benchmarking

Use JMH for performance testing:
```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class GPUBenchmark {
    @Benchmark
    public void benchmarkCPU() { /* ... */ }

    @Benchmark
    public void benchmarkGPU() { /* ... */ }
}
```

## Common Patterns

### Kernel Loading

```java
// Use KernelResourceLoader for classpath kernel loading
var kernelSource = KernelResourceLoader.loadKernel("kernels/vector_add.cl");
```

### Cross-Validation

```java
// Validate GPU results against CPU reference
var cpuResult = processCPU(input);
var gpuResult = processGPU(input);
assertArrayEquals(cpuResult, gpuResult, 1e-6f);
```

## Logging

Uses SLF4J with Logback:
- Production: `resource/src/main/resources/logback.xml`
- Testing: `resource/src/test/resources/logback-test.xml`

Debug GPU operations:
```xml
<logger name="com.hellblazer.luciferase.resource" level="DEBUG"/>
<logger name="com.hellblazer.luciferase.gpu.test" level="DEBUG"/>
```

## Integration with Luciferase

Designed for:
- ESVO (Efficient Sparse Voxel Octree) ray traversal kernels
- Spatial index GPU acceleration
- Collision detection GPU kernels
- Voxel rendering compute shaders

## Known Limitations

- ARM64 JVM stack tests may fail (expected - library functions work fine)
- macOS windowing requires `-XstartOnFirstThread` (use headless tests instead)
- CI environments typically lack OpenCL drivers (framework handles gracefully)