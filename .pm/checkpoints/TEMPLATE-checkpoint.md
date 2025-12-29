# Checkpoint Template

Use this template to document fine-grained progress at task, day, phase, and milestone boundaries.

## Context

**Date**: [YYYY-MM-DD]
**Bead(s)**: [e.g., gpu-support-e63, gpu-support-ipz]
**Phase**: [1-4]
**Status**: [In Progress / Paused / Complete / Blocked]

## Work Completed

Summarize what was accomplished in this checkpoint period:

- [Task 1 completion]
- [Task 2 completion]
- [Subtask details if breaking down work]

### Code Artifacts
- [Files created/modified]
- [Number of lines changed]
- [Test coverage impact]

## Decisions Made

Document significant technical decisions:

1. **Decision**: [The decision]
   - **Rationale**: Why this choice
   - **Alternatives**: Other options considered
   - **Impact**: What changes as a result
   - **Stored in ChromaDB**: [Document ID or "Not yet"]

2. **Decision**: [...]

## Blockers Encountered

If any blockers were hit, document them:

1. **Blocker**: [Description]
   - **Impact**: Effect on progress
   - **Mitigation**: What we did about it
   - **Status**: Resolved / Escalated / In Progress
   - **Reference**: [Bead ID or ChromaDB doc]

2. **Blocker**: [...]

If no blockers, state: "No blockers encountered."

## Next Actions

Specific, actionable items for next checkpoint:

1. [Action 1]
2. [Action 2]
3. [Action 3]

## Metrics Update

Update these from execution_state.json:

| Metric | Previous | Current | Change |
|--------|----------|---------|--------|
| Tests Passing | [X] | [Y] | +[Y-X] |
| Source Files Extracted | [X] | [Y] | +[Y-X] |
| Code Review Status | [X] | [Y] | [Change] |
| Resource Leaks | [X] | [Y] | [Change] |

## Files Modified

List all files touched:

- `/path/to/file1.java` - [Purpose of changes]
- `/path/to/file2.java` - [Purpose of changes]
- `.pm/execution_state.json` - [Updated metrics]
- `pom.xml` - [Dependency changes if any]

## Related Learning

If this checkpoint generated insights:

- **Learning**: [Key insight]
  - **Evidence**: [How we know]
  - **Action**: What to do with this learning
  - **Stored in ChromaDB**: [Document ID or "Not yet"]

---

**Previous Checkpoint**: [Link or date]
**Next Checkpoint**: [Estimated date]
**Checkpoint Duration**: [Time spent since previous]
