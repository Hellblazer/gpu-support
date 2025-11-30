package com.hellblazer.luciferase.resource.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceSnapshot (immutable point-in-time resource state).
 */
class ResourceSnapshotTest {

    private ResourceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ResourceTracker();
    }

    @Test
    void testEmptySnapshot() {
        var snapshot = ResourceSnapshot.captureSnapshot(tracker);

        assertNotNull(snapshot);
        assertEquals(0, snapshot.getActiveCount());
        assertEquals(0, snapshot.getActiveResourcesByType().size());
    }

    @Test
    void testSnapshotWithResources() {
        // Create mock active resources map
        var byType = new HashMap<Class<?>, Set<String>>();
        var bufferIds = new HashSet<String>();
        bufferIds.add("buffer-1");
        bufferIds.add("buffer-2");
        byType.put(String.class, bufferIds);

        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            byType,
            2L,
            0L
        );

        assertEquals(2, snapshot.getActiveCount());
        assertEquals(2, snapshot.getActiveCount(String.class));
        assertTrue(snapshot.getActiveResourcesByType().containsKey(String.class));
    }

    @Test
    void testMultipleResourceTypes() {
        var byType = new HashMap<Class<?>, Set<String>>();

        var buffers = new HashSet<String>();
        buffers.add("buffer-1");
        buffers.add("buffer-2");
        byType.put(String.class, buffers);

        var programs = new HashSet<String>();
        programs.add("program-1");
        byType.put(Integer.class, programs);

        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            byType,
            3L,
            0L
        );

        assertEquals(3, snapshot.getActiveCount());
        assertEquals(2, snapshot.getActiveCount(String.class));
        assertEquals(1, snapshot.getActiveCount(Integer.class));
        assertEquals(0, snapshot.getActiveCount(Double.class));
    }

    @Test
    void testSnapshotImmutability() {
        var byType = new HashMap<Class<?>, Set<String>>();
        var buffers = new HashSet<String>();
        buffers.add("buffer-1");
        byType.put(String.class, buffers);

        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            byType,
            1L,
            0L
        );

        // Modify original map - snapshot map is immutable
        byType.put(Integer.class, new HashSet<>());

        // Original snapshot is not affected
        assertEquals(1, snapshot.getActiveCount());
        assertEquals(1, snapshot.getActiveCount(String.class));
        assertEquals(0, snapshot.getActiveCount(Integer.class));
    }

    @Test
    void testTimestamp() {
        var now = System.nanoTime();
        var snapshot = new ResourceSnapshot(now, new HashMap<>(), 0L, 0L);

        assertEquals(now, snapshot.getTimestamp());
    }

    @Test
    void testTotalAllocated() {
        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            100L,
            25L
        );

        assertEquals(100L, snapshot.getTotalAllocated());
        assertEquals(25L, snapshot.getTotalFreed());
    }

    @Test
    void testGetActiveResourcesByType() {
        var byType = new HashMap<Class<?>, Set<String>>();
        var buffers = new HashSet<String>();
        buffers.add("buffer-1");
        byType.put(String.class, buffers);

        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            byType,
            1L,
            0L
        );

        var resources = snapshot.getActiveResourcesByType();
        assertNotNull(resources);
        assertTrue(resources.containsKey(String.class));
        assertEquals(1, resources.get(String.class).size());

        // Verify immutability - cannot modify returned map
        assertThrows(UnsupportedOperationException.class, () ->
            resources.put(Integer.class, new HashSet<>())
        );
    }

    @Test
    void testZeroCountWhenMissingType() {
        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            0L,
            0L
        );

        assertEquals(0, snapshot.getActiveCount(String.class));
        assertEquals(0, snapshot.getActiveCount(Integer.class));
    }

    @Test
    void testLargeResourceCount() {
        var byType = new HashMap<Class<?>, Set<String>>();
        var buffers = new HashSet<String>();

        for (int i = 0; i < 1000; i++) {
            buffers.add("buffer-" + i);
        }
        byType.put(String.class, buffers);

        var snapshot = new ResourceSnapshot(
            System.nanoTime(),
            byType,
            1000L,
            0L
        );

        assertEquals(1000, snapshot.getActiveCount());
        assertEquals(1000, snapshot.getActiveCount(String.class));
    }
}
