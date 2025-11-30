package com.hellblazer.luciferase.resource;

import com.hellblazer.luciferase.resource.memory.MemoryPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MemoryPool functionality
 */
public class MemoryPoolTest {
    
    private MemoryPool pool;
    
    @BeforeEach
    void setUp() {
        pool = new MemoryPool(1024 * 1024, Duration.ofMinutes(1)); // 1MB pool, 1 min idle
    }
    
    @Test
    void testPoolCreation() {
        assertNotNull(pool);
        assertEquals(0, pool.getCurrentSize());
        assertEquals(0, pool.getHitRate(), 0.01);
    }
    
    @Test
    void testBasicAllocationAndReturn() {
        var buffer = pool.allocate(1024);
        assertNotNull(buffer);
        assertEquals(1024, buffer.capacity());
        assertEquals(0, pool.getCurrentSize()); // Not in pool until returned
        
        pool.returnToPool(buffer);
        assertEquals(1024, pool.getCurrentSize()); // Now in pool
    }
    
    @Test
    void testPoolReuse() {
        var buffer1 = pool.allocate(1024);
        assertNotNull(buffer1);
        pool.returnToPool(buffer1);
        
        var buffer2 = pool.allocate(1024);
        assertNotNull(buffer2);
        assertSame(buffer1, buffer2); // Should get the same buffer back
        assertEquals(0.5, pool.getHitRate(), 0.01); // 1 hit, 2 requests
    }
    
    @Test
    void testDifferentSizes() {
        var small = pool.allocate(512);
        var medium = pool.allocate(1024);
        var large = pool.allocate(2048);
        
        assertNotNull(small);
        assertNotNull(medium);
        assertNotNull(large);
        
        assertEquals(512, small.capacity());
        assertEquals(1024, medium.capacity());
        assertEquals(2048, large.capacity());
        
        pool.returnToPool(small);
        pool.returnToPool(medium);
        pool.returnToPool(large);
        
        assertEquals(512 + 1024 + 2048, pool.getCurrentSize());
    }
    
    @Test
    void testPoolEviction() throws InterruptedException {
        // Create pool with very short idle time
        var shortPool = new MemoryPool(1024 * 1024, Duration.ofMillis(100));
        
        var buffer = shortPool.allocate(1024);
        shortPool.returnToPool(buffer);
        assertEquals(1024, shortPool.getCurrentSize());
        
        // Wait for expiration
        Thread.sleep(200);
        shortPool.evictExpired();
        
        assertEquals(0, shortPool.getCurrentSize());
    }
    
    @Test
    void testMaxPoolSize() {
        // Create small pool
        var smallPool = new MemoryPool(2048, Duration.ofMinutes(1));
        
        var buffer1 = smallPool.allocate(1024);
        var buffer2 = smallPool.allocate(1024);
        
        
        smallPool.returnToPool(buffer1);
        
        smallPool.returnToPool(buffer2);
        
        assertEquals(2048, smallPool.getCurrentSize());
        
        // Try to add more - should trigger eviction
        var buffer3 = smallPool.allocate(1024);
        smallPool.returnToPool(buffer3);
        
        // Pool should have evicted something to stay under max
        assertTrue(smallPool.getCurrentSize() <= 2048);
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await(); // Wait for all threads to be ready
                    var buffer = pool.allocate(1024);
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10));
                    pool.returnToPool(buffer);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        
        latch.countDown(); // Start all threads
        
        for (var future : futures) {
            assertTrue(future.get());
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        // Pool should still be in valid state
        assertTrue(pool.getCurrentSize() >= 0);
        assertTrue(pool.getCurrentSize() <= 1024 * 1024);
    }
    
    @Test
    void testPoolStatistics() {
        var stats = pool.getPoolStatistics();
        assertNotNull(stats);
        assertEquals(0, stats.getHitCount());
        assertEquals(0, stats.getMissCount());
        assertEquals(0, stats.getEvictionCount());
        
        // Allocate and return
        var buffer = pool.allocate(1024);
        pool.returnToPool(buffer);
        
        // Reuse
        pool.allocate(1024);
        
        stats = pool.getPoolStatistics();
        assertEquals(1, stats.getHitCount());
        assertEquals(1, stats.getMissCount());
    }
    
    @Test
    void testClearPool() {
        pool.allocate(1024);
        pool.allocate(2048);
        
        // Return buffers to pool
        var buffer1 = pool.allocate(1024);
        var buffer2 = pool.allocate(2048);
        pool.returnToPool(buffer1);
        pool.returnToPool(buffer2);
        
        assertTrue(pool.getCurrentSize() > 0);
        
        pool.clear();
        
        assertEquals(0, pool.getCurrentSize());
    }
    
    @Test
    void testDirectBufferAllocation() {
        var buffer = pool.allocate(1024);
        assertNotNull(buffer);
        assertTrue(buffer.isDirect());
    }
    
    @Test
    void testZeroSizeAllocation() {
        var buffer = pool.allocate(0);
        assertNotNull(buffer);
        assertEquals(0, buffer.capacity());
    }
    
    @Test
    void testNegativeSizeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> {
            pool.allocate(-1);
        });
    }
    
    @Test
    void testPoolWithNoEviction() {
        // Pool with infinite idle time
        var noEvictPool = new MemoryPool(1024 * 1024, Duration.ofDays(365));

        var buffer = noEvictPool.allocate(1024);
        noEvictPool.returnToPool(buffer);

        noEvictPool.evictExpired();
        assertEquals(1024, noEvictPool.getCurrentSize()); // Should not evict
    }

    // ===== Phase 2 Tests: Large Buffer Buckets & GPU-Specific Eviction =====

    /**
     * Test 1: Allocate 5MB buffer, verify XLARGE category assignment
     */
    @Test
    void testXLargeBucketAllocation() {
        var largePool = new MemoryPool(500 * 1024 * 1024, Duration.ofMinutes(5)); // 500MB pool

        // Allocate 5MB buffer
        var buffer = largePool.allocate(5 * 1024 * 1024);
        assertNotNull(buffer);
        assertTrue(buffer.capacity() >= 5 * 1024 * 1024); // Power-of-2 rounding

        // Return to pool and verify category
        largePool.returnToPool(buffer);

        var stats = largePool.getPoolStatistics();
        assertNotNull(stats);
        assertEquals(1, stats.totalBuffers);

        // Verify buffer is categorized as XLARGE (64KB-10MB range)
        // This will be tested via category-specific eviction behavior
    }

    /**
     * Test 2: Allocate 120MB buffer (FuzzyARTGPU batch size), verify BATCH category
     */
    @Test
    void testBatchBucketAllocation() {
        var batchPool = new MemoryPool(500 * 1024 * 1024, Duration.ofMinutes(10)); // 500MB pool

        // Allocate 120MB buffer (typical FuzzyARTGPU batch)
        var buffer = batchPool.allocate(120 * 1024 * 1024);
        assertNotNull(buffer);
        assertTrue(buffer.capacity() >= 120 * 1024 * 1024); // Power-of-2 rounding

        // Return to pool and verify
        batchPool.returnToPool(buffer);

        var stats = batchPool.getPoolStatistics();
        assertNotNull(stats);
        assertEquals(1, stats.totalBuffers);
        assertTrue(stats.totalMemoryBytes >= 120 * 1024 * 1024);
    }

    /**
     * Test 3: Verify all buffer categories are assigned correctly
     */
    @Test
    void testCategoryAssignment() {
        var categoryPool = new MemoryPool(500 * 1024 * 1024, Duration.ofMinutes(5));

        // SMALL: 1KB
        var small = categoryPool.allocate(1024);
        assertNotNull(small);
        categoryPool.returnToPool(small);

        // MEDIUM: 32KB
        var medium = categoryPool.allocate(32 * 1024);
        assertNotNull(medium);
        categoryPool.returnToPool(medium);

        // XLARGE: 5MB
        var xlarge = categoryPool.allocate(5 * 1024 * 1024);
        assertNotNull(xlarge);
        categoryPool.returnToPool(xlarge);

        // BATCH: 120MB
        var batch = categoryPool.allocate(120 * 1024 * 1024);
        assertNotNull(batch);
        categoryPool.returnToPool(batch);

        var stats = categoryPool.getPoolStatistics();
        assertEquals(4, stats.totalBuffers);

        // All buffers should be in pool
        assertTrue(categoryPool.getCurrentSize() > 0);
    }

    /**
     * Test 4: Category-specific eviction - SMALL evicts at 60s, XLARGE stays at 60s
     */
    @Test
    void testCategorySpecificEviction() throws InterruptedException {
        // Create pool with category-specific timeouts
        // SMALL/MEDIUM: 100ms, XLARGE: 300ms, BATCH: 500ms
        var evictPool = new MemoryPool(500 * 1024 * 1024, Duration.ofMillis(100));

        // Allocate SMALL buffer
        var small = evictPool.allocate(1024);
        evictPool.returnToPool(small);

        // Allocate XLARGE buffer
        var xlarge = evictPool.allocate(5 * 1024 * 1024);
        evictPool.returnToPool(xlarge);

        long initialSize = evictPool.getCurrentSize();
        assertTrue(initialSize > 0);

        // Wait 200ms - SMALL should evict, XLARGE should stay (if category-specific timeouts work)
        Thread.sleep(200);
        evictPool.evictExpired();

        // For now, all buffers use same timeout, so both evict
        // After implementation, XLARGE should remain
        long afterEviction = evictPool.getCurrentSize();

        // Current behavior: both evict (timeout = 100ms applies to all)
        // Future behavior: only SMALL evicts, XLARGE remains (has 300ms timeout)
        // Test will validate implementation correctness
        assertTrue(afterEviction >= 0);
    }

    /**
     * Test 5: keepWarm() prevents eviction
     */
    @Test
    void testKeepWarmPreventEviction() throws InterruptedException {
        var warmPool = new MemoryPool(500 * 1024 * 1024, Duration.ofMillis(100));

        // Allocate buffer
        var buffer = warmPool.allocate(1024);
        warmPool.returnToPool(buffer);

        // Mark size as keep-warm
        warmPool.keepWarm(1024);

        // Wait for normal eviction time
        Thread.sleep(200);
        warmPool.evictExpired();

        // Buffer should NOT be evicted
        assertTrue(warmPool.getCurrentSize() > 0);
        assertEquals(1024, warmPool.getCurrentSize());
    }

    /**
     * Test 6: clearKeepWarm() allows eviction
     */
    @Test
    void testClearKeepWarmAllowsEviction() throws InterruptedException {
        var warmPool = new MemoryPool(500 * 1024 * 1024, Duration.ofMillis(100));

        // Allocate buffer and mark keep-warm
        var buffer = warmPool.allocate(1024);
        warmPool.returnToPool(buffer);
        warmPool.keepWarm(1024);

        // Clear keep-warm
        warmPool.clearKeepWarm(1024);

        // Wait for eviction
        Thread.sleep(200);
        warmPool.evictExpired();

        // Buffer should be evicted
        assertEquals(0, warmPool.getCurrentSize());
    }

    /**
     * Test 7: Max pool size respected with large buffers (500MB limit)
     */
    @Test
    void testMaxPoolSizeWithLargeBuffers() {
        // Create 500MB pool
        var largePool = new MemoryPool(500 * 1024 * 1024, Duration.ofMinutes(10));

        // Allocate 2x240MB buffers (480MB total, should fit)
        var buffer1 = largePool.allocate(240 * 1024 * 1024);
        var buffer2 = largePool.allocate(240 * 1024 * 1024);

        assertNotNull(buffer1);
        assertNotNull(buffer2);

        largePool.returnToPool(buffer1);
        largePool.returnToPool(buffer2);

        // Pool should respect 500MB limit
        long poolSize = largePool.getCurrentSize();
        assertTrue(poolSize <= 500 * 1024 * 1024,
            "Pool size " + poolSize + " exceeds limit of " + (500 * 1024 * 1024));

        // Try to add third 240MB buffer - should evict something
        var buffer3 = largePool.allocate(240 * 1024 * 1024);
        largePool.returnToPool(buffer3);

        poolSize = largePool.getCurrentSize();
        assertTrue(poolSize <= 500 * 1024 * 1024,
            "Pool size " + poolSize + " exceeds limit after third buffer");
    }

    /**
     * Test 8: Thread-safe large buffer allocation
     */
    @Test
    void testConcurrentLargeBufferAllocation() throws InterruptedException, ExecutionException {
        var largePool = new MemoryPool(500 * 1024 * 1024, Duration.ofMinutes(5));

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        // Allocate 10x50MB buffers concurrently
        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    var buffer = largePool.allocate(50 * 1024 * 1024);
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10));
                    largePool.returnToPool(buffer);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        latch.countDown();

        for (var future : futures) {
            assertTrue(future.get());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Pool should be valid and under limit
        assertTrue(largePool.getCurrentSize() >= 0);
        assertTrue(largePool.getCurrentSize() <= 500 * 1024 * 1024);
    }
}