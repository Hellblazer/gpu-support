package com.hellblazer.luciferase.resource.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceTracker;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Base class for resource lifecycle testing with snapshot/diff/assert capabilities.
 *
 * Provides three core operations:
 * 1. captureSnapshot() - Capture current resource state from ResourceTracker
 * 2. diff() - Compare two snapshots to detect resource changes
 * 3. assertNoLeaks() - Assert that no leaks were detected (zero tolerance)
 *
 * Typical usage:
 * <pre>
 * @Test
 * void testComponentLifecycle() {
 *     var before = captureSnapshot();
 *
 *     try (var component = createComponent()) {
 *         exerciseComponent(component);
 *     } // AutoCloseable cleanup
 *
 *     var after = captureSnapshot();
 *     var report = diff(before, after);
 *
 *     assertNoLeaks(report); // Zero tolerance
 * }
 * </pre>
 *
 * Key design principles:
 * - No GC dependency: close() synchronously unregisters resources
 * - Zero leak tolerance: Any leak causes test failure
 * - Fast feedback: &lt;100ms per test
 * - Existing infrastructure: Uses ResourceTracker API without modification
 */
public class ResourceLifecycleTestSupport {

    /**
     * Capture current resource state from the global ResourceTracker.
     *
     * Creates an immutable snapshot of all active resources at this moment,
     * grouped by type. Can be compared with another snapshot to detect leaks.
     *
     * @return Immutable snapshot of current resource state
     */
    protected ResourceSnapshot captureSnapshot() {
        var tracker = ResourceTracker.getGlobalTracker();
        return ResourceSnapshot.captureSnapshot(tracker);
    }

    /**
     * Compare two snapshots to detect resource leaks.
     *
     * Identifies resources that exist in the "after" snapshot but not
     * in the "before" snapshot. These are considered leaked resources.
     *
     * @param before Snapshot before the operation
     * @param after Snapshot after the operation
     * @return Leak report with detailed analysis
     */
    protected LeakReport diff(ResourceSnapshot before, ResourceSnapshot after) {
        Map<Class<?>, java.util.List<ResourceInfo>> leakedByType = new HashMap<>();

        for (var entry : after.getActiveResourcesByType().entrySet()) {
            var type = entry.getKey();
            var afterIds = entry.getValue();
            var beforeIds = before.getActiveResourcesByType().getOrDefault(type, java.util.Set.of());

            // Find resources that exist after but not before
            var leaked = afterIds.stream()
                .filter(id -> !beforeIds.contains(id))
                .map(id -> {
                    var tracker = ResourceTracker.getGlobalTracker();
                    var resource = tracker.getResource(id);
                    return new ResourceInfo(
                        id,
                        type,
                        resource != null ? resource.getAgeMillis() : 0,
                        resource != null ? resource.getAllocationStack() : null
                    );
                })
                .collect(Collectors.toList());

            if (!leaked.isEmpty()) {
                leakedByType.put(type, leaked);
            }
        }

        return new LeakReport(leakedByType, before, after);
    }

    /**
     * Assert that no resource leaks were detected (zero tolerance).
     *
     * Fails the test if the report indicates any leaks, providing a
     * detailed error message with leak information for debugging.
     *
     * @param report The leak report to check
     * @throws AssertionError If leaks are detected
     */
    protected void assertNoLeaks(LeakReport report) {
        if (report.hasLeaks()) {
            throw new AssertionError("Resource leaks detected:\n" + report.toString());
        }
    }

    /**
     * Assert acceptable leaks for specific types (for gradual migration).
     *
     * Use sparingly - the goal is zero leaks for all components.
     * This method is provided for temporary use while fixing existing leaks.
     *
     * @param report The leak report to check
     * @param threshold Maximum acceptable leak count
     * @param types Resource types to check (can check subset of types)
     * @throws AssertionError If leaks exceed threshold for any specified type
     */
    protected void assertAcceptableLeaks(LeakReport report, int threshold, Class<?>... types) {
        for (var type : types) {
            var count = report.getLeakCount(type);
            if (count > threshold) {
                throw new AssertionError(String.format("Too many %s leaks: %d (threshold: %d)\n%s",
                    type.getSimpleName(), count, threshold, report.toString()));
            }
        }
    }

    /**
     * Force cleanup of all tracked resources (emergency teardown).
     *
     * Calls close() on all active resources in the ResourceTracker.
     * Use in @AfterEach to ensure cleanup even if test fails.
     *
     * Logs warnings if close() fails - does not throw exceptions.
     */
    protected void forceCleanup() {
        var tracker = ResourceTracker.getGlobalTracker();
        tracker.forceCloseAll();
    }
}
