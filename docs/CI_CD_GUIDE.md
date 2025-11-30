# CI/CD Integration Guide

## Overview

The GPU Support Framework is designed from the ground up for CI/CD compatibility. Tests gracefully skip when GPU hardware is unavailable, allowing your build to succeed in cloud CI environments while still providing full GPU testing locally.

## Automatic CI Detection

The framework automatically detects CI environments using:

```java
private static boolean isCI() {
    return System.getenv("CI") != null ||
           System.getenv("GITHUB_ACTIONS") != null ||
           System.getenv("JENKINS_HOME") != null ||
           System.getenv("GITLAB_CI") != null ||
           System.getenv("TRAVIS") != null ||
           System.getenv("CIRCLECI") != null;
}
```

## GitHub Actions Integration

### Basic Workflow

```yaml
name: Java CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: 'graalvm'
          github-token: ${{ secrets.GITHUB_TOKEN }}

      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: |
            ${{ runner.os }}-maven-

      - name: Build with Maven
        run: mvn clean install
```

### Expected Behavior

On GitHub Actions runners (Ubuntu, no GPU):
- Build succeeds
- 34 tests skip (21 in resource, 13 in gpu-test-framework)
- Informative skip messages in logs
- Mock platform used for framework testing

### Build Status

![Build Status](https://github.com/Hellblazer/gpu-support/actions/workflows/maven.yml/badge.svg)

## Test Skip Behavior

### Resource Module (21 skipped tests)

OpenCL tests that skip in CI:
- `CLEventHandleTest` - 7 tests
- `LeakDetectionTest` - 7 tests
- `CLBufferHandleTest` - 4 tests (nested test classes)
- `PinnedMemoryTest` - 3 tests

### GPU Test Framework (13 skipped tests)

GPU-dependent tests that skip in CI:
- `MetalComputeTest` - 3 tests
- `BasicGPUComputeTest` - 1 test
- `SimpleMatrixMultiplyTest` - 2 tests
- `GPUVerificationTest` - 3 tests
- `HeadlessPlatformValidationTest` - 1 test
- `AutoBackendSelectionIT` - 3 tests

## Log Output Analysis

### Successful CI Build

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.hellblazer.luciferase.resource.opencl.CLBufferHandleTest
[WARNING] Tests run: 4, Failures: 0, Errors: 0, Skipped: 4, Time elapsed: 0 s

[INFO] Running com.hellblazer.luciferase.gpu.test.HeadlessPlatformValidationTest
[WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.002 s

[INFO] Results:
[INFO]
[WARNING] Tests run: 119, Failures: 0, Errors: 0, Skipped: 34
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Log Improvements (After CI Remediation)

**Before:**
```
[LWJGL] libOpenCL.so not found in org.lwjgl.librarypath=...
[LWJGL] libOpenCL.so not found in system paths...
[LWJGL] Failed to load a library. Possible solutions:...
[WARNING] The system property java.awt.headless is configured twice!
```

**After:**
```
[INFO] Tests automatically skipped - OpenCL not available in CI
15:23:39 [main] INFO  AutoBackendSelectionIT - CI Environment: true
15:23:39 [main] INFO  AutoBackendSelectionIT - Selected Backend: CPU Mock
```

## Multi-Platform Testing

### Matrix Build

Test on multiple platforms:

```yaml
jobs:
  build:
    strategy:
      matrix:
        os: [ubuntu-latest, macos-latest, windows-latest]
        java: ['25']

    runs-on: ${{ matrix.os }}

    steps:
      - uses: actions/checkout@v4

      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: ${{ matrix.java }}
          distribution: 'graalvm'

      - name: Build and Test
        run: mvn clean install
```

### Platform-Specific Notes

**Ubuntu** (GitHub Actions):
- No OpenCL drivers
- All GPU tests skip
- Mock platform used

**macOS** (GitHub Actions):
- May have OpenCL support
- Some tests may run
- Metal tests skip (no GPU access in VM)

**Windows** (GitHub Actions):
- No OpenCL drivers
- All GPU tests skip
- Mock platform used

## Local Development vs CI

### Local Machine (with GPU)

```bash
$ mvn test

[INFO] Running com.hellblazer.luciferase.resource.opencl.CLBufferHandleTest
OpenCL initialized for testing
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0

[INFO] BUILD SUCCESS
```

### CI Environment (without GPU)

```bash
$ mvn test

[INFO] Running com.hellblazer.luciferase.resource.opencl.CLBufferHandleTest
[WARNING] Tests run: 4, Failures: 0, Errors: 0, Skipped: 4

[INFO] BUILD SUCCESS
```

## Mock Platform System

When OpenCL is unavailable, the framework uses mock implementations:

```java
public class CICompatibleGPUTest extends GPUComputeHeadlessTest {

    protected static boolean openCLAvailable;

    static {
        try {
            CL.create();
            openCLAvailable = true;
        } catch (Exception e) {
            openCLAvailable = false;
            log.info("OpenCL not available - using mock platform");
        }
    }

    @BeforeEach
    void ensureOpenCLAvailable() {
        assumeTrue(openCLAvailable, "OpenCL not available - skipping test");
    }
}
```

### Mock Platform Features

- Simulates platform/device discovery
- Returns realistic device info
- Allows framework testing without GPU
- Clearly marked in logs

## Debugging CI Issues

### Enable Detailed Logging

Add to your workflow:

```yaml
- name: Build with Debug Logging
  run: mvn clean install -X
  env:
    MAVEN_OPTS: "-Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG"
```

### Check Surefire Reports

```yaml
- name: Upload Test Reports
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: test-reports
    path: |
      **/target/surefire-reports/
      **/target/failsafe-reports/
```

### View OpenCL Detection Logs

Look for these patterns in CI logs:

```
[LWJGL] Loading library: OpenCL
[LWJGL] libOpenCL.so not found
15:23:39 [main] INFO  CICompatibleGPUTest - OpenCL not available - using mock
```

## Best Practices

### 1. Use CICompatibleGPUTest Base Class

**Recommended:**
```java
public class MyGPUTest extends CICompatibleGPUTest {
    @Test
    void testGPUKernel() {
        var platforms = discoverPlatforms();
        // Automatically skips in CI
    }
}
```

**Avoid:**
```java
public class MyGPUTest {
    @Test
    void testGPUKernel() {
        // No automatic CI handling
        CL.create(); // Fails in CI
    }
}
```

### 2. Check for Mock Platforms

```java
@Test
void testGPUOperation() {
    var platforms = discoverPlatforms();
    var platform = platforms.get(0);

    if (MockPlatform.isMockPlatform(platform)) {
        log.info("Mock platform detected - skipping GPU operation");
        return;
    }

    // Real GPU code here
}
```

### 3. Use Assumptions for Graceful Skipping

```java
@Test
void testGPUFeature() {
    var gpuDevices = discoverGPUDevices();
    assumeFalse(gpuDevices.isEmpty(), "No GPU devices available");

    // Test code
}
```

## Performance in CI

### Build Time Comparison

**With GPU Tests (local):**
```
[INFO] BUILD SUCCESS
[INFO] Total time:  23.240 s
```

**Without GPU (CI):**
```
[INFO] BUILD SUCCESS
[INFO] Total time:  23.156 s
```

Minimal overhead - test skipping is fast!

## Troubleshooting

### Issue: Tests Fail in CI

**Check:**
1. Are you using `CICompatibleGPUTest`?
2. Are assumptions used correctly?
3. Check logs for actual failure vs skip

### Issue: Too Many Skip Warnings

**Solution:**
This is normal! Skipped tests indicate the framework is working correctly.

### Issue: Unexpected Failures

**Debug:**
```bash
# Run locally without GPU to simulate CI
export CI=true
mvn clean test
```

## Advanced: GPU-Enabled CI Runners

If you have access to GPU-enabled CI runners:

```yaml
jobs:
  gpu-tests:
    runs-on: [self-hosted, gpu]

    steps:
      - uses: actions/checkout@v4

      - name: Verify GPU Access
        run: clinfo || nvidia-smi

      - name: Run Full GPU Tests
        run: mvn clean install
```

All tests will run and validate actual GPU functionality.

## Summary

The GPU Support Framework's CI/CD integration ensures:

- Builds succeed in any environment
- Tests skip gracefully without GPU
- Clear skip messages in logs
- Full GPU testing available locally
- Mock platform for framework development
- Fast build times in CI

No special configuration needed - it works out of the box.
