package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ResourceLifecycle framework with real ResourceTracker.
 * Validates end-to-end workflow with actual ResourceHandle instances.
 */
class ResourceLifecycleIntegrationTest {

    @Test
    void testWithRealTracker() {
        // Use global ResourceTracker, real ResourceHandle implementation
        var tracker = ResourceTracker.getGlobalTracker();

        // Create real ResourceHandle BEFORE taking snapshot
        var handle = new TestResourceHandle();

        var before = new ResourceSnapshot(tracker);

        // Now close the handle
        handle.close();

        var after = new ResourceSnapshot(tracker);
        var report = LeakReport.diff(before, after);

        assertFalse(report.hasLeaks());
        assertEquals(1, report.getFreedCount());
        assertEquals(0, report.getLeakedCount());
    }

    @Test
    void testMultipleResources() {
        var tracker = ResourceTracker.getGlobalTracker();

        // Create multiple resources BEFORE snapshot
        var h1 = new TestResourceHandle();
        var h2 = new TestResourceHandle();

        var before = new ResourceSnapshot(tracker);

        // Close them
        h1.close();
        h2.close();

        var after = new ResourceSnapshot(tracker);
        var report = LeakReport.diff(before, after);

        assertFalse(report.hasLeaks());
        assertEquals(2, report.getFreedCount());
    }

    @Test
    void testMixedLifecycles() {
        var tracker = ResourceTracker.getGlobalTracker();
        var before = new ResourceSnapshot(tracker);

        // Create persistent resource (stays alive)
        var persistent = new TestResourceHandle();

        // Create resource to be freed
        var freed = new TestResourceHandle();

        var middle = new ResourceSnapshot(tracker);

        // Create leaked resource (not closed)
        var leaked = new TestResourceHandle();

        // Now close the freed resource
        freed.close();

        var after = new ResourceSnapshot(tracker);

        // Compare before to after
        var report = LeakReport.diff(before, after);

        assertTrue(report.hasLeaks());
        assertEquals(2, report.getLeakedCount()); // persistent + leaked
        assertEquals(0, report.getFreedCount());  // freed not in before

        // Compare middle to after (only new resources)
        var reportFromMiddle = LeakReport.diff(middle, after);
        assertEquals(1, reportFromMiddle.getLeakedCount()); // just leaked
        assertEquals(1, reportFromMiddle.getFreedCount());  // freed
        assertEquals(1, reportFromMiddle.getPersistentCount()); // persistent

        // Cleanup
        persistent.close();
        leaked.close();

        var cleanup = new ResourceSnapshot(tracker);
        var cleanupReport = LeakReport.diff(after, cleanup);
        assertEquals(0, cleanupReport.getLeakedCount());
        assertEquals(2, cleanupReport.getFreedCount());
    }

    @Test
    void testResourceTypes() {
        var tracker = ResourceTracker.getGlobalTracker();

        // Create resources BEFORE snapshot
        var h1 = new TestResourceHandle();
        var h2 = new AlternateResourceHandle();

        var before = new ResourceSnapshot(tracker);

        assertTrue(before.getResourceTypes().contains("TestResourceHandle"));
        assertTrue(before.getResourceTypes().contains("AlternateResourceHandle"));

        var buffers = before.getResourcesByType("TestResourceHandle");
        assertEquals(1, buffers.size());

        var alternates = before.getResourcesByType("AlternateResourceHandle");
        assertEquals(1, alternates.size());

        // Close resources
        h1.close();
        h2.close();

        var after = new ResourceSnapshot(tracker);
        var report = LeakReport.diff(before, after);

        assertFalse(report.hasLeaks());
        assertEquals(2, report.getFreedCount());
    }

    @Test
    void testReportFormatting() {
        var tracker = ResourceTracker.getGlobalTracker();
        var before = new ResourceSnapshot(tracker);

        // Create leaks
        new TestResourceHandle();
        new TestResourceHandle();
        new AlternateResourceHandle();

        var after = new ResourceSnapshot(tracker);
        var report = LeakReport.diff(before, after);

        var formatted = report.formatReport();

        assertNotNull(formatted);
        assertTrue(formatted.contains("LEAKED RESOURCES"));
        assertTrue(formatted.contains("TestResourceHandle: 2 instances"));
        assertTrue(formatted.contains("AlternateResourceHandle: 1 instances"));

        // Cleanup
        tracker.forceCloseAll();
    }

    @Test
    void testWithResourceLifecycleTestSupport() {
        var test = new IntegrationLifecycleTest();
        test.setupLifecycleTesting();

        // Create handle BEFORE snapshot
        var handle = new TestResourceHandle();

        var before = test.captureSnapshot();

        // Close it
        handle.close();

        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        test.assertNoLeaks(report);
        test.assertFreedCount(report, 1);

        test.teardownLifecycleTesting();
    }

    // Test helper classes
    private static class TestResourceHandle extends ResourceHandle<Long> {
        private static final AtomicLong counter = new AtomicLong(0);

        TestResourceHandle() {
            super(counter.incrementAndGet(), ResourceTracker.getGlobalTracker());
        }

        @Override
        protected void doCleanup(Long resource) {
            // Trivial cleanup (no actual native resource)
        }
    }

    private static class AlternateResourceHandle extends ResourceHandle<Long> {
        private static final AtomicLong counter = new AtomicLong(1000);

        AlternateResourceHandle() {
            super(counter.incrementAndGet(), ResourceTracker.getGlobalTracker());
        }

        @Override
        protected void doCleanup(Long resource) {
            // Trivial cleanup
        }
    }

    private static class IntegrationLifecycleTest extends ResourceLifecycleTestSupport {
        // Concrete test class for integration testing
    }
}
