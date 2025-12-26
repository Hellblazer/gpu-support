package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for resource lifecycle tests.
 * Provides snapshot/diff/assert capabilities for detecting resource leaks.
 *
 * Tests extending this class can verify that components properly cleanup
 * all allocated resources (CLBufferHandle, CLProgramHandle, etc.).
 *
 * Usage Pattern:
 * <pre>
 * var before = captureSnapshot();
 * try (var component = new SomeComponent()) {
 *     component.doWork();
 * }
 * var after = captureSnapshot();
 * var report = diff(before, after);
 * assertNoLeaks(report);
 * </pre>
 */
public abstract class ResourceLifecycleTestSupport {

    protected ResourceTracker tracker;

    /**
     * Setup lifecycle testing infrastructure.
     * Initializes tracker to global instance by default.
     * Subclasses can override to use custom tracker.
     */
    @BeforeEach
    void setupLifecycleTesting() {
        tracker = ResourceTracker.getGlobalTracker();

        // Optional: Reset tracker for test isolation
        // tracker.reset();  // Uncomment if tests need clean slate
    }

    /**
     * Cleanup after lifecycle testing.
     * Forces cleanup of any remaining resources (emergency fallback).
     */
    @AfterEach
    void teardownLifecycleTesting() {
        // Emergency cleanup in case test failed before assertNoLeaks()
        if (tracker.getActiveCount() > 0) {
            System.err.println("WARNING: Active resources found after test - forcing cleanup");
            forceCleanupAll();
        }
    }

    /**
     * Capture snapshot of current resource state.
     *
     * @return Immutable snapshot of active resources
     */
    protected ResourceSnapshot captureSnapshot() {
        return new ResourceSnapshot(tracker);
    }

    /**
     * Compute diff between two snapshots.
     *
     * @param before Snapshot before component creation
     * @param after Snapshot after component cleanup
     * @return LeakReport analyzing the difference
     */
    protected LeakReport diff(ResourceSnapshot before, ResourceSnapshot after) {
        return LeakReport.diff(before, after);
    }

    /**
     * Assert that no resources leaked between snapshots.
     * Fails test with detailed report if any leaks detected.
     *
     * @param report The leak report to check
     */
    protected void assertNoLeaks(LeakReport report) {
        if (report.hasLeaks()) {
            fail("Resource leak detected:\n" + report.formatReport());
        }
    }

    /**
     * Assert exact leak count (for testing framework itself).
     *
     * @param report The leak report to check
     * @param expectedLeaks Expected number of leaks
     */
    protected void assertLeakCount(LeakReport report, int expectedLeaks) {
        assertEquals(expectedLeaks, report.getLeakedCount(),
            "Expected " + expectedLeaks + " leaks:\n" + report.formatReport());
    }

    /**
     * Assert exact freed count.
     *
     * @param report The leak report to check
     * @param expectedFreed Expected number of freed resources
     */
    protected void assertFreedCount(LeakReport report, int expectedFreed) {
        assertEquals(expectedFreed, report.getFreedCount(),
            "Expected " + expectedFreed + " freed resources");
    }

    /**
     * Force cleanup of all active resources (emergency).
     * Use in @AfterEach to prevent leak accumulation across tests.
     *
     * @return Number of resources cleaned up
     */
    protected int forceCleanupAll() {
        return tracker.forceCloseAll();
    }

    /**
     * Get current active resource count.
     * Useful for debugging test setup.
     */
    protected int getActiveResourceCount() {
        return tracker.getActiveCount();
    }
}
