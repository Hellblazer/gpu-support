package com.hellblazer.luciferase.resource.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceLifecycleTestSupport base class.
 *
 * This test class extends the base class it's testing, demonstrating the
 * typical usage pattern for component lifecycle testing.
 */
class ResourceLifecycleTestSupportTest extends ResourceLifecycleTestSupport {

    private ResourceTracker testTracker;

    @BeforeEach
    void setUp() {
        // Use a fresh tracker for testing
        testTracker = new ResourceTracker();
    }

    @Test
    void testCaptureSnapshotEmpty() {
        var snapshot = captureSnapshot();

        assertNotNull(snapshot);
        assertEquals(0, snapshot.getActiveCount());
    }

    @Test
    void testCaptureSnapshotWithResources() {
        var tracker = ResourceTracker.getGlobalTracker();
        var before = captureSnapshot();

        // Get initial count
        int initialCount = before.getActiveCount();

        // Capture another snapshot
        var after = captureSnapshot();

        assertEquals(initialCount, after.getActiveCount());
    }

    @Test
    void testDiffNoLeaks() {
        var tracker = ResourceTracker.getGlobalTracker();
        var before = captureSnapshot();
        var after = captureSnapshot();

        var report = diff(before, after);

        assertNotNull(report);
        assertFalse(report.hasLeaks());
        assertEquals(0, report.getNetLeaks());
    }

    @Test
    void testAssertNoLeaksPass() {
        var before = captureSnapshot();
        var after = captureSnapshot();
        var report = diff(before, after);

        // Should not throw
        assertNoLeaks(report);
    }

    @Test
    void testAssertNoLeaksFail() {
        var tracker = ResourceTracker.getGlobalTracker();

        // Create a leaked resource mock (we can't actually create one without GPU context)
        // Instead, we create a report with leaked resources
        var before = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            0L,
            0L
        );

        var leakedByType = new java.util.HashMap<Class<?>, java.util.List<ResourceInfo>>();
        leakedByType.put(
            String.class,
            java.util.List.of(new ResourceInfo("test-resource", String.class, 100L, null))
        );

        var after = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            1L,
            0L
        );

        var report = new LeakReport(leakedByType, before, after);

        // Should throw AssertionError with leak details
        var error = assertThrows(AssertionError.class, () -> assertNoLeaks(report));
        assertTrue(error.toString().contains("Resource leaks detected"));
    }

    @Test
    void testForceCleanup() {
        var tracker = ResourceTracker.getGlobalTracker();
        var initialCount = tracker.getActiveCount();

        // Force cleanup (should handle empty case gracefully)
        forceCleanup();

        // Tracker should be cleaned
        assertEquals(initialCount, tracker.getActiveCount());
    }

    @Test
    void testSnapshotTimestamp() {
        var snapshot = captureSnapshot();

        assertNotNull(snapshot.getTimestamp());
        assertTrue(snapshot.getTimestamp() > 0);
    }

    @Test
    void testMultipleSnapshots() {
        var snapshot1 = captureSnapshot();
        var snapshot2 = captureSnapshot();

        // Both should be valid
        assertNotNull(snapshot1);
        assertNotNull(snapshot2);

        // Snapshots can have same or different timestamps (likely different)
        assertTrue(snapshot1.getTimestamp() >= 0);
        assertTrue(snapshot2.getTimestamp() >= 0);
    }

    @Test
    void testDiffWithEmptyBefore() {
        var before = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            0L,
            0L
        );

        var after = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            0L,
            0L
        );

        var report = diff(before, after);
        assertFalse(report.hasLeaks());
    }

    @Test
    void testReportWithMultipleTypes() {
        var before = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            0L,
            0L
        );

        var leakedByType = new java.util.HashMap<Class<?>, java.util.List<ResourceInfo>>();
        leakedByType.put(
            String.class,
            java.util.List.of(
                new ResourceInfo("buffer-1", String.class, 100L, null),
                new ResourceInfo("buffer-2", String.class, 95L, null)
            )
        );
        leakedByType.put(
            Integer.class,
            java.util.List.of(new ResourceInfo("program-1", Integer.class, 50L, null))
        );

        var after = new ResourceSnapshot(
            System.nanoTime(),
            new java.util.HashMap<>(),
            3L,
            0L
        );

        var report = new LeakReport(leakedByType, before, after);

        assertEquals(3, report.getNetLeaks());
        assertEquals(2, report.getLeakCount(String.class));
        assertEquals(1, report.getLeakCount(Integer.class));
    }

    @Test
    void testCaptureSnapshotIsIndependent() {
        var snapshot1 = captureSnapshot();
        var snapshot2 = captureSnapshot();

        // Both snapshots should be independent
        assertNotNull(snapshot1);
        assertNotNull(snapshot2);

        // They should have equal counts (no resources created between them)
        assertEquals(snapshot1.getActiveCount(), snapshot2.getActiveCount());
    }
}
