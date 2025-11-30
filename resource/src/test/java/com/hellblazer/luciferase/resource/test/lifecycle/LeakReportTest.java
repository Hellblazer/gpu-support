package com.hellblazer.luciferase.resource.test.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LeakReport (detailed leak analysis and reporting).
 */
class LeakReportTest {

    @Test
    void testNoLeaks() {
        var before = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            0L,
            0L
        );

        var after = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            0L,
            0L
        );

        var report = new LeakReport(new HashMap<>(), before, after);

        assertFalse(report.hasLeaks());
        assertEquals(0, report.getNetLeaks());
    }

    @Test
    void testDetectsLeaks() {
        var before = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            0L,
            0L
        );

        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, null),
            new ResourceInfo("buffer-2", String.class, 95L, null)
        );
        leakedByType.put(String.class, buffers);

        var after = new ResourceSnapshot(
            System.nanoTime(),
            new HashMap<>(),
            2L,
            0L
        );

        var report = new LeakReport(leakedByType, before, after);

        assertTrue(report.hasLeaks());
        assertEquals(2, report.getNetLeaks());
        assertEquals(2, report.getLeakCount(String.class));
    }

    @Test
    void testLeakCountByType() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();

        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, null),
            new ResourceInfo("buffer-2", String.class, 95L, null)
        );
        leakedByType.put(String.class, buffers);

        var programs = List.of(
            new ResourceInfo("program-1", Integer.class, 50L, null)
        );
        leakedByType.put(Integer.class, programs);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 3L, 0L);

        var report = new LeakReport(leakedByType, before, after);

        assertEquals(3, report.getNetLeaks());
        assertEquals(2, report.getLeakCount(String.class));
        assertEquals(1, report.getLeakCount(Integer.class));
        assertEquals(0, report.getLeakCount(Double.class));
    }

    @Test
    void testGetLeakedResourcesByType() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, null)
        );
        leakedByType.put(String.class, buffers);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 1L, 0L);

        var report = new LeakReport(leakedByType, before, after);

        var resources = report.getLeakedResourcesByType();
        assertNotNull(resources);
        assertTrue(resources.containsKey(String.class));
        assertEquals(1, resources.get(String.class).size());
    }

    @Test
    void testLeakedResourcesImmutable() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        leakedByType.put(String.class, List.of(
            new ResourceInfo("buffer-1", String.class, 100L, null)
        ));

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 1L, 0L);

        var report = new LeakReport(leakedByType, before, after);
        var resources = report.getLeakedResourcesByType();

        // Cannot modify returned map
        assertThrows(UnsupportedOperationException.class, () ->
            resources.put(Integer.class, List.of())
        );
    }

    @Test
    void testFormattedReport() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, "at java.nio.Buffer.allocate"),
            new ResourceInfo("buffer-2", String.class, 95L, "at java.nio.Buffer.allocate")
        );
        leakedByType.put(String.class, buffers);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime() + 1_000_000_000, new HashMap<>(), 2L, 0L);

        var report = new LeakReport(leakedByType, before, after);
        var str = report.toString();

        // Verify report contains expected information
        assertTrue(str.contains("Resource Leak Report"));
        assertTrue(str.contains("Total Leaks: 2"));
        assertTrue(str.contains("String"));
        assertTrue(str.contains("2 leaked"));
        assertTrue(str.contains("buffer-1"));
        assertTrue(str.contains("buffer-2"));
    }

    @Test
    void testFormattedReportWithAllocationStack() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, "stack trace here")
        );
        leakedByType.put(String.class, buffers);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 1L, 0L);

        var report = new LeakReport(leakedByType, before, after);
        var str = report.toString();

        assertTrue(str.contains("stack trace here"));
    }

    @Test
    void testFormattedReportWithManyLeaks() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();
        var buffers = new java.util.ArrayList<ResourceInfo>();

        for (int i = 0; i < 10; i++) {
            buffers.add(new ResourceInfo("buffer-" + i, String.class, 100L - i, null));
        }
        leakedByType.put(String.class, buffers);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 10L, 0L);

        var report = new LeakReport(leakedByType, before, after);
        var str = report.toString();

        // Should show first 5 and indicate more
        assertTrue(str.contains("Total Leaks: 10"));
        assertTrue(str.contains("... and 5 more"));
    }

    @Test
    void testEmptyReport() {
        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);

        var report = new LeakReport(new HashMap<>(), before, after);
        var str = report.toString();

        assertTrue(str.contains("Resource Leak Report"));
        assertTrue(str.contains("Total Leaks: 0"));
    }

    @Test
    void testMultipleResourceTypes() {
        var leakedByType = new HashMap<Class<?>, List<ResourceInfo>>();

        var buffers = List.of(
            new ResourceInfo("buffer-1", String.class, 100L, null),
            new ResourceInfo("buffer-2", String.class, 95L, null),
            new ResourceInfo("buffer-3", String.class, 90L, null)
        );
        leakedByType.put(String.class, buffers);

        var programs = List.of(
            new ResourceInfo("program-1", Integer.class, 80L, null),
            new ResourceInfo("program-2", Integer.class, 75L, null)
        );
        leakedByType.put(Integer.class, programs);

        var before = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 0L, 0L);
        var after = new ResourceSnapshot(System.nanoTime(), new HashMap<>(), 5L, 0L);

        var report = new LeakReport(leakedByType, before, after);

        assertTrue(report.hasLeaks());
        assertEquals(5, report.getNetLeaks());
        assertEquals(3, report.getLeakCount(String.class));
        assertEquals(2, report.getLeakCount(Integer.class));

        var str = report.toString();
        assertTrue(str.contains("String: 3 leaked"));
        assertTrue(str.contains("Integer: 2 leaked"));
    }
}
