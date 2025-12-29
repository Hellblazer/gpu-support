# Continuation: GPU-Support OpenCL Compute Infrastructure Extraction

**Date**: 2025-12-28
**Branch**: feature/opencl-compute-infrastructure
**Epic**: gpu-support-bsy

## Quick Context

Extracting ART's OpenCL compute infrastructure into gpu-support for cross-project reuse by ART, Luciferase, and future projects.

### Source Location
```
ART: /Users/hal.hildebrand/git/ART/art-modules/art-cortical/src/main/java/com/hellblazer/art/cortical/gpu/
```

### Target Location
```
gpu-support: /Users/hal.hildebrand/git/gpu-support/resource/src/main/java/com/hellblazer/luciferase/resource/compute/
```

## Current Status

**Phase**: 1 (Core Interfaces)
**Progress**: Planning complete, ready for implementation
**Next Action**: Begin Phase 1 - Extract GPUBuffer interface

## Phase Overview

### Phase 1: Core Interfaces (Current)
Extract fundamental compute abstractions:
- **gpu-support-e63**: GPUBuffer interface
- **gpu-support-ad2**: ComputeKernel interface
- **gpu-support-9go**: GPUBackend enum (METAL, OPENCL, CPU_FALLBACK)
- **gpu-support-kdp**: GPUErrorClassifier (program vs recoverable errors)
- **gpu-support-ipz**: Phase 1 Unit Tests

**Parallel execution**: All four interface extractions can run independently.

### Phase 2: OpenCL Implementation (Blocked by Phase 1)
- **gpu-support-cbr**: BackendSelector (requires GPUBackend)
- **gpu-support-gij**: OpenCLContext singleton (requires Phase 1 tests)
- **gpu-support-6pw**: OpenCLKernel (requires gij + ad2)
- **gpu-support-ilr**: OpenCLBuffer (requires gij + e63)
- **gpu-support-97u**: Phase 2 Integration Tests

### Phase 3: Utilities (Blocked by Phase 2)
- KernelLoader extraction
- ComputeKernelFactory creation

### Phase 4: ART Migration (Blocked by Phase 3)
- Create shim classes in ART
- Update dependencies
- Deprecate original files

## Key Files and References

### ChromaDB (Knowledge Base)
- **ID**: `plan::gpu-support::art-opencl-extraction::v1`
- **Content**: Full strategic plan with architecture, phase details, test strategy
- **Search before work**: Always query for prior art and decisions

### Memory Bank (Session State)
- **Project**: `gpu-support_active`
- **Files**:
  - `extraction-plan-state.md` - Active session state
  - `hypotheses.md` - Active technical hypotheses
  - `blockers.md` - Current blockers

### Beads
- **Epic**: gpu-support-bsy - ART OpenCL Compute Infrastructure Extraction
- **Ready beads**: gpu-support-e63, gpu-support-ad2, gpu-support-9go, gpu-support-kdp
- **Update on completion**: Mark bead complete and reference in commit

## Critical Implementation Notes

### 1. Generalize Environment Variables
Replace ART-specific naming with generic names:
```
ART_GPU_BACKEND     → GPU_BACKEND
ART_GPU_DISABLE     → GPU_DISABLE
art.gpu.disable     → gpu.disable
```

### 2. Preserve macOS Cleanup Workaround
In OpenCLContext, skip cleanup to avoid SIGABRT:
```java
// LWJGL OpenCL has SIGABRT bug on macOS
// Only release if not on macOS or if explicitly enabled
if (!System.getProperty("os.name").contains("Mac") ||
    System.getProperty("gpu.cleanup.force", "false").equals("true")) {
    CL10.clReleaseContext(clContext);
}
```

### 3. Use CLBufferHandle from gpu-support
OpenCLBuffer integrates with existing `CLBufferHandle` from resource module. Don't create new resource wrapper.

### 4. Singleton with Reference Counting
OpenCLContext uses singleton pattern with reference counting. Preserve this for resource management:
- `getInstance()` - Get or create singleton
- `increment()` - Increment reference count
- `decrement()` - Decrement, release when count reaches 0
- `reset()` - For testing

## Testing Strategy

### Unit Tests (15 total)
Each extraction task includes unit tests validating the interface contract.

### Integration Tests
Phase tests validate interaction between components and with CLBufferHandle.

### CI Compatibility
Use `CICompatibleGPUTest` base class:
- Gracefully skips if OpenCL unavailable
- Provides mock platform for CI
- No test failures in GPU-less environments

### GPU Tests Require Sandbox Disable
```bash
# In bash calls to java-developer:
dangerouslyDisableSandbox: true
```

## Ready to Start

### Next Immediate Actions
1. Spawn java-developer agent with Phase 1 bead (gpu-support-e63)
2. Developer implements GPUBuffer extraction with TDD
3. Parallel work on other Phase 1 tasks
4. Audit with plan-auditor when Phase 1 complete

### Developer Handoff Template
```
## Handoff: java-developer

**Task**: Extract GPUBuffer interface from ART to gpu-support
**Bead**: gpu-support-e63 (status: pending)

### Input Artifacts
- ChromaDB: plan::gpu-support::art-opencl-extraction::v1
- Memory Bank: gpu-support_active/extraction-plan-state.md
- Source: /Users/hal.hildebrand/git/ART/.../GPUBuffer.java

### Deliverable
- GPUBuffer.java extracted to resource module
- Unit tests passing
- Generalized naming (no ART_ prefix)

### Quality Criteria
- [ ] Compiles without errors
- [ ] Tests pass in IDE and CLI
- [ ] No GPU required (pure interface)
- [ ] ResourceLifecycleTestSupport validates no leaks
- [ ] Bead marked complete
```

## Learnings

### Extracted Insights (L0)
1. **GPU Resource Management**: OpenCL contexts must use reference counting for proper lifecycle
2. **Platform Abstraction**: GPUBackend enum provides clean abstraction for future Metal/CUDA support
3. **Error Classification**: Distinguish program errors (recoverable) from driver errors (fatal)

### Technical Hypotheses (H0)
1. **Hypothesis**: Interface-based extraction allows clean separation from ART-specific code
   - **Status**: Validated by architecture review
   - **Evidence**: Clear interface contracts in ChromaDB plan

2. **Hypothesis**: macOS cleanup workaround is necessary for stability
   - **Status**: Requires validation during OpenCLContext extraction
   - **Action**: Test on macOS during Phase 2

## Success Metrics (Live)

| Metric | Target | Current | Status |
|--------|--------|---------|--------|
| Tests Passing | 15 | 0 | Pending |
| Phases Complete | 4 | 0 | Pending |
| Source Files Extracted | 9 | 0 | Pending |
| Resource Leaks | 0 | TBD | Pending |
| Code Review Passed | Yes | No | Pending |

## Context Protocol

### RECEIVE (Start of Session)
1. Check this CONTINUATION.md for phase and next action
2. Search ChromaDB: `plan::gpu-support::art-opencl-extraction::v1`
3. Read Memory Bank: `gpu-support_active/extraction-plan-state.md`
4. `bd list gpu-support-bsy` to see all beads

### PRODUCE (During Work)
- Update bead status: `bd update <id> --status in_progress`
- Store findings in ChromaDB with ID: `research::{phase}::{learning}`
- Update Memory Bank on blockers or decisions
- Commit with bead reference: "gpu-support-e63: Extract GPUBuffer interface"

### HANDOFF (Between Agents)
Include: (1) Bead ID, (2) ChromaDB references, (3) Input artifacts, (4) Quality criteria

## Blocked State
None currently. All Phase 1 tasks are ready to start.

## Contact Points

For questions about:
- **Plan architecture**: See `plan::gpu-support::art-opencl-extraction::v1` in ChromaDB
- **Phase details**: Read `.pm/execution_state.json`
- **Session state**: Check `gpu-support_active/extraction-plan-state.md` in Memory Bank
- **Task status**: `bd list gpu-support-bsy`

---

**Last Updated**: 2025-12-28 17:15:00
**Next Review**: After Phase 1 complete
