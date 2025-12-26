package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ResourceLifecycleTestSupport base class contract.
 * Uses concrete test class to validate abstract base functionality.
 */
class ResourceLifecycleTestSupportTest {

    @Test
    void testNoLeaksWhenClean() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        var before = test.captureSnapshot();
        // No resource creation
        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        test.assertNoLeaks(report);  // Should pass

        test.teardownLifecycleTesting();
    }

    @Test
    void testLeakDetectionFails() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        var before = test.captureSnapshot();

        // Create resource without closing (intentional leak)
        var leaked = new TestResourceHandle();

        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        // assertNoLeaks should throw AssertionError
        var error = assertThrows(AssertionError.class, () -> test.assertNoLeaks(report));
        assertTrue(error.getMessage().contains("Resource leak detected"));

        // Cleanup the leaked resource
        leaked.close();
        test.teardownLifecycleTesting();
    }

    @Test
    void testFreedResourcesPass() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        // Create resource BEFORE snapshot
        var resource = new TestResourceHandle();

        var before = test.captureSnapshot();

        // Close it
        resource.close();

        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        test.assertNoLeaks(report);  // Should pass (freed correctly)
        assertEquals(1, report.getFreedCount());

        test.teardownLifecycleTesting();
    }

    @Test
    void testForceCleanup() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        // Create resources without closing
        new TestResourceHandle();
        new TestResourceHandle();

        assertTrue(test.getActiveResourceCount() >= 2);

        int cleaned = test.forceCleanupAll();
        assertTrue(cleaned >= 2);

        test.teardownLifecycleTesting();
    }

    @Test
    void testAssertLeakCount() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        var before = test.captureSnapshot();

        // Create exactly 2 leaks
        new TestResourceHandle();
        new TestResourceHandle();

        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        test.assertLeakCount(report, 2);  // Should pass

        // Wrong count should fail
        assertThrows(AssertionError.class, () -> test.assertLeakCount(report, 1));

        test.forceCleanupAll();
        test.teardownLifecycleTesting();
    }

    @Test
    void testAssertFreedCount() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        // Create 3 resources BEFORE snapshot
        var r1 = new TestResourceHandle();
        var r2 = new TestResourceHandle();
        var r3 = new TestResourceHandle();

        var before = test.captureSnapshot();

        // Close them
        r1.close();
        r2.close();
        r3.close();

        var after = test.captureSnapshot();
        var report = test.diff(before, after);

        test.assertFreedCount(report, 3);  // Should pass

        // Wrong count should fail
        assertThrows(AssertionError.class, () -> test.assertFreedCount(report, 2));

        test.teardownLifecycleTesting();
    }

    @Test
    void testEmergencyCleanupInTeardown() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        // Create leak (don't close)
        new TestResourceHandle();

        assertTrue(test.getActiveResourceCount() > 0);

        // teardown should force cleanup
        test.teardownLifecycleTesting();

        // After teardown, resources should be cleaned
        assertEquals(0, test.getActiveResourceCount());
    }

    @Test
    void testMultipleSnapshots() {
        var test = new ConcreteLifecycleTest();
        test.setupLifecycleTesting();

        // Create first resource
        var r1 = new TestResourceHandle();

        var snap1 = test.captureSnapshot();

        // Create second resource
        var r2 = new TestResourceHandle();

        var snap2 = test.captureSnapshot();

        // Between snap1 and snap2: 1 leaked
        var report1 = test.diff(snap1, snap2);
        assertEquals(1, report1.getLeakedCount());

        // Close r2
        r2.close();

        var snap3 = test.captureSnapshot();

        // Between snap2 and snap3: 1 freed
        var report2 = test.diff(snap2, snap3);
        assertEquals(1, report2.getFreedCount());

        // Close r1
        r1.close();

        var snap4 = test.captureSnapshot();

        // Between snap1 and snap4: r1 freed (r2 never in snap1)
        var finalReport = test.diff(snap1, snap4);
        assertEquals(0, finalReport.getLeakedCount());
        assertEquals(1, finalReport.getFreedCount());

        test.teardownLifecycleTesting();
    }

    // Concrete test class extending abstract base
    private static class ConcreteLifecycleTest extends ResourceLifecycleTestSupport {
        // Inherits all functionality from base class
    }

    // Test helper resource handle
    private static class TestResourceHandle extends ResourceHandle<Long> {
        private static final AtomicLong counter = new AtomicLong(0);

        TestResourceHandle() {
            super(counter.incrementAndGet(), ResourceTracker.getGlobalTracker());
        }

        @Override
        protected void doCleanup(Long resource) {
            // No-op for testing
        }
    }
}
