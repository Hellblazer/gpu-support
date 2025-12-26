# Resource Lifecycle Testing Framework

Portable, reusable testing framework for resource lifecycle verification. Provides snapshot/diff/assert capabilities for detecting resource leaks in components using the ResourceTracker/ResourceHandle pattern.

## Overview

This module provides a comprehensive testing infrastructure for verifying proper resource lifecycle management. It's technology-agnostic and works with any resource type (GPU buffers, file handles, network connections, etc.) that uses the ResourceTracker/ResourceHandle pattern.

## Key Features

- **Snapshot-based testing**: Capture resource state before/after component operations
- **Leak detection**: Identify resources that weren't properly cleaned up
- **Freed resource tracking**: Verify resources were correctly released
- **Type-aware reporting**: Group leaks by resource type with stack traces
- **Thread-safe**: Safe for concurrent testing scenarios
- **Zero dependencies**: Only requires JUnit 5 and the resource module

## Architecture

The framework consists of 4 core classes:

1. **ResourceInfo** (record): Immutable snapshot of resource metadata (ID, type, age, stack trace)
2. **ResourceSnapshot**: Captures active resources at a point in time, grouped by type
3. **LeakReport**: Analyzes differences between snapshots (leaked/freed/persistent)
4. **ResourceLifecycleTestSupport**: Abstract JUnit base class with snapshot/diff/assert helpers

## Usage Examples

### Basic Usage Pattern

```java
class MyComponentTest extends ResourceLifecycleTestSupport {

    @Test
    void testNoResourceLeaks() {
        var before = captureSnapshot();

        try (var component = new MyComponent()) {
            component.doWork();
        }

        var after = captureSnapshot();
        var report = diff(before, after);

        assertNoLeaks(report);  // Fails with detailed report if leaks detected
    }
}
```

### Advanced Usage: Multiple Snapshots

```java
@Test
void testComplexLifecycle() {
    var initial = captureSnapshot();

    var resource1 = createResource();
    var afterCreate1 = captureSnapshot();

    var resource2 = createResource();
    var afterCreate2 = captureSnapshot();

    resource1.close();
    var afterClose1 = captureSnapshot();

    // Verify resource1 was freed
    var report = diff(afterCreate2, afterClose1);
    assertFreedCount(report, 1);
    assertLeakCount(report, 0);

    resource2.close();
    var final = captureSnapshot();

    // Verify all resources cleaned up
    var finalReport = diff(initial, final);
    assertNoLeaks(finalReport);
}
```

### Manual Usage (Without Base Class)

```java
@Test
void testManualSnapshot() {
    var tracker = ResourceTracker.getGlobalTracker();

    var before = new ResourceSnapshot(tracker);

    var handle = new MyResourceHandle();
    handle.close();

    var after = new ResourceSnapshot(tracker);
    var report = LeakReport.diff(before, after);

    assertFalse(report.hasLeaks());
    assertEquals(1, report.getFreedCount());
}
```

## API Reference

### ResourceLifecycleTestSupport

Abstract base class providing lifecycle testing helpers:

- `captureSnapshot()`: Capture current resource state
- `diff(before, after)`: Compute leak report between snapshots
- `assertNoLeaks(report)`: Fail test if any leaks detected
- `assertLeakCount(report, count)`: Assert exact leak count
- `assertFreedCount(report, count)`: Assert exact freed count
- `forceCleanupAll()`: Emergency cleanup of all active resources
- `getActiveResourceCount()`: Get current active resource count

### ResourceSnapshot

Immutable snapshot of resource state:

- `getTotalCount()`: Total resources in snapshot
- `getResourceTypes()`: Set of resource types present
- `getResourcesByType(type)`: List of resources of specific type
- `getResourceById(id)`: Lookup resource by ID

### LeakReport

Diff analysis between two snapshots:

- `hasLeaks()`: Check if any resources leaked
- `getLeakedCount()`: Count of leaked resources
- `getFreedCount()`: Count of properly freed resources
- `getPersistentCount()`: Count of persistent resources
- `formatReport()`: Human-readable report with stack traces

### ResourceInfo

Immutable resource metadata (record):

- `id()`: Unique resource identifier
- `type()`: Resource type (class simple name)
- `ageMillis()`: Resource age at snapshot time
- `allocationStack()`: Allocation stack trace (nullable)

## Integration

### Maven Dependency

```xml
<dependency>
    <groupId>com.hellblazer.art</groupId>
    <artifactId>resource-lifecycle-testing</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### Requirements

- Java 24+
- JUnit 5
- resource module (com.hellblazer.art:resource)

## Testing

The module includes comprehensive self-tests:

- **ResourceInfoTest**: Record contract and factory method
- **ResourceSnapshotTest**: Snapshot capture and queries (7 tests)
- **LeakReportTest**: Diff algorithm and formatting (8 tests)
- **ResourceLifecycleTestSupportTest**: Base class contract (8 tests)
- **ResourceLifecycleIntegrationTest**: End-to-end workflows (6 tests)

Run tests: `mvn test -pl resource-lifecycle-testing`

## Design Principles

1. **Technology-agnostic**: Works with any ResourceHandle implementation
2. **Immutable snapshots**: Thread-safe, no side effects
3. **Zero-cost abstractions**: Minimal overhead for production code
4. **Clear test failures**: Detailed reports with stack traces
5. **Flexible workflows**: Support for complex multi-stage tests

## Example Output

When a leak is detected, you get a detailed report:

```
Resource Lifecycle Report
========================
Duration: 150 ms
Leaked: 2
Freed: 1
Persistent: 0

LEAKED RESOURCES:
  CLBufferHandle: 2 instances
    - buffer-123 (age: 100 ms)
      Allocated at:
        at com.example.MyComponent.createBuffer(MyComponent.java:42)
        at com.example.MyComponent.doWork(MyComponent.java:30)
    - buffer-456 (age: 50 ms)
      Allocated at:
        at com.example.MyComponent.createBuffer(MyComponent.java:42)
        at com.example.MyComponent.doMoreWork(MyComponent.java:35)
```

## Best Practices

1. **Always extend ResourceLifecycleTestSupport** for lifecycle tests
2. **Capture snapshots around component boundaries** (before creation, after cleanup)
3. **Use assertNoLeaks() as final assertion** in every lifecycle test
4. **Enable allocation stack traces** in ResourceTracker for debugging
5. **Force cleanup in @AfterEach** to prevent leak accumulation

## License

GNU Affero General Public License V3
