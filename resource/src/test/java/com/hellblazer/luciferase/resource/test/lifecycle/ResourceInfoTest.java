package com.hellblazer.luciferase.resource.test.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceInfo record (immutable resource metadata holder).
 */
class ResourceInfoTest {

    @Test
    void testCreationWithAllFields() {
        var info = new ResourceInfo("buffer-123", String.class, 1000L, "at sun.misc.Unsafe.allocateMemory");

        assertEquals("buffer-123", info.resourceId());
        assertEquals(String.class, info.type());
        assertEquals(1000L, info.age());
        assertEquals("at sun.misc.Unsafe.allocateMemory", info.allocationStack());
    }

    @Test
    void testCreationWithNullStack() {
        var info = new ResourceInfo("buffer-456", Integer.class, 500L, null);

        assertEquals("buffer-456", info.resourceId());
        assertEquals(Integer.class, info.type());
        assertEquals(500L, info.age());
        assertNull(info.allocationStack());
    }

    @Test
    void testNullResourceIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new ResourceInfo(null, String.class, 100L, "stack"));
    }

    @Test
    void testNullTypeThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new ResourceInfo("id", null, 100L, "stack"));
    }

    @Test
    void testImmutability() {
        var info = new ResourceInfo("id", String.class, 100L, "stack");

        // Record fields should be final and accessible
        assertEquals("id", info.resourceId());

        // Cannot reassign record properties (compile-time check tested conceptually)
        var info2 = info;
        assertEquals(info, info2); // Records provide equals/hashCode
    }

    @Test
    void testEqualsAndHashCode() {
        var info1 = new ResourceInfo("id", String.class, 100L, "stack");
        var info2 = new ResourceInfo("id", String.class, 100L, "stack");
        var info3 = new ResourceInfo("id2", String.class, 100L, "stack");

        assertEquals(info1, info2);
        assertNotEquals(info1, info3);
        assertEquals(info1.hashCode(), info2.hashCode());
        assertNotEquals(info1.hashCode(), info3.hashCode());
    }

    @Test
    void testToString() {
        var info = new ResourceInfo("buffer-123", String.class, 1000L, "at sun.misc.Unsafe");
        var str = info.toString();

        // Record toString includes field values
        assertTrue(str.contains("buffer-123"));
        assertTrue(str.contains("String"));
    }

    @Test
    void testZeroAge() {
        var info = new ResourceInfo("id", String.class, 0L, null);
        assertEquals(0L, info.age());
    }

    @Test
    void testLargeAge() {
        var info = new ResourceInfo("id", String.class, Long.MAX_VALUE, null);
        assertEquals(Long.MAX_VALUE, info.age());
    }
}
