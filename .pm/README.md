# GPU-Support Project Management Infrastructure

This directory contains project management infrastructure for the **GPU-Support OpenCL Compute Infrastructure Extraction** project.

## Quick Start

### Understanding the Project
1. **CONTINUATION.md** - Read this first for project context and next actions
2. **execution_state.json** - Detailed project state, phases, and metrics
3. **METHODOLOGY.md** - Engineering discipline and standards

### Starting Work
```bash
# View your task (find ready beads)
bd ready

# See epic and phase structure
bd list gpu-support-bsy

# Update your bead to in_progress
bd update gpu-support-e63 --status in_progress

# Work on task, following TDD: RED → GREEN → REFACTOR
# See METHODOLOGY.md for detailed workflow

# Mark complete when done
bd close gpu-support-e63
```

### Between Sessions
```bash
# Before closing editor
/check   # Saves context

# When resuming
/load    # Restores context
# Then read CONTINUATION.md for where you left off
```

## Directory Structure

```
.pm/
├── README.md                      # This file
├── CONTINUATION.md                # Resume context for next session
├── execution_state.json           # Project state tracking
├── METHODOLOGY.md                 # Engineering standards and discipline
├── CONTEXT_PROTOCOL.md            # Context management rules
├── AGENT_INSTRUCTIONS.md          # Instructions for spawned agents
├── checkpoints/                   # Fine-grained progress tracking
│   ├── TEMPLATE-checkpoint.md
│   ├── phase1-checkpoint.md       # Created during Phase 1
│   └── ...
├── learnings/                     # Accumulated knowledge
│   ├── TEMPLATE-learning.md
│   ├── L0-gpu-resource-management.md
│   └── ...
├── hypotheses/                    # Technical hypotheses and validation
│   ├── TEMPLATE-hypothesis.md
│   ├── H0-interface-extraction.md
│   └── ...
├── audits/                        # Quality gates and reviews
│   ├── phase1-audit.md            # Plan-auditor review output
│   └── ...
├── thinking/                      # Deep analysis sessions
│   ├── phase1-design.md           # Phase 1 design decisions
│   └── ...
└── metrics/                       # Performance and progress tracking
    ├── phase1-metrics.md          # Phase 1 performance data
    └── ...
```

## Core Files Explained

### execution_state.json
Central state tracking for the project. Updated at phase completion:
- **project**: Basic metadata (name, repo, branch, version)
- **overview**: Objective, scope, timeline
- **phases**: Array of all 4 phases with status and tasks
- **current_phase**: Which phase is active
- **status**: Overall project status
- **success_criteria**: Definition of success for the project
- **blockers**: Current blockers (if any)
- **metrics**: Test count, files extracted, etc.

### CONTINUATION.md
Resume context - read this at session start:
- Current phase and next action
- Quick reference to key files and beads
- Critical implementation notes (macOS workaround, env var generalization)
- Testing strategy
- Ready to start checklist

### METHODOLOGY.md
Engineering discipline standards:
- TDD workflow (RED → GREEN → REFACTOR)
- Source code extraction process (6 steps)
- Naming conventions (packages, classes, env vars, beads)
- Code quality standards
- Testing standards (unit, integration, leak detection)
- Commit guidelines with examples
- Code review checklist
- Documentation standards
- Troubleshooting common issues

## Key Integration Points

### ChromaDB (Knowledge Base)
Search before starting work:
```bash
# Full strategic plan with all architecture details
search: "plan gpu-support art opencl extraction"
document: plan::gpu-support::art-opencl-extraction::v1
```

### Memory Bank (Session State)
Active project: `gpu-support_active`
- `extraction-plan-state.md` - Bead structure and ready tasks
- `hypotheses.md` - Active technical decisions
- `blockers.md` - Current blockers

### Beads (Task Tracking)
Primary task tracking system:
- **Epic**: gpu-support-bsy - ART OpenCL Compute Infrastructure Extraction
- **Phase Beads**: gpu-support-6e9 (Phase 1), gpu-support-5wc (Phase 2), etc.
- **Task Beads**: gpu-support-e63, gpu-support-ad2, gpu-support-9go, etc.

## Workflow

### Phase Workflow
```
1. Read CONTINUATION.md for phase overview and ready tasks
2. Spawn agents (typically java-developer for implementation)
3. Follow METHODOLOGY.md standards
4. Update Memory Bank with blockers/decisions
5. Complete phase tasks, all tests GREEN
6. Audit with plan-auditor
7. Mark phase beads complete
8. Update execution_state.json
9. Document learnings in LEARNINGS.md
```

### Daily Workflow
1. **Start**: `bd ready` - View unblocked tasks
2. **Work**: Update bead to in_progress, implement with TDD
3. **Test**: Run tests locally and in CI
4. **Review**: Self-review against checklist
5. **Commit**: Reference bead ID in commit message
6. **Mark Complete**: `bd close <bead-id>`

### Session Transitions
- **End of session**: `/check` to save context
- **Start of session**: `/load` to restore context, read CONTINUATION.md
- **Long break**: Update CONTINUATION.md with current state before break

## Success Criteria

Project is complete when:

1. **All extracted code compiles and passes tests**
   - Metric: 15 tests passing (4 + 5 + 2 + 1 + 3 integration)
   - Validation: CI runs clean, all tests GREEN

2. **ART can switch to gpu-support's compute infrastructure**
   - Metric: Shim classes created, ART imports updated
   - Validation: ART build succeeds with gpu-support dependency

3. **Luciferase can use the infrastructure for ESVO**
   - Metric: Integration confirmed
   - Validation: ESVO tests use gpu-support compute infrastructure

4. **CI runs tests gracefully without GPU**
   - Metric: Tests skip/pass in CI (CICompatibleGPUTest)
   - Validation: CI pipeline succeeds

5. **No resource leaks verified by ResourceLifecycleTestSupport**
   - Metric: Zero leaks in all tests
   - Validation: ResourceTracker clean after test suite

## Templates

### Checkpoint Template
See `checkpoints/TEMPLATE-checkpoint.md`
- Context (phase, blockers, status)
- Work completed
- Decisions made
- Blockers encountered
- Next actions
- Metrics update
- Files modified

### Learning Template
See `learnings/TEMPLATE-learning.md`
- Date
- Context
- The learning
- Why it matters
- Evidence
- Action items
- Related learnings

### Hypothesis Template
See `hypotheses/TEMPLATE-hypothesis.md`
- Date proposed
- Status (Active, Validated, Refuted)
- The hypothesis
- Rationale
- Validation criteria
- Testing approach
- Results
- Conclusion
- Impact
- Related hypotheses

## Advanced Features

### ChromaDB Integration
Store validated findings with proper IDs:
```
{domain}::{agent-type}::{topic}

Example:
research::phase1::gpu-buffer-design
decision::architecture::environment-variables
pattern::extraction::generalize-naming
debug::mac-cleanup::sigabrt-handling
```

### Memory Bank Coordination
For agent handoffs:
1. Update `gpu-support_active/hypotheses.md` with active decisions
2. Update `gpu-support_active/blockers.md` if encountering issues
3. Include file paths and context IDs in handoff

### Bead Relationships
- Dependencies: `bd dep add <id> <blocker-id>`
- Phases: Link to epic with design field
- Tracking: Update description with status notes

## Customization for This Project

### Java-Specific
- JVM args already configured in pom.xml
- LWJGL OpenCL dependency required
- GPU tests need `dangerouslyDisableSandbox: true` in Bash tool
- ResourceTracker validates no leaks

### Phase-Specific
- Phase 1: Pure interfaces, no GPU needed
- Phase 2: OpenCL implementation, GPU optional for tests
- Phase 3: Utilities, GPU optional
- Phase 4: ART migration, no GPU needed

### Critical Implementations
- **macOS cleanup workaround**: Document clearly in code
- **Environment variable generalization**: Search/replace systematically
- **CLBufferHandle integration**: Use existing resource wrapper
- **Singleton reference counting**: Preserve OpenCLContext pattern

## Contacts and Resources

### For Questions About...

| Topic | Resource | Location |
|-------|----------|----------|
| **Full Architecture** | Strategic Plan | ChromaDB `plan::gpu-support::...` |
| **Phase Details** | Execution State | `.pm/execution_state.json` |
| **Session Context** | CONTINUATION.md | `.pm/CONTINUATION.md` |
| **Engineering Standards** | METHODOLOGY.md | `.pm/METHODOLOGY.md` |
| **Task Status** | Beads | `bd list gpu-support-bsy` |
| **Session State** | Memory Bank | `gpu-support_active/*` |

### Before Starting Work
1. Read CONTINUATION.md (5 min)
2. Search ChromaDB for plan (2 min)
3. Check Memory Bank for active state (2 min)
4. List beads: `bd list gpu-support-bsy` (1 min)
5. Total: 10 minutes of context gathering

### Problem Solving
1. Check METHODOLOGY.md troubleshooting section
2. Search ChromaDB for related decisions
3. Update Memory Bank with blocker
4. Escalate to plan-auditor if stuck >2 hours

## Metrics Dashboard

Current project metrics (updated at phase completion):

| Metric | Target | Current | %Complete |
|--------|--------|---------|-----------|
| **Tests Passing** | 15 | 0 | 0% |
| **Phases Complete** | 4 | 0 | 0% |
| **Source Files Extracted** | 9 | 0 | 0% |
| **Resource Leaks** | 0 | 0 | ✓ |
| **Code Review Passed** | Yes | Pending | - |

Updates: Post-phase-complete

## Next Steps

1. Read CONTINUATION.md for current phase context
2. Review execution_state.json for detailed state
3. Search ChromaDB for full strategic plan
4. Check Memory Bank for active decisions
5. `bd list gpu-support-bsy` to see all tasks
6. Start with `gpu-support-e63`: Extract GPUBuffer interface

---

**Version**: 1.0
**Created**: 2025-12-28 17:15:00
**Last Updated**: 2025-12-28 17:15:00
**Maintained By**: Project Infrastructure
