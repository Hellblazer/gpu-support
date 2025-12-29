# Context Protocol: GPU-Support OpenCL Extraction

This document defines how context flows between sessions and agents for this project.

## Session Lifecycle

### SessionStart Hook
When Claude Code starts a new session:
1. **Auto-load .pm/ context**: SessionStart hook loads `CONTINUATION.md`
2. **Check status**: Review `.pm/execution_state.json`
3. **Load active state**: Read `gpu-support_active/` from Memory Bank
4. **List ready beads**: `bd list gpu-support-bsy --status=ready`
5. **Proceed**: Follow CONTINUATION.md next actions

No manual action required - hook handles automatically.

### During Work

**RECEIVE Phase** (Before Starting Task):
1. **Bead Context**: `bd show <id>` to see task details and design field
2. **ChromaDB Search**: Query `plan::gpu-support::art-opencl-extraction::v1` for architecture
3. **Memory Bank**: Read `gpu-support_active/extraction-plan-state.md` for active state
4. **Files**: Check which source files are referenced

**PRODUCE Phase** (During Implementation):
1. **Update Bead**: `bd update <id> --status in_progress`
2. **Implement**: Follow METHODOLOGY.md test-first workflow
3. **Store Findings**: Create ChromaDB documents for decisions (ID: `research::{phase}::{topic}`)
4. **Memory Updates**: Update `gpu-support_active/hypotheses.md` with active decisions
5. **Commit**: Reference bead ID and epic in commit message

**HANDOFF Phase** (To Next Agent):
1. **Prepare Input**: Stage all artifacts (code, tests, documentation)
2. **Update Bead**: Mark as blocked or complete with notes
3. **Create Handoff**: Use standardized format below
4. **Update Memory**: Flag transition in Memory Bank
5. **Document**: Store decision/context in ChromaDB

### PreCompact Hook
Before editor compaction reminder:
- Review current session state
- Decide: continue session or save and close
- If saving: use `/check` to save continuation context
- Update CONTINUATION.md with latest phase status

## HANDOFF Format (Standard)

When passing work between agents, always use this structure:

```
## Handoff: [Target Agent Name]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: [status])

### Input Artifacts

**ChromaDB**:
- `plan::gpu-support::art-opencl-extraction::v1` - Full strategic plan
- [Other relevant document IDs]

**Memory Bank**:
- Project: `gpu-support_active`
- Files: `extraction-plan-state.md`, [others]

**Files**:
- Source: `/Users/hal.hildebrand/git/ART/path/to/GPUBuffer.java`
- Target: `/Users/hal.hildebrand/git/gpu-support/resource/src/main/java/com/hellblazer/luciferase/resource/compute/`
- Test: [test file location]

### Deliverable

[What the receiving agent should produce]

### Quality Criteria

- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]
- [ ] [Criterion 4]

### Context Notes

[Special context, platform-specific notes, known issues, or constraints]
```

### Example Handoff

```
## Handoff: java-developer

**Task**: Extract GPUBuffer interface from ART to gpu-support with TDD
**Bead**: gpu-support-e63 (status: pending)

### Input Artifacts

**ChromaDB**:
- `plan::gpu-support::art-opencl-extraction::v1` - Full plan with Phase 1 details
- `research::phase1::gpu-buffer-design` - (create during work)

**Memory Bank**:
- Project: `gpu-support_active`
- Files: `extraction-plan-state.md` (ready beads list)

**Files**:
- Source: `/Users/hal.hildebrand/git/ART/art-modules/art-cortical/src/main/java/com/hellblazer/art/cortical/gpu/GPUBuffer.java`
- Target: `/Users/hal.hildebrand/git/gpu-support/resource/src/main/java/com/hellblazer/luciferase/resource/compute/GPUBuffer.java`
- Tests: `src/test/java/.../GPUBufferTest.java`

### Deliverable

**GPUBuffer interface** extracted to gpu-support with:
- All methods documented
- Generic naming (no ART_ prefix)
- 4 unit tests passing
- ResourceTracker integration
- Ready for MockGPU testing in CI

### Quality Criteria

- [ ] Source compiles without errors
- [ ] All 4 tests pass (RED → GREEN → REFACTOR workflow)
- [ ] No ART-specific naming or imports remain
- [ ] Environment variables generalized (ART_GPU_* → GPU_*)
- [ ] JavaDoc complete for all methods
- [ ] Bead gpu-support-e63 marked complete
- [ ] Commit includes bead reference and epic link
- [ ] Code review passed (review checklist in METHODOLOGY.md)

### Context Notes

**Critical Implementation Details**:
- This is a pure interface extraction - no GPU code needed
- Remove all ART imports (com.hellblazer.art.*)
- Generalize package: `com.hellblazer.art.cortical.gpu` → `com.hellblazer.luciferase.resource.compute`
- Generalize environment variables: `ART_GPU_BACKEND` → `GPU_BACKEND`

**Testing Strategy**:
- Write failing test first (RED)
- Implement interface contract (GREEN)
- Refactor and add edge case tests
- All tests must pass locally and in CI (no GPU required)

**Integration Notes**:
- Part of Phase 1 (Core Interfaces) - other tasks run in parallel
- Depends on nothing
- Blocks: gpu-support-ipz (Phase 1 Tests)

**References**:
- Source location: ART cortical GPU module
- Architecture: See plan in ChromaDB
- Standards: METHODOLOGY.md has extraction process (6 steps)
- Session context: CONTINUATION.md has phase overview
```

## Context Recovery (If Missing)

If expected context is not available:

### Step 1: Search ChromaDB
```
Query: "plan gpu-support art opencl extraction"
Should return: plan::gpu-support::art-opencl-extraction::v1
Contains: Full architecture, phases, test strategy, success criteria
```

### Step 2: Check Memory Bank
```
Project: gpu-support_active
Files: extraction-plan-state.md (bead structure)
      hypotheses.md (active decisions)
      blockers.md (any blockers)
```

### Step 3: Query Beads
```bash
bd list gpu-support-bsy              # All project beads
bd list gpu-support-bsy --status=ready  # Unblocked work
bd show <id>                         # Specific task details
```

### Step 4: Document Assumptions
```
If context cannot be found:
1. Record in bead description what you assumed
2. Update Memory Bank with assumptions
3. Flag in downstream handoff
4. Request clarification from plan-auditor
```

### Step 5: Escalate
If context missing for >30 minutes:
- Create bead: "Resolve context gap"
- Update Memory Bank: gpu-support_active/blockers.md
- Request help from orchestrator or strategic-planner

## Storage Hierarchy

### Level 1: Beads (Task Tracking)
- **Primary storage** for task status, dependencies, blockers
- Updated in real-time
- Always current

### Level 2: ChromaDB (Knowledge Base)
- **Persistent storage** for decisions, research, patterns
- Updated at phase completion or when major decision made
- Never deleted (searchable archive)

### Level 3: Memory Bank (Session State)
- **Ephemeral storage** for active work coordination
- Updated throughout session
- Cleared/archived when session ends
- Only for current session scope

### Level 4: .pm/ Infrastructure
- **Project infrastructure** defining how work flows
- Updated at project setup and major methodology changes
- Not for task tracking (use beads)
- Not for knowledge (use ChromaDB)

## Naming Conventions

### ChromaDB Document IDs
Format: `{domain}::{agent-type}::{topic}`

```
Decision:     decision::{component}::{decision-name}
              decision::architecture::gpu-platform-abstraction
              decision::gpu-context::reference-counting

Research:     research::{phase}::{topic}
              research::phase1::gpu-buffer-design
              research::phase2::opencl-context-lifecycle

Pattern:      pattern::{name}::{variant}
              pattern::extraction::generalize-naming
              pattern::testing::mock-gpu-environment

Debug:        debug::{issue}::{platform}
              debug::mac-cleanup::sigabrt-handling
              debug::ci-detection::missing-opencl
```

### Memory Bank Files
Format: `{project}_active/{phase-or-topic}.md`

```
gpu-support_active/extraction-plan-state.md     # Bead structure, ready tasks
gpu-support_active/hypotheses.md                # Active technical hypotheses
gpu-support_active/blockers.md                  # Current blockers
gpu-support_active/phase1-progress.md           # Phase 1 in-progress state
```

### Bead IDs
Format: `{project}-{identifier}`

```
Epic:        gpu-support-bsy (Epic - ART OpenCL Extraction)
Phase:       gpu-support-6e9 (Feature - Phase 1)
Task:        gpu-support-e63 (Task - Extract GPUBuffer)
```

## Context Loss Prevention

### Checkpoint Strategy
After each phase completion:
1. Create checkpoint in `.pm/checkpoints/phase{N}-complete.md`
2. Store key decisions in ChromaDB
3. Update CONTINUATION.md with phase results
4. Archive Memory Bank to dated file

### Session Boundaries
- **End of session**: `/check` to auto-save context
- **Before big break**: Manually update CONTINUATION.md
- **Complex decision**: Create ChromaDB document
- **Major blocker**: Update Memory Bank `blockers.md`

### Recovery from Loss
If session context lost:
1. **Immediate**: Review `.pm/checkpoints/` for last saved state
2. **Short-term**: Check Memory Bank for most recent updates
3. **Long-term**: Search ChromaDB for decision history
4. **Latest**: Read CONTINUATION.md for intended next action

## Integration with Other Agents

### Strategic Planner
- **Sends**: Project plan, scope, timeline
- **Receives**: Infrastructure ready for execution
- **Context**: ChromaDB plan document, CONTINUATION.md
- **Handoff**: Standard format with all artifacts

### Plan Auditor
- **Sends**: Audit results, change requests
- **Receives**: Code for review, documentation for validation
- **Context**: execution_state.json for metrics, bead status
- **Handoff**: Code review checklist in METHODOLOGY.md

### Java Developer
- **Sends**: Completed implementations, tests, decisions
- **Receives**: Bead and architecture context
- **Context**: CONTINUATION.md phase overview, ChromaDB plan
- **Handoff**: Input artifacts with clear deliverables

### Knowledge Tidier
- **Sends**: Refined knowledge, organized findings
- **Receives**: Raw learnings, decisions, research
- **Context**: Memory Bank raw state, ChromaDB recent adds
- **Handoff**: Learning template and hypothesis format

## Validation

Before claiming context available:
- [ ] CONTINUATION.md current and actionable
- [ ] execution_state.json valid JSON
- [ ] ChromaDB plan document retrievable
- [ ] Memory Bank files readable
- [ ] Beads list shows current status
- [ ] No contradictions between sources
- [ ] All next actions clear

## Version Control

This protocol is versioned with the project:
- **Version**: 1.0
- **Created**: 2025-12-28
- **Last Updated**: 2025-12-28
- **Maintained By**: Project Infrastructure

Changes to context protocol:
1. Update this document
2. Notify all active agents
3. Create decision in ChromaDB: `decision::context-protocol::change`
4. Reference in commit message

---

For questions about context management, refer to:
- **CONTINUATION.md**: Session resume context
- **execution_state.json**: Current project state
- **METHODOLOGY.md**: Engineering standards
- **README.md**: Quick start and file overview
