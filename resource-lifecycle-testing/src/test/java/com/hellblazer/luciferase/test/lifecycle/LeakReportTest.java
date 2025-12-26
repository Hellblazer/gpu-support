package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test for LeakReport diff algorithm and formatting.
 */
class LeakReportTest {

    @Test
    void testNoChanges() {
        var tracker = mock(ResourceTracker.class);
        var handle = createMockHandle("same-id", "CLBufferHandle", 100L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("same-id"));
        when(tracker.getResource("same-id")).thenAnswer(inv -> handle);

        var before = new ResourceSnapshot(tracker);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);

        assertFalse(report.hasLeaks());
        assertEquals(0, report.getLeakedCount());
        assertEquals(0, report.getFreedCount());
        assertEquals(1, report.getPersistentCount());

        var persistent = report.getPersistentResources();
        assertEquals(1, persistent.size());
        assertTrue(persistent.stream().anyMatch(r -> r.id().equals("same-id")));
    }

    @Test
    void testLeakDetection() {
        var tracker = mock(ResourceTracker.class);

        // Before: no resources
        when(tracker.getActiveResourceIds()).thenReturn(Set.of());
        var before = new ResourceSnapshot(tracker);

        // After: one leaked resource
        var leaked = createMockHandle("leaked-id", "CLBufferHandle", 50L);
        when(tracker.getActiveResourceIds()).thenReturn(Set.of("leaked-id"));
        when(tracker.getResource("leaked-id")).thenAnswer(inv -> leaked);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);

        assertTrue(report.hasLeaks());
        assertEquals(1, report.getLeakedCount());
        assertEquals(0, report.getFreedCount());
        assertEquals(0, report.getPersistentCount());

        var leakedResources = report.getLeakedResources();
        assertEquals(1, leakedResources.size());
        assertTrue(leakedResources.stream().anyMatch(r -> r.id().equals("leaked-id")));
    }

    @Test
    void testFreedDetection() {
        var tracker = mock(ResourceTracker.class);
        var freed = createMockHandle("freed-id", "CLBufferHandle", 100L);

        // Before: one resource
        when(tracker.getActiveResourceIds()).thenReturn(Set.of("freed-id"));
        when(tracker.getResource("freed-id")).thenAnswer(inv -> freed);
        var before = new ResourceSnapshot(tracker);

        // After: resource properly closed
        when(tracker.getActiveResourceIds()).thenReturn(Set.of());
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);

        assertFalse(report.hasLeaks());
        assertEquals(0, report.getLeakedCount());
        assertEquals(1, report.getFreedCount());
        assertEquals(0, report.getPersistentCount());

        var freedResources = report.getFreedResources();
        assertEquals(1, freedResources.size());
        assertTrue(freedResources.stream().anyMatch(r -> r.id().equals("freed-id")));
    }

    @Test
    void testPersistentResources() {
        var tracker = mock(ResourceTracker.class);
        var persistent1 = createMockHandle("persist-1", "CLBufferHandle", 100L);
        var persistent2 = createMockHandle("persist-2", "CLKernelHandle", 200L);
        var freed = createMockHandle("freed-1", "CLBufferHandle", 150L);
        var leaked = createMockHandle("leaked-1", "CLBufferHandle", 50L);

        // Before: persistent + freed
        when(tracker.getActiveResourceIds()).thenReturn(Set.of("persist-1", "persist-2", "freed-1"));
        when(tracker.getResource("persist-1")).thenAnswer(inv -> persistent1);
        when(tracker.getResource("persist-2")).thenAnswer(inv -> persistent2);
        when(tracker.getResource("freed-1")).thenAnswer(inv -> freed);
        var before = new ResourceSnapshot(tracker);

        // After: persistent + leaked
        when(tracker.getActiveResourceIds()).thenReturn(Set.of("persist-1", "persist-2", "leaked-1"));
        when(tracker.getResource("persist-1")).thenAnswer(inv -> persistent1);
        when(tracker.getResource("persist-2")).thenAnswer(inv -> persistent2);
        when(tracker.getResource("leaked-1")).thenAnswer(inv -> leaked);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);

        assertTrue(report.hasLeaks());
        assertEquals(1, report.getLeakedCount());
        assertEquals(1, report.getFreedCount());
        assertEquals(2, report.getPersistentCount());

        assertTrue(report.getLeakedResources().stream().anyMatch(r -> r.id().equals("leaked-1")));
        assertTrue(report.getFreedResources().stream().anyMatch(r -> r.id().equals("freed-1")));
        assertTrue(report.getPersistentResources().stream().anyMatch(r -> r.id().equals("persist-1")));
        assertTrue(report.getPersistentResources().stream().anyMatch(r -> r.id().equals("persist-2")));
    }

    @Test
    void testFormatReport() {
        var tracker = mock(ResourceTracker.class);

        // Before: empty
        when(tracker.getActiveResourceIds()).thenReturn(Set.of());
        var before = new ResourceSnapshot(tracker);

        // After: leaked resources with stack traces
        var leaked1 = createMockHandleWithStack("leak-1", "CLBufferHandle", 100L, "at com.example.Test.method1");
        var leaked2 = createMockHandleWithStack("leak-2", "CLKernelHandle", 150L, "at com.example.Test.method2");
        when(tracker.getActiveResourceIds()).thenReturn(Set.of("leak-1", "leak-2"));
        when(tracker.getResource("leak-1")).thenAnswer(inv -> leaked1);
        when(tracker.getResource("leak-2")).thenAnswer(inv -> leaked2);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);
        var formatted = report.formatReport();

        assertNotNull(formatted);
        assertTrue(formatted.contains("Resource Lifecycle Report"));
        assertTrue(formatted.contains("Leaked: 2"));
        assertTrue(formatted.contains("Freed: 0"));
        assertTrue(formatted.contains("Persistent: 0"));
        assertTrue(formatted.contains("LEAKED RESOURCES"));
        assertTrue(formatted.contains("CLBufferHandleMock"));
        assertTrue(formatted.contains("CLKernelHandleMock"));
        assertTrue(formatted.contains("leak-1"));
        assertTrue(formatted.contains("leak-2"));
        assertTrue(formatted.contains("at com.example.Test.method1"));
        assertTrue(formatted.contains("at com.example.Test.method2"));
    }

    @Test
    void testFormatReportNoLeaks() {
        var tracker = mock(ResourceTracker.class);
        when(tracker.getActiveResourceIds()).thenReturn(Set.of());

        var before = new ResourceSnapshot(tracker);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);
        var formatted = report.formatReport();

        assertNotNull(formatted);
        assertTrue(formatted.contains("Leaked: 0"));
        assertFalse(formatted.contains("LEAKED RESOURCES"));
    }

    @Test
    void testMultipleLeaksGroupedByType() {
        var tracker = mock(ResourceTracker.class);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of());
        var before = new ResourceSnapshot(tracker);

        // Multiple leaks of same type
        var leak1 = createMockHandle("buf-leak-1", "CLBufferHandle", 100L);
        var leak2 = createMockHandle("buf-leak-2", "CLBufferHandle", 150L);
        var leak3 = createMockHandle("kern-leak-1", "CLKernelHandle", 200L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("buf-leak-1", "buf-leak-2", "kern-leak-1"));
        when(tracker.getResource("buf-leak-1")).thenAnswer(inv -> leak1);
        when(tracker.getResource("buf-leak-2")).thenAnswer(inv -> leak2);
        when(tracker.getResource("kern-leak-1")).thenAnswer(inv -> leak3);
        var after = new ResourceSnapshot(tracker);

        var report = LeakReport.diff(before, after);
        var formatted = report.formatReport();

        assertTrue(formatted.contains("CLBufferHandleMock: 2 instances"));
        assertTrue(formatted.contains("CLKernelHandleMock: 1 instances"));
    }

    private ResourceHandle<?> createMockHandle(String id, String type, long ageMillis) {
        return createMockHandleWithStack(id, type, ageMillis, null);
    }

    private ResourceHandle<?> createMockHandleWithStack(String id, String type, long ageMillis, String stack) {
        if ("CLBufferHandle".equals(type)) {
            return new CLBufferHandleMock(id, ageMillis, stack);
        } else if ("CLKernelHandle".equals(type)) {
            return new CLKernelHandleMock(id, ageMillis, stack);
        }
        return new TestHandle(id, ageMillis, stack);
    }

    private static class CLBufferHandleMock extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;
        private final String testStack;

        protected CLBufferHandleMock(String id, long age, String stack) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
            this.testStack = stack;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override public String getAllocationStack() { return testStack; }
        @Override protected void doCleanup(Long resource) {}
    }

    private static class CLKernelHandleMock extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;
        private final String testStack;

        protected CLKernelHandleMock(String id, long age, String stack) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
            this.testStack = stack;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override public String getAllocationStack() { return testStack; }
        @Override protected void doCleanup(Long resource) {}
    }

    private static class TestHandle extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;
        private final String testStack;

        protected TestHandle(String id, long age, String stack) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
            this.testStack = stack;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override public String getAllocationStack() { return testStack; }
        @Override protected void doCleanup(Long resource) {}
    }
}
