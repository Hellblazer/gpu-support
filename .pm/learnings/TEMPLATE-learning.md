# Learning Template

Document insights, patterns, and knowledge gained during implementation.

## L{N}: [Learning Title]

**Date**: [YYYY-MM-DD]
**Context**: [Phase, Bead(s), Task]
**Type**: [Architecture / Performance / Integration / Error Handling / Platform-Specific]

## The Learning

Clear, concise statement of the insight:

[1-2 paragraphs describing the learning]

### Why It Matters

How this insight impacts the project:

- [Impact 1]
- [Impact 2]
- [Impact 3]

### Evidence

Proof points for this learning:

1. **Code Evidence**: [Code snippet or file location]
   - What it shows: [Interpretation]

2. **Test Evidence**: [Test result or test name]
   - What it shows: [Interpretation]

3. **Measurement Evidence**: [Metric or benchmark]
   - What it shows: [Interpretation]

## Action Items

What to do with this learning:

- [ ] [Action 1 - e.g., Document in code comment]
- [ ] [Action 2 - e.g., Update METHODOLOGY.md]
- [ ] [Action 3 - e.g., Create decision in ChromaDB]

## Related Learnings

Links to related insights:

- **L0**: [Previous or related learning]
- **L1**: [Previous or related learning]
- **Future**: [Related topics to explore]

## ChromaDB Storage

When persisting to ChromaDB:
- **Document ID**: `research::{phase}::{topic}`
- **Metadata**: `{"phase": "1", "type": "architecture", "bead": "gpu-support-e63"}`

---

### Example: L0 - GPU Resource Reference Counting

**Date**: 2025-12-28
**Context**: Phase 1, All Core Interface Extraction Tasks
**Type**: Architecture

## The Learning

GPU resources (contexts, buffers, kernels) must use reference counting for proper lifecycle management in long-lived applications. A single OpenCLContext singleton with per-application reference counting prevents premature cleanup while ensuring cleanup when no longer needed.

### Why It Matters

- Prevents SEGFAULT from double-free (context released while still in use)
- Enables multiple threads/agents to safely share GPU context
- Simplifies resource management (explicit reference counting beats garbage collection for native resources)
- Critical for integration with Luciferase and ART

### Evidence

1. **Code Evidence**: ART's OpenCLContext implements reference counting:
   ```java
   private final AtomicInteger refCount = new AtomicInteger(0);
   public static OpenCLContext getInstance() {
       instance.refCount.incrementAndGet();
       return instance;
   }
   public void release() {
       if (refCount.decrementAndGet() == 0) {
           close();  // Only actually release when refCount reaches 0
       }
   }
   ```

2. **Test Evidence**: Resource lifecycle tests verify no double-close
   - Test: testNoDoubleRelease() validates refCount protection

3. **Measurement Evidence**: Thread safety under high concurrency
   - Benchmark: 100 threads accessing context simultaneously
   - Result: No SEGFAULT, proper cleanup when all threads done

## Action Items

- [ ] Document reference counting pattern in OpenCLContext implementation
- [ ] Create ChromaDB decision: `decision::gpu-context::reference-counting`
- [ ] Add to METHODOLOGY.md Resource Lifecycle section
- [ ] Include in code review checklist for Phase 2

## Related Learnings

- **Future**: L1 - GPU Context Invalidation (error recovery)
- **Future**: L2 - Thread-Safe Resource Pooling
