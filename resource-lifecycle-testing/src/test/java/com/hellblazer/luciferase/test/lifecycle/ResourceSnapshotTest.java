package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test for ResourceSnapshot capture and query capabilities.
 */
class ResourceSnapshotTest {

    @Test
    void testEmptySnapshot() {
        var tracker = mock(ResourceTracker.class);
        when(tracker.getActiveResourceIds()).thenReturn(Set.of());

        var snapshot = new ResourceSnapshot(tracker);

        assertEquals(0, snapshot.getTotalCount());
        assertTrue(snapshot.getResourceTypes().isEmpty());
        assertTrue(snapshot.getAllResourceIds().isEmpty());
    }

    @Test
    void testSnapshotCapture() {
        var tracker = mock(ResourceTracker.class);
        var handle1 = createMockHandle("id-1", "CLBufferHandle", 100L);
        var handle2 = createMockHandle("id-2", "CLKernelHandle", 200L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("id-1", "id-2"));
        when(tracker.getResource("id-1")).thenAnswer(inv -> handle1);
        when(tracker.getResource("id-2")).thenAnswer(inv -> handle2);

        var snapshot = new ResourceSnapshot(tracker);

        assertEquals(2, snapshot.getTotalCount());
        assertEquals(2, snapshot.getResourceTypes().size());
        assertTrue(snapshot.getResourceTypes().contains("CLBufferHandleMock"));
        assertTrue(snapshot.getResourceTypes().contains("CLKernelHandleMock"));
    }

    @Test
    void testGroupingByType() {
        var tracker = mock(ResourceTracker.class);
        var buffer1 = createMockHandle("buf-1", "CLBufferHandle", 100L);
        var buffer2 = createMockHandle("buf-2", "CLBufferHandle", 150L);
        var kernel1 = createMockHandle("kern-1", "CLKernelHandle", 200L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("buf-1", "buf-2", "kern-1"));
        when(tracker.getResource("buf-1")).thenAnswer(inv -> buffer1);
        when(tracker.getResource("buf-2")).thenAnswer(inv -> buffer2);
        when(tracker.getResource("kern-1")).thenAnswer(inv -> kernel1);

        var snapshot = new ResourceSnapshot(tracker);

        assertEquals(3, snapshot.getTotalCount());

        var buffers = snapshot.getResourcesByType("CLBufferHandleMock");
        assertEquals(2, buffers.size());
        assertTrue(buffers.stream().anyMatch(r -> r.id().equals("buf-1")));
        assertTrue(buffers.stream().anyMatch(r -> r.id().equals("buf-2")));

        var kernels = snapshot.getResourcesByType("CLKernelHandleMock");
        assertEquals(1, kernels.size());
        assertEquals("kern-1", kernels.get(0).id());
    }

    @Test
    void testResourceQueries() {
        var tracker = mock(ResourceTracker.class);
        var handle = createMockHandle("query-id", "CLBufferHandle", 300L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("query-id"));
        when(tracker.getResource("query-id")).thenAnswer(inv -> handle);

        var snapshot = new ResourceSnapshot(tracker);

        // Test getResourcesByType
        var byType = snapshot.getResourcesByType("CLBufferHandleMock");
        assertEquals(1, byType.size());
        assertEquals("query-id", byType.get(0).id());

        // Test getResourceById
        var byId = snapshot.getResourceById("query-id");
        assertTrue(byId.isPresent());
        assertEquals("query-id", byId.get().id());
        assertEquals(300L, byId.get().ageMillis());

        // Test non-existent ID
        var notFound = snapshot.getResourceById("non-existent");
        assertFalse(notFound.isPresent());

        // Test non-existent type
        var noType = snapshot.getResourcesByType("NonExistentType");
        assertTrue(noType.isEmpty());
    }

    @Test
    void testRaceConditionHandling() {
        // Simulate race condition: resource closed between getActiveResourceIds() and getResource(id)
        var tracker = mock(ResourceTracker.class);
        var handle = createMockHandle("race-id", "CLBufferHandle", 100L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("race-id", "closed-id"));
        when(tracker.getResource("race-id")).thenAnswer(inv -> handle);
        when(tracker.getResource("closed-id")).thenAnswer(inv -> null); // Closed during snapshot

        var snapshot = new ResourceSnapshot(tracker);

        // Should only capture the non-null resource
        assertEquals(2, snapshot.getTotalCount()); // Total count reflects initial query
        assertEquals(1, snapshot.getAllResourceIds().size()); // But only 1 resource captured
        assertTrue(snapshot.getResourceById("race-id").isPresent());
        assertFalse(snapshot.getResourceById("closed-id").isPresent());
    }

    @Test
    void testSnapshotImmutability() {
        var tracker = mock(ResourceTracker.class);
        var handle = createMockHandle("immut-id", "CLBufferHandle", 100L);

        when(tracker.getActiveResourceIds()).thenReturn(Set.of("immut-id"));
        when(tracker.getResource("immut-id")).thenAnswer(inv -> handle);

        var snapshot = new ResourceSnapshot(tracker);

        // Attempt to modify returned collections should fail or have no effect
        var types = snapshot.getResourceTypes();
        assertThrows(UnsupportedOperationException.class, () -> types.add("NewType"));

        var byType = snapshot.getResourcesByType("CLBufferHandleMock");
        assertThrows(UnsupportedOperationException.class, () -> byType.add(
            new ResourceInfo("fake", "Fake", 0L, null)
        ));
    }

    private ResourceHandle<?> createMockHandle(String id, String type, long ageMillis) {
        // Use actual ResourceHandle subclasses instead of mocking
        if ("CLBufferHandle".equals(type)) {
            return new CLBufferHandleMock(id, ageMillis);
        } else if ("CLKernelHandle".equals(type)) {
            return new CLKernelHandleMock(id, ageMillis);
        }
        return new TestHandle(id, ageMillis);
    }

    // Test helper classes with controllable ID and age
    private static class CLBufferHandleMock extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;

        protected CLBufferHandleMock(String id, long age) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override protected void doCleanup(Long resource) {}
    }

    private static class CLKernelHandleMock extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;

        protected CLKernelHandleMock(String id, long age) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override protected void doCleanup(Long resource) {}
    }

    private static class TestHandle extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;

        protected TestHandle(String id, long age) {
            super(0L, null);
            this.testId = id;
            this.testAge = age;
        }

        @Override public String getId() { return testId; }
        @Override public long getAgeMillis() { return testAge; }
        @Override protected void doCleanup(Long resource) {}
    }
}
