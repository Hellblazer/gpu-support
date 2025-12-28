package com.hellblazer.luciferase.test.lifecycle;

import com.hellblazer.luciferase.resource.ResourceHandle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test for ResourceInfo record contract and factory method.
 */
class ResourceInfoTest {

    @Test
    void testRecordImmutability() {
        var info = new ResourceInfo("id-123", "CLBufferHandle", 100L, "stack trace");

        assertEquals("id-123", info.id());
        assertEquals("CLBufferHandle", info.type());
        assertEquals(100L, info.ageMillis());
        assertEquals("stack trace", info.allocationStack());
    }

    @Test
    void testEqualsAndHashCode() {
        var info1 = new ResourceInfo("id-123", "CLBufferHandle", 100L, "stack");
        var info2 = new ResourceInfo("id-123", "CLBufferHandle", 100L, "stack");
        var info3 = new ResourceInfo("id-456", "CLBufferHandle", 100L, "stack");

        // Same values should be equal
        assertEquals(info1, info2);
        assertEquals(info1.hashCode(), info2.hashCode());

        // Different IDs should not be equal
        assertNotEquals(info1, info3);
    }

    @Test
    void testFromFactory() {
        // Create real ResourceHandle with test data
        var testHandle = new TestResourceHandle("test-id-789", 250L, "test stack trace");

        // Extract info using factory method
        var info = ResourceInfo.from(testHandle);

        assertEquals("test-id-789", info.id());
        assertEquals("TestResourceHandle", info.type());
        assertEquals(250L, info.ageMillis());
        assertEquals("test stack trace", info.allocationStack());
    }

    @Test
    void testNullAllocationStack() {
        // Create handle with null allocation stack (debug logging disabled)
        var testHandle = new TestResourceHandle("id-null-stack", 50L, null);

        var info = ResourceInfo.from(testHandle);

        assertEquals("id-null-stack", info.id());
        assertNull(info.allocationStack());
    }

    // Test helper class with controllable properties
    private static class TestResourceHandle extends ResourceHandle<Long> {
        private final String testId;
        private final long testAge;
        private final String testStack;

        protected TestResourceHandle(String id, long age, String stack) {
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
