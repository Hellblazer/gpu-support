# Engineering Methodology: GPU-Support OpenCL Extraction

This document defines engineering discipline for extracting and integrating OpenCL compute infrastructure.

## Test-First Development (TDD)

Every implementation task follows strict test-first workflow:

### RED Phase: Write Failing Test
```java
@Test
void testGPUBufferContract() {
    GPUBuffer buffer = createTestBuffer(1024);

    // Test interface contract
    assertThat(buffer.getId()).isPositive();
    assertThat(buffer.getSizeBytes()).isEqualTo(1024);
    assertThat(buffer.getType()).isNotNull();
}
```

**Acceptance**: Test compiles and fails (red bar).

### GREEN Phase: Implement Minimum
```java
public interface GPUBuffer {
    long getId();
    long getSizeBytes();
    GPUResourceType getType();
}

public class GPUBufferImpl implements GPUBuffer {
    private final long id;
    private final long sizeBytes;

    @Override
    public long getId() { return id; }

    @Override
    public long getSizeBytes() { return sizeBytes; }

    @Override
    public GPUResourceType getType() { return GPUResourceType.BUFFER; }
}
```

**Acceptance**: Test passes (green bar).

### REFACTOR Phase: Clean Code
- Extract common logic to utility methods
- Improve naming and organization
- Add additional tests for edge cases
- Keep tests passing throughout

**Acceptance**: Tests still pass, code cleaner.

## Source Code Extraction Process

### Step 1: Locate Source File
```bash
find /Users/hal.hildebrand/git/ART -name "GPUBuffer.java" -type f
# Result: /Users/hal.hildebrand/git/ART/art-modules/.../GPUBuffer.java
```

### Step 2: Understand Dependencies
```bash
# Identify imports and class references
grep -E "^(import|class|interface)" GPUBuffer.java
# Check for ART-specific types
grep "ART\|art\." GPUBuffer.java
```

### Step 3: Extract Clean Copy
1. Copy source to target location
2. Remove all ART-specific imports
3. Generalize package names (`art.*` → `luciferase.resource.*`)
4. Generalize environment variables (`ART_*` → `*`)
5. Preserve original logic and comments

### Step 4: Add Tests
Create unit tests for interface contract without GPU dependencies.

### Step 5: Validate
- Compiles without errors
- Tests pass locally
- Tests pass in CI (with GPU mock)
- No GPU required (pure Java interfaces)

### Step 6: Review and Merge
- Code review by plan-auditor
- Verify no ART-specific code remains
- Update bead status to complete

## Naming Conventions

### Package Structure
```
ART Original:           gpu-support Target:
com.hellblazer.art      com.hellblazer.luciferase.resource
  .cortical             .compute (core interfaces)
  .gpu                  .compute.opencl (implementations)
```

### Class Naming
```
ART Name                →  gpu-support Name
ARTGPUBuffer            →  GPUBuffer (interface)
ARTOpenCLContext        →  OpenCLContext (implementation)
ART_GPU_BACKEND         →  GPU_BACKEND (env var)
```

**Rule**: Remove project prefixes. Use generic names for shared infrastructure.

### Environment Variables
```
ART_GPU_BACKEND         →  GPU_BACKEND (e.g., "OPENCL")
ART_GPU_DISABLE         →  GPU_DISABLE (e.g., "true")
art.gpu.disable         →  gpu.disable (system property)
```

### Bead Naming
```
{project}-{phase}{type}: Description

gpu-support-e63: Extract GPUBuffer interface
gpu-support-6e9: Phase 1 - Core Interfaces (feature)
gpu-support-bsy: Epic - ART OpenCL Extraction
```

## Code Quality Standards

### Interfaces (No Implementation)
- Clear, minimal contract
- Comprehensive JavaDoc
- No platform-specific code
- Ready for multiple implementations (OpenCL, Metal, CUDA)

### Implementations (OpenCL Specific)
- Single responsibility (one API per class)
- Reference counting for resource management
- Comprehensive error handling
- Platform-specific workarounds documented

### Error Handling
```java
public class OpenCLContext implements GPUContext {
    public OpenCLContext(GPUCapabilityProfile profile) {
        var errcode = BufferUtils.createIntBuffer(1);
        this.clContext = CL10.clCreateContext(null, profile.deviceId(), null, 0, errcode);

        // LWJGL error checking
        if (errcode.get(0) != CL10.CL_SUCCESS) {
            throw new GPUInitializationException(
                "Failed to create OpenCL context: error code " + errcode.get(0)
            );
        }
    }
}
```

**Rule**: Every LWJGL call must check error code immediately.

### Resource Lifecycle
```java
public class OpenCLContext implements GPUContext, AutoCloseable {

    public OpenCLContext(GPUCapabilityProfile profile) {
        // 1. Create resources
        // 2. Register with ResourceTracker
        ResourceTracker.register(this, "OpenCLContext-" + profile.deviceName());
    }

    @Override
    public void close() {
        if (valid.compareAndSet(true, false)) {
            // 1. Release LWJGL resources
            // 2. Unregister from ResourceTracker
            ResourceTracker.unregister(this);
        }
    }
}
```

**Rule**: Always implement AutoCloseable. Always register with ResourceTracker.

### Platform-Specific Workarounds
```java
// Document workarounds clearly with issue context
public void close() {
    if (valid.compareAndSet(true, false)) {
        CL10.clReleaseCommandQueue(clCommandQueue);

        // LWJGL OpenCL macOS SIGABRT Bug:
        // Calling clReleaseContext on macOS causes SIGABRT during shutdown.
        // This is an upstream LWJGL issue. Skip cleanup to avoid crash.
        // Issue: https://github.com/LWJGL/lwjgl3/issues/XXXX
        if (!isMacOS()) {
            CL10.clReleaseContext(clContext);
        }
    }
}

private static boolean isMacOS() {
    return System.getProperty("os.name").toLowerCase().contains("mac");
}
```

## Testing Standards

### Unit Tests (No GPU Required)
```java
@Test
void testInterfaceContract() {
    // Test purely on JVM, no GPU calls
    // Use mocks for dependencies
    // Fast execution (<100ms)
}

@Test
void testBoundaryConditions() {
    // Test edge cases: empty, null, zero-size
    // Verify error handling
}

@Test
void testErrorHandling() {
    // Test exceptional paths
    // Verify clear error messages
}
```

### Integration Tests (GPU Optional)
```java
@EnabledIf("isGPUAvailable")
class OpenCLContextIntegrationTest extends CICompatibleGPUTest {

    @Test
    void testContextCreationWithRealGPU() {
        var context = new OpenCLContext(discoveredProfile);

        // Assert context valid
        assertThat(context.isValid()).isTrue();
        assertThat(context.getContextHandle()).isNotEqualTo(0);
    }

    static boolean isGPUAvailable() {
        try {
            CL.create();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### Resource Leak Detection
```java
@Test
void testNoResourceLeaks() {
    var before = ResourceTracker.getResourceCount();

    try (var context = new OpenCLContext(profile)) {
        // Use context
        context.getCommandQueueHandle();
    }

    System.gc();
    var after = ResourceTracker.getResourceCount();

    assertThat(after).isEqualTo(before);
}
```

## Commit Guidelines

### Commit Message Format
```
{bead-id}: {brief description}

Detailed explanation of what and why.

- Reference: {epic-bead-id}
- Tests: {test count}
- Related: {other bead ids}
```

### Example Commit
```
gpu-support-e63: Extract GPUBuffer interface from ART

Extract the GPUBuffer interface from ART's cortical GPU module
to gpu-support for cross-project reuse. Generalize naming and
remove ART-specific dependencies.

- Extracted: GPUBuffer interface with getId(), getSizeBytes(), getType()
- Generalized: Environment variables (GPU_BACKEND instead of ART_GPU_BACKEND)
- Tests: 4 unit tests for interface contract
- Reference: gpu-support-bsy (Epic)
```

## Code Review Checklist

### Functional Review
- [ ] Implementation matches interface contract
- [ ] All tests passing (unit + integration)
- [ ] No resource leaks (ResourceTracker clean)
- [ ] Error handling comprehensive (all error paths tested)
- [ ] No GPU required for unit tests

### Code Quality Review
- [ ] Naming generalized (no ART_ prefix)
- [ ] Package structure correct (compute, compute.opencl)
- [ ] JavaDoc present and clear
- [ ] No duplication (extract common utilities)
- [ ] Follows Java 24 patterns (var, records, sealed classes)

### Integration Review
- [ ] No breaking changes to existing code
- [ ] Backward compatibility maintained (deprecated old paths if needed)
- [ ] CI compatible (graceful GPU detection)
- [ ] Documentation updated

### Security Review
- [ ] No credentials or secrets in code
- [ ] No unsafe reflection without comment
- [ ] ResourceTracker enables leak detection
- [ ] No public mutable state

## Documentation Standards

### JavaDoc Requirements
```java
/**
 * GPU buffer abstraction for compute operations.
 *
 * Represents allocated GPU memory that can be read, written, or
 * used as kernel argument. Implementations manage lifecycle and
 * synchronization with GPU context.
 *
 * @see OpenCLBuffer for OpenCL-specific implementation
 * @see GPUContext for context management
 */
public interface GPUBuffer {

    /**
     * Unique identifier for this buffer within its GPU context.
     *
     * @return non-zero long identifier
     */
    long getId();

    /**
     * Size of allocated GPU memory in bytes.
     *
     * @return allocation size, always >= 0
     */
    long getSizeBytes();
}
```

### Implementation Notes
Include platform-specific details inline:
```java
public class OpenCLContext implements GPUContext {

    /**
     * Creates OpenCL context for specified GPU capability profile.
     *
     * Platform Notes:
     * - macOS: Skips clReleaseContext in close() to avoid SIGABRT
     * - Linux: Full cleanup enabled
     * - Windows: Full cleanup enabled
     *
     * @param profile GPU capability profile with device ID
     * @throws GPUInitializationException if context creation fails
     */
    public OpenCLContext(GPUCapabilityProfile profile) {
        // ...
    }
}
```

## Integration Checklist

Before marking phase complete:

### Phase Completion Criteria
- [ ] All tasks within phase have passing tests
- [ ] All code has been reviewed
- [ ] No resource leaks detected
- [ ] Beads marked complete
- [ ] Documentation updated

### Pre-Release Validation
- [ ] CI builds successfully
- [ ] All tests pass (unit + integration + benchmark)
- [ ] No GPU required tests
- [ ] GPU-dependent tests skip gracefully in CI
- [ ] Cross-module integration tested (with CLBufferHandle, ResourceTracker)

## Troubleshooting

### Common Issues

**Issue**: "Cannot resolve symbol 'CL10'"
- **Cause**: LWJGL-opencl dependency missing
- **Fix**: Verify pom.xml has org.lwjgl:lwjgl-opencl
- **Verify**: `mvn dependency:tree | grep opencl`

**Issue**: "SIGABRT on macOS during context cleanup"
- **Cause**: LWJGL OpenCL macOS bug
- **Fix**: Use macOS detection in close() method
- **Verify**: Test on actual macOS machine

**Issue**: "Tests skip in CI due to missing GPU"
- **Cause**: Expected behavior, tests use CICompatibleGPUTest base
- **Fix**: No action needed, system working as designed
- **Verify**: CI logs show "Skipped" not "Failed"

## References

- **Strategic Plan**: ChromaDB `plan::gpu-support::art-opencl-extraction::v1`
- **Session State**: Memory Bank `gpu-support_active/extraction-plan-state.md`
- **Java Standards**: CLAUDE.md project directives
- **GPU Guidelines**: CLAUDE.md GPU Testing Requirements section

---

**Version**: 1.0
**Last Updated**: 2025-12-28
**Maintained By**: Project Infrastructure
