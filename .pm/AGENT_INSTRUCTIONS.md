# Agent Instructions: GPU-Support OpenCL Extraction

Instructions for agents spawned to work on gpu-support project tasks.

## Quick Orientation (Read This First)

You are working on **GPU-Support OpenCL Compute Infrastructure Extraction** - extracting ART's GPU compute infrastructure for reuse across projects.

### Before Starting Work
1. Read `.pm/CONTINUATION.md` (5 min) - Your current phase and next action
2. Search ChromaDB: `plan::gpu-support::art-opencl-extraction::v1` (2 min) - Full architecture
3. Check `bd list gpu-support-bsy` (1 min) - See current beads
4. Total context gathering: 8 minutes

### Your Bead
- **What's assigned**: [See bead ID in parent handoff]
- **What it means**: Extract specific interface/implementation, write tests, mark complete
- **How to track**: `bd show <id>` and `bd update <id> --status in_progress`
- **When done**: `bd close <id>` with commit message

### Core Files Reference
| File | Purpose | Update When |
|------|---------|-----------|
| `.pm/CONTINUATION.md` | Session context | End of session |
| `.pm/execution_state.json` | Project metrics | Phase completion |
| `.pm/METHODOLOGY.md` | Engineering standards | Methodology changes only |
| `gpu-support_active/extraction-plan-state.md` | Bead structure | At session start |

## Engineering Standards

### Test-First Workflow (TDD)

**Every task follows this pattern:**

1. **RED**: Write failing test
   ```bash
   mvn test -Dtest=GPUBufferTest  # Test fails - RED
   ```

2. **GREEN**: Implement to pass test
   ```bash
   # Add implementation
   mvn test -Dtest=GPUBufferTest  # Test passes - GREEN
   ```

3. **REFACTOR**: Improve code
   ```bash
   # Clean up, improve names, extract utilities
   mvn test -Dtest=GPUBufferTest  # Tests still pass - REFACTOR
   ```

### Critical Patterns

#### 1. Interface Extraction (Phase 1)
```java
// Step 1: Write test for interface contract
@Test
void testInterfaceContract() {
    GPUBuffer buffer = createTestBuffer(1024);
    assertThat(buffer.getId()).isPositive();
    assertThat(buffer.getSizeBytes()).isEqualTo(1024);
}

// Step 2: Extract interface from ART
public interface GPUBuffer {
    long getId();
    long getSizeBytes();
    GPUResourceType getType();
}

// Step 3: Implement in tests
class MockGPUBuffer implements GPUBuffer {
    // Mock implementation for testing
}
```

#### 2. Resource Management (Phase 2)
Always use AutoCloseable and register with ResourceTracker:
```java
public class OpenCLContext implements GPUContext, AutoCloseable {

    public OpenCLContext(GPUCapabilityProfile profile) {
        // Create LWJGL resources
        this.clContext = CL10.clCreateContext(...);

        // Register for leak detection
        ResourceTracker.register(this, "OpenCLContext-" + profile.deviceName());
    }

    @Override
    public void close() {
        if (valid.compareAndSet(true, false)) {
            // Clean up LWJGL resources
            CL10.clReleaseContext(clContext);

            // Unregister from tracking
            ResourceTracker.unregister(this);
        }
    }
}
```

#### 3. Error Handling (All Phases)
Every LWJGL call must check error code:
```java
var errcode = BufferUtils.createIntBuffer(1);
var context = CL10.clCreateContext(null, deviceId, null, 0, errcode);

if (errcode.get(0) != CL10.CL_SUCCESS) {
    throw new GPUInitializationException(
        "Failed to create OpenCL context: error code " + errcode.get(0)
    );
}
```

## Naming and Generalization

### Package Structure
```
ART Original:
com.hellblazer.art.cortical.gpu.GPUBuffer

gpu-support Target:
com.hellblazer.luciferase.resource.compute.GPUBuffer
                                ^^^^^^^^^
                              core interfaces
```

### Environment Variables
```
ART Name                    →  gpu-support Name
ART_GPU_BACKEND             →  GPU_BACKEND
ART_GPU_DISABLE             →  GPU_DISABLE
art.gpu.disable             →  gpu.disable
```

**Rule**: Search/replace systematically. No ART_ prefix in extracted code.

### JavaDoc Standards
```java
/**
 * GPU buffer abstraction for compute operations.
 *
 * Represents allocated GPU memory that can be read, written,
 * or used as kernel argument. Implementations manage lifecycle
 * and synchronization with GPU context.
 *
 * @see OpenCLBuffer for OpenCL-specific implementation
 */
public interface GPUBuffer {

    /**
     * Unique identifier for this buffer within its GPU context.
     * @return non-zero long identifier
     */
    long getId();
}
```

## Testing Standards

### Unit Tests (No GPU Required)
```java
@Test
void testInterfaceContract() {
    // Use mocks, not real GPU
    GPUBuffer buffer = createMockBuffer(1024);
    assertThat(buffer.getSizeBytes()).isEqualTo(1024);
}

@Test
void testBoundaryConditions() {
    // Edge cases: empty, null, zero
    assertThat(createMockBuffer(0).getSizeBytes()).isZero();
}

@Test
void testErrorHandling() {
    // Exceptional paths
    assertThrows(GPUInitializationException.class, () -> {
        // trigger error
    });
}
```

### Integration Tests (GPU Optional)
```java
@EnabledIf("isGPUAvailable")
class OpenCLContextIntegrationTest extends CICompatibleGPUTest {

    @Test
    void testContextWithRealGPU() {
        var context = new OpenCLContext(discoveredProfile);
        assertThat(context.isValid()).isTrue();
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
    }

    System.gc();
    assertThat(ResourceTracker.getResourceCount()).isEqualTo(before);
}
```

## Commit Guidelines

### Commit Message Format
```
{bead-id}: {brief description}

Detailed explanation of what, why, and impact.

- Reference: {epic-id}
- Tests: {count}
- Related: {other beads}
```

### Example
```
gpu-support-e63: Extract GPUBuffer interface from ART

Extract GPUBuffer interface from ART's cortical GPU module to
gpu-support for cross-project reuse. Generalize naming to remove
ART-specific environment variables and package structure.

- Extracted: GPUBuffer with getId(), getSizeBytes(), getType()
- Generalized: ART_GPU_BACKEND → GPU_BACKEND
- Tests: 4 unit tests for interface contract
- Reference: gpu-support-bsy (Epic)
```

## Critical Implementation Notes

### macOS Cleanup Workaround
OpenCL has SIGABRT bug on macOS:
```java
// LWJGL OpenCL macOS SIGABRT Bug:
// Calling clReleaseContext causes crash. Skip on macOS.
if (!isMacOS()) {
    CL10.clReleaseContext(clContext);
}

private static boolean isMacOS() {
    return System.getProperty("os.name").toLowerCase().contains("mac");
}
```

### CLBufferHandle Integration
Don't create new resource wrapper - use existing:
```java
// GOOD: Use CLBufferHandle from gpu-support
long clMem = CL10.clCreateBuffer(...);
var handle = new CLBufferHandle(clMem, size, this);

// BAD: Don't create new wrapper
class MyBufferHandle { }  // Don't do this
```

### Singleton Reference Counting
OpenCLContext uses singleton with ref counting:
```java
public class OpenCLContext implements GPUContext {
    private static volatile OpenCLContext instance;
    private final AtomicInteger refCount = new AtomicInteger(0);

    public static OpenCLContext getInstance() {
        // Double-checked locking
        if (instance == null) {
            synchronized(OpenCLContext.class) {
                if (instance == null) {
                    instance = new OpenCLContext();
                }
            }
        }
        instance.refCount.incrementAndGet();
        return instance;
    }

    public void release() {
        if (refCount.decrementAndGet() == 0) {
            close();
        }
    }

    public void reset() {
        // For testing
        instance = null;
    }
}
```

## GPU Testing Requirements

GPU tests need special handling:

### Local Testing
```bash
# If you have GPU and want to run real tests
mvn test -Pgpu-tests

# Requires: dangerouslyDisableSandbox: true in Bash tool
```

### CI Environment
```bash
# CI has no GPU - tests gracefully skip
mvn test

# Uses CICompatibleGPUTest base class
# Tests marked with @EnabledIf("isGPUAvailable") skip automatically
```

### Mock Environment
```bash
# For testing without GPU
mvn test -Dart.gpu.mock=true -Dart.gpu.mock.profile=NVIDIA_RTX4090
```

## Integration Points

### Before Starting
Check these don't already exist (reuse if they do):
- [ ] `.pm/checkpoints/` - Phase checkpoint template
- [ ] `.pm/learnings/` - Learning template
- [ ] `.pm/hypotheses/` - Hypothesis template
- [ ] ChromaDB plan document (search first)
- [ ] Memory Bank project state

### During Work
Update these as you progress:
- [ ] Bead status: `bd update <id> --status in_progress`
- [ ] Memory Bank: Add blockers if encountered
- [ ] ChromaDB: Create decision document for major choices
- [ ] CONTINUATION.md: If major context change

### At Completion
Finalize these before handing off:
- [ ] Bead status: `bd close <id>` with commit
- [ ] Code review: Run self-check from METHODOLOGY.md checklist
- [ ] Tests: All passing locally and in CI
- [ ] Documentation: JavaDoc complete, CHANGELOG updated
- [ ] ChromaDB: Store key decision/learning
- [ ] Memory Bank: Clear any blockers noted during work

## Common Workflows

### Parallel Task Execution (Phase 1)
All four Phase 1 tasks (e63, ad2, 9go, kdp) can run in parallel:

1. **gpu-support-e63**: Extract GPUBuffer interface
2. **gpu-support-ad2**: Extract ComputeKernel interface
3. **gpu-support-9go**: Extract GPUBackend enum
4. **gpu-support-kdp**: Extract GPUErrorClassifier

If spawned for any of these:
- Work independently - no dependencies
- Coordinate at phase end for testing
- All must complete before Phase 1 Tests (gpu-support-ipz)

### Sequential Phase Workflow
Later phases have dependencies:

```
Phase 1 (4 parallel tasks) →
    ↓
Phase 1 Tests (gpu-support-ipz) →
    ↓
Phase 2 (5 parallel tasks) →
    ↓
Phase 2 Integration Tests (gpu-support-97u) →
    ↓
Phase 3 (2 tasks) →
    ↓
Phase 4 (3 tasks)
```

If waiting for dependency:
1. Check `.pm/execution_state.json` for dependent bead status
2. Query `bd list gpu-support-bsy` to see what's blocking
3. Update Memory Bank: `gpu-support_active/blockers.md`
4. Don't create separate blocker bead - just flag in Memory Bank

## Code Review Checklist

Before handing off completed work:

### Functional
- [ ] Implementation matches interface contract
- [ ] All tests passing (unit + integration)
- [ ] No resource leaks (ResourceTracker clean)
- [ ] Error handling comprehensive
- [ ] No GPU required for unit tests

### Code Quality
- [ ] Naming generalized (no ART_)
- [ ] JavaDoc present and clear
- [ ] No code duplication
- [ ] Follows Java 24 patterns (var, records)
- [ ] SLF4J logging configured

### Integration
- [ ] No breaking changes
- [ ] Backward compatibility maintained
- [ ] CI compatible (graceful GPU detection)
- [ ] Documentation updated

### Commit Quality
- [ ] Message includes bead ID
- [ ] References epic ID
- [ ] No AI attribution
- [ ] Professional technical content

## Troubleshooting

### "Cannot resolve symbol 'CL10'"
**Cause**: Missing LWJGL OpenCL dependency
**Fix**: Verify pom.xml has `org.lwjgl:lwjgl-opencl`
```bash
mvn dependency:tree | grep opencl
```

### "SIGABRT on macOS during cleanup"
**Cause**: LWJGL OpenCL macOS bug
**Fix**: Use platform detection in close()
```java
if (!isMacOS()) {
    CL10.clReleaseContext(clContext);
}
```

### "Tests skip in CI but fail locally"
**Cause**: CICompatibleGPUTest skips without GPU
**Fix**: This is expected - tests skip gracefully in CI
**Verify**: Check CI logs show "Skipped" not "Failed"

### "ResourceTracker reports leak"
**Cause**: Forgot to call close() or unregister
**Fix**: Ensure AutoCloseable implemented, ResourceTracker.unregister() called
```java
try (var resource = new MyResource()) {
    // Use resource
}  // close() and unregister() called automatically
```

## When Stuck

If blocked for >30 minutes:
1. **Document** the issue in Memory Bank: `gpu-support_active/blockers.md`
2. **Search** ChromaDB for similar issues: `debug::{issue}`
3. **Check** METHODOLOGY.md troubleshooting section
4. **Flag** in bead notes: `bd update <id> -n "Blocker: ..."`
5. **Escalate** to plan-auditor or orchestrator

## Success Metrics

You're done when:
- [ ] Bead tasks complete: tests GREEN, code reviewed
- [ ] ChromaDB updated with any major decisions
- [ ] No resource leaks detected
- [ ] Commit includes bead + epic reference
- [ ] Code passes review checklist
- [ ] Bead marked complete: `bd close <id>`

---

**Version**: 1.0
**Last Updated**: 2025-12-28
**For Questions**: See `.pm/README.md` for file locations and contacts
