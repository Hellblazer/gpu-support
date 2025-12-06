package com.hellblazer.luciferase.resource.memory;

import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Memory pool for efficient allocation and reuse of native memory buffers.
 * Reduces allocation overhead and fragmentation by reusing buffers.
 */
public class MemoryPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryPool.class);

    /**
     * Access type for pinned memory buffers.
     * Specifies how the buffer will be accessed by host and device.
     */
    public enum AccessType {
        READ_ONLY,    // Device reads, host writes
        WRITE_ONLY,   // Device writes, host reads
        READ_WRITE    // Both can read and write
    }

    /**
     * Pool configuration.
     */
    public static class Config {
        public final int minBufferSize;
        public final int maxBufferSize;
        public final int maxPoolSize;
        public final int maxBuffersPerSize;
        public final boolean alignBuffers;
        public final int alignment;
        
        private Config(Builder builder) {
            this.minBufferSize = builder.minBufferSize;
            this.maxBufferSize = builder.maxBufferSize;
            this.maxPoolSize = builder.maxPoolSize;
            this.maxBuffersPerSize = builder.maxBuffersPerSize;
            this.alignBuffers = builder.alignBuffers;
            this.alignment = builder.alignment;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private int minBufferSize = 256;
            private int maxBufferSize = 16 * 1024 * 1024; // 16MB
            private int maxPoolSize = 100;
            private int maxBuffersPerSize = 10;
            private boolean alignBuffers = false;
            private int alignment = 64;
            
            public Builder minBufferSize(int size) {
                this.minBufferSize = size;
                return this;
            }
            
            public Builder maxBufferSize(int size) {
                this.maxBufferSize = size;
                return this;
            }
            
            public Builder maxPoolSize(int size) {
                this.maxPoolSize = size;
                return this;
            }
            
            public Builder maxBuffersPerSize(int count) {
                this.maxBuffersPerSize = count;
                return this;
            }
            
            public Builder alignBuffers(boolean align) {
                this.alignBuffers = align;
                return this;
            }
            
            public Builder alignment(int alignment) {
                this.alignment = alignment;
                return this;
            }
            
            public Config build() {
                return new Config(this);
            }
        }
    }
    
    /**
     * Buffer size categories for GPU-specific eviction policies.
     * @deprecated Use {@link BufferPoolUtils.BufferCategory} instead
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public enum BufferCategory {
        SMALL(0, 64 * 1024),
        MEDIUM(64 * 1024, 10 * 1024 * 1024),
        XLARGE(10 * 1024 * 1024, 100 * 1024 * 1024),
        BATCH(100 * 1024 * 1024, Integer.MAX_VALUE);

        private final int minSize;
        private final int maxSize;

        BufferCategory(int minSize, int maxSize) {
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        public int getMinSize() { return minSize; }
        public int getMaxSize() { return maxSize; }

        public static BufferCategory fromSize(int size) {
            return switch (BufferPoolUtils.BufferCategory.fromSize(size)) {
                case SMALL -> SMALL;
                case MEDIUM -> MEDIUM;
                case XLARGE -> XLARGE;
                case BATCH -> BATCH;
            };
        }
    }

    /**
     * Pooled buffer wrapper that tracks usage.
     */
    private static class PooledBuffer {
        final ByteBuffer buffer;
        final long address;
        final int size;
        final boolean aligned;
        final BufferCategory category;
        volatile long lastUsed;
        volatile int useCount;

        PooledBuffer(ByteBuffer buffer, long address, int size, boolean aligned) {
            this.buffer = buffer;
            this.address = address;
            this.size = size;
            this.aligned = aligned;
            this.category = BufferCategory.fromSize(size);
            this.lastUsed = System.nanoTime();
            this.useCount = 0;
        }

        void markUsed() {
            lastUsed = System.nanoTime();
            useCount++;
        }

        long getIdleTime() {
            return System.nanoTime() - lastUsed;
        }
    }
    
    /**
     * Handle for a borrowed buffer that returns to pool on close.
     */
    public class BorrowedBuffer extends NativeMemoryHandle {
        private final PooledBuffer pooledBuffer;
        private volatile boolean returned = false;

        private BorrowedBuffer(PooledBuffer pooledBuffer, ResourceTracker tracker) {
            super(pooledBuffer.buffer.duplicate().clear(), pooledBuffer.address,
                  pooledBuffer.size, pooledBuffer.aligned, tracker);
            this.pooledBuffer = pooledBuffer;
            pooledBuffer.markUsed();
        }

        @Override
        protected void doCleanup(ByteBuffer buffer) {
            returnToPool();
        }

        private void returnToPool() {
            if (!returned) {
                returned = true;
                MemoryPool.this.returnBuffer(pooledBuffer);
            }
        }
    }

    /**
     * Pinned memory buffer wrapper combining host-side ByteBuffer with GPU-side CLBufferHandle.
     * Supports efficient DMA transfers between CPU and GPU.
     *
     * Usage:
     * <pre>
     * try (var pinned = pool.pinnedAllocate(size, context, AccessType.WRITE_ONLY)) {
     *     // Fill host buffer
     *     pinned.getHostBuffer().asFloatBuffer().put(data).flip();
     *
     *     // Upload to GPU
     *     pinned.enqueueUpload(queue, data, null, null);
     *     clFinish(queue);
     * }
     * </pre>
     */
    public class PinnedBuffer implements AutoCloseable {
        private final ByteBuffer hostBuffer;
        private final com.hellblazer.luciferase.resource.opencl.CLBufferHandle gpuBuffer;
        private final int size;
        private volatile boolean closed = false;

        private PinnedBuffer(ByteBuffer hostBuffer,
                           com.hellblazer.luciferase.resource.opencl.CLBufferHandle gpuBuffer,
                           int size) {
            this.hostBuffer = hostBuffer;
            this.gpuBuffer = gpuBuffer;
            this.size = size;
        }

        /**
         * Get the host-side buffer for CPU access.
         */
        public ByteBuffer getHostBuffer() {
            if (closed) {
                throw new IllegalStateException("PinnedBuffer is closed");
            }
            return hostBuffer;
        }

        /**
         * Get the GPU-side buffer handle.
         */
        public com.hellblazer.luciferase.resource.opencl.CLBufferHandle getGPUBuffer() {
            if (closed) {
                throw new IllegalStateException("PinnedBuffer is closed");
            }
            return gpuBuffer;
        }

        /**
         * Get buffer size in bytes.
         */
        public int getSize() {
            return size;
        }

        /**
         * Upload data from host to GPU (enqueued operation).
         *
         * @param queue OpenCL command queue
         * @param data Float array to upload (or null to use current hostBuffer contents)
         * @param events Event wait list
         * @param event Event to signal on completion
         */
        public void enqueueUpload(long queue, float[] data, org.lwjgl.PointerBuffer events,
                                org.lwjgl.PointerBuffer event) {
            if (closed) {
                throw new IllegalStateException("PinnedBuffer is closed");
            }

            // If data provided, copy to host buffer first
            if (data != null) {
                hostBuffer.clear();
                hostBuffer.asFloatBuffer().put(data);
                hostBuffer.rewind();
            }

            // Unmap to make data visible to GPU (DMA transfer)
            gpuBuffer.enqueueUnmap(queue, hostBuffer, events, event);
        }

        /**
         * Download data from GPU to host (enqueued operation).
         *
         * @param queue OpenCL command queue
         * @param events Event wait list
         * @param event Event to signal on completion
         */
        public void enqueueDownload(long queue, org.lwjgl.PointerBuffer events,
                                   org.lwjgl.PointerBuffer event) {
            if (closed) {
                throw new IllegalStateException("PinnedBuffer is closed");
            }

            // Map GPU buffer to host (triggers DMA download)
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var errorCode = stack.mallocInt(1);
                gpuBuffer.enqueueMap(
                    queue,
                    true, // blocking
                    org.lwjgl.opencl.CL10.CL_MAP_READ,
                    0,
                    size,
                    events,
                    event,
                    errorCode
                );
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                MemoryPool.this.returnPinnedBuffer(this);
            }
        }

        /**
         * Internal cleanup - called when buffer is actually freed (not pooled).
         */
        private void cleanup() {
            if (gpuBuffer != null) {
                gpuBuffer.close();
            }
            // hostBuffer will be freed when gpuBuffer is released (USE_HOST_PTR mode)
        }
    }

    private final Config config;
    private final ResourceTracker tracker;
    private final Map<Integer, ConcurrentLinkedDeque<PooledBuffer>> pools;
    private final Set<PooledBuffer> borrowed;
    private final Map<ByteBuffer, PooledBuffer> allocatedBuffers; // Track allocate() calls - use IdentityHashMap for object identity
    private final ReentrantLock lock;
    private final long maxIdleTimeNanos; // Max idle time before eviction (SMALL/MEDIUM)
    private final long maxPoolSizeBytes; // Max total bytes in pool (0 = use config.maxPoolSize for buffer count)
    private final Set<Integer> keepWarmSizes; // Buffer sizes to keep warm (prevent eviction)

    // GPU support (optional - 0 means not configured)
    private final long gpuContext;
    private final long gpuQueue;

    // Pinned buffer pool (separate from regular buffer pool)
    private final Map<Integer, ConcurrentLinkedDeque<PinnedBuffer>> pinnedPools;
    private final Set<PinnedBuffer> activePinnedBuffers;

    private final AtomicInteger totalBuffers;
    private final AtomicLong totalMemory;
    private final AtomicLong allocations;
    private final AtomicLong poolHits;
    private final AtomicLong poolMisses;
    
    private volatile boolean closed = false;
    
    /**
     * Create a memory pool with size and idle time configuration.
     */
    public MemoryPool(long maxPoolSizeBytes, java.time.Duration maxIdleTime) {
        this(Config.builder()
            .maxPoolSize(Integer.MAX_VALUE) // No buffer count limit when using byte size limit
            .maxBuffersPerSize(Integer.MAX_VALUE) // No per-size limit when using byte size limit
            .build(), 
            ResourceTracker.getGlobalTracker(),
            maxIdleTime,
            maxPoolSizeBytes);
    }
    
    /**
     * Create a memory pool with default configuration.
     */
    public MemoryPool(ResourceTracker tracker) {
        this(Config.builder().build(), tracker, java.time.Duration.ofMinutes(1));
    }
    
    /**
     * Create a memory pool with custom configuration.
     */
    public MemoryPool(Config config, ResourceTracker tracker) {
        this(config, tracker, java.time.Duration.ofMinutes(1));
    }
    
    /**
     * Create a memory pool with custom configuration and idle time.
     */
    public MemoryPool(Config config, ResourceTracker tracker, java.time.Duration maxIdleTime) {
        this(config, tracker, maxIdleTime, 0);
    }
    
    /**
     * Create a memory pool with custom configuration, idle time, and byte size limit.
     */
    private MemoryPool(Config config, ResourceTracker tracker, java.time.Duration maxIdleTime, long maxPoolSizeBytes) {
        this(config, tracker, maxIdleTime, maxPoolSizeBytes, 0, 0);
    }

    /**
     * Create a GPU-enabled memory pool with OpenCL context and queue.
     * Enables pinned memory allocation for DMA transfers.
     *
     * @param config Pool configuration
     * @param tracker Resource tracker for leak detection
     * @param context OpenCL context handle (0 to disable GPU features)
     * @param queue OpenCL command queue handle (0 to disable GPU features)
     */
    public MemoryPool(Config config, ResourceTracker tracker, long context, long queue) {
        this(config, tracker, java.time.Duration.ofMinutes(1), 0, context, queue);
    }

    /**
     * Internal constructor with all parameters.
     */
    private MemoryPool(Config config, ResourceTracker tracker, java.time.Duration maxIdleTime,
                       long maxPoolSizeBytes, long gpuContext, long gpuQueue) {
        this.config = config;
        this.tracker = tracker;
        this.pools = new HashMap<>();
        this.borrowed = new HashSet<>();
        this.allocatedBuffers = new IdentityHashMap<>(); // Use IdentityHashMap for object identity
        this.lock = new ReentrantLock();
        this.maxIdleTimeNanos = maxIdleTime.toNanos();
        this.maxPoolSizeBytes = maxPoolSizeBytes;
        this.keepWarmSizes = new HashSet<>();
        this.gpuContext = gpuContext;
        this.gpuQueue = gpuQueue;
        this.pinnedPools = new HashMap<>();
        this.activePinnedBuffers = new HashSet<>();

        this.totalBuffers = new AtomicInteger(0);
        this.totalMemory = new AtomicLong(0);
        this.allocations = new AtomicLong(0);
        this.poolHits = new AtomicLong(0);
        this.poolMisses = new AtomicLong(0);

        var gpuStatus = (gpuContext != 0 && gpuQueue != 0) ? "GPU-enabled" : "CPU-only";
        log.debug("Created {} memory pool with config: minSize={}, maxSize={}, maxPool={}",
            gpuStatus, config.minBufferSize, config.maxBufferSize, config.maxPoolSize);
    }
    
    /**
     * Borrow a buffer from the pool.
     * 
     * @param size Requested size in bytes
     * @return A borrowed buffer handle
     */
    public BorrowedBuffer borrow(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Size must be non-negative, got: " + size);
        }
        
        if (size == 0) {
            // Return an empty buffer for zero size
            return new BorrowedBuffer(
                new PooledBuffer(ByteBuffer.allocateDirect(0), 0, 0, false),
                tracker
            );
        }
        
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }
        
        if (size < config.minBufferSize || size > config.maxBufferSize) {
            // Size out of range, allocate directly without pooling
            log.trace("Size {} out of pool range, allocating directly", size);
            poolMisses.incrementAndGet();
            
            if (config.alignBuffers) {
                return new BorrowedBuffer(
                    new PooledBuffer(
                        MemoryUtil.memAlignedAlloc(config.alignment, size),
                        0, size, true
                    ), 
                    tracker
                );
            } else {
                return new BorrowedBuffer(
                    new PooledBuffer(
                        MemoryUtil.memAlloc(size),
                        0, size, false
                    ),
                    tracker
                );
            }
        }
        
        allocations.incrementAndGet();
        
        // Round up to power of 2 for better reuse
        int poolSize = roundUpToPowerOf2(size);
        
        lock.lock();
        try {
            var pool = pools.computeIfAbsent(poolSize, k -> new ConcurrentLinkedDeque<>());
            
            // Try to get from pool
            PooledBuffer buffer = pool.poll();
            if (buffer != null) {
                poolHits.incrementAndGet();
                log.trace("Reusing buffer of size {} from pool", poolSize);
                
                // Clear buffer before reuse - ensure it's zeroed
                buffer.buffer.clear();
                // Use bulk put operation for better performance and reliability
                int capacity = buffer.buffer.capacity();
                buffer.buffer.position(0);
                buffer.buffer.limit(capacity);
                
                // Fill with zeros using bulk operations for better performance
                // This works more reliably across different environments
                while (buffer.buffer.remaining() >= 8) {
                    buffer.buffer.putLong(0L);
                }
                while (buffer.buffer.hasRemaining()) {
                    buffer.buffer.put((byte) 0);
                }
                
                buffer.buffer.clear(); // Reset position and limit
                
                borrowed.add(buffer);
                return new BorrowedBuffer(buffer, tracker);
            }
            
            // Need to allocate new buffer
            poolMisses.incrementAndGet();
            
            if (totalBuffers.get() >= config.maxPoolSize) {
                // Pool is full, try to evict old buffers
                evictOldBuffers();
            }
            
            // Allocate new buffer
            ByteBuffer newBuffer;
            if (config.alignBuffers) {
                newBuffer = MemoryUtil.memAlignedAlloc(config.alignment, poolSize);
            } else {
                newBuffer = MemoryUtil.memAlloc(poolSize);
            }
            
            // Zero the newly allocated buffer - LWJGL doesn't zero memory by default
            newBuffer.clear();
            while (newBuffer.remaining() >= 8) {
                newBuffer.putLong(0L);
            }
            while (newBuffer.hasRemaining()) {
                newBuffer.put((byte) 0);
            }
            newBuffer.clear(); // Reset position and limit
            
            var pooledBuffer = new PooledBuffer(
                newBuffer,
                MemoryUtil.memAddress(newBuffer),
                poolSize,
                config.alignBuffers
            );
            
            totalBuffers.incrementAndGet();
            totalMemory.addAndGet(poolSize);
            
            log.trace("Allocated new buffer of size {} for pool", poolSize);
            
            borrowed.add(pooledBuffer);
            return new BorrowedBuffer(pooledBuffer, tracker);
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Return a buffer to the pool.
     */
    private void returnBuffer(PooledBuffer buffer) {
        if (closed) {
            // Pool is closed, free the buffer
            freeBuffer(buffer);
            return;
        }
        
        lock.lock();
        try {
            borrowed.remove(buffer);
            
            var pool = pools.get(buffer.size);
            if (pool != null && pool.size() < config.maxBuffersPerSize) {
                pool.offer(buffer);
                log.trace("Returned buffer of size {} to pool", buffer.size);
            } else {
                // Pool for this size is full, free the buffer
                freeBuffer(buffer);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get category-specific idle timeout in nanoseconds.
     */
    private long getCategoryTimeout(BufferCategory category) {
        return switch (category) {
            case SMALL, MEDIUM -> maxIdleTimeNanos;           // 60s default
            case XLARGE -> maxIdleTimeNanos * 5;               // 5x longer (5 min if base is 60s)
            case BATCH -> maxIdleTimeNanos * 10;               // 10x longer (10 min if base is 60s)
        };
    }

    /**
     * Evict old buffers that haven't been used recently.
     * Uses category-specific timeouts and respects keep-warm sizes.
     */
    private void evictOldBuffers() {
        for (var pool : pools.values()) {
            var iterator = pool.iterator();
            while (iterator.hasNext()) {
                var buffer = iterator.next();

                // Check if this size is keep-warm
                if (keepWarmSizes.contains(buffer.size)) {
                    continue; // Skip eviction for keep-warm buffers
                }

                // Use category-specific timeout
                long categoryTimeout = getCategoryTimeout(buffer.category);
                if (buffer.getIdleTime() > categoryTimeout) {
                    iterator.remove();
                    freeBuffer(buffer);
                    log.trace("Evicted idle {} buffer of size {}", buffer.category, buffer.size);
                }
            }
        }
    }
    
    /**
     * Free a pooled buffer.
     */
    private void freeBuffer(PooledBuffer buffer) {
        if (buffer.aligned) {
            MemoryUtil.memAlignedFree(buffer.buffer);
        } else {
            MemoryUtil.memFree(buffer.buffer);
        }
        
        totalBuffers.decrementAndGet();
        totalMemory.addAndGet(-buffer.size);
        
        log.trace("Freed buffer of size {}", buffer.size);
    }
    
    /**
     * Round up to nearest power of 2.
     * @deprecated Use {@link BufferPoolUtils#roundUpToPowerOf2(int)} instead
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private static int roundUpToPowerOf2(int value) {
        return BufferPoolUtils.roundUpToPowerOf2(value);
    }
    
    /**
     * Allocate a buffer from the pool.
     * Simple wrapper for compatibility.
     * Note: Buffers allocated this way MUST be returned via returnToPool().
     */
    public ByteBuffer allocate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Size must be non-negative, got: " + size);
        }
        
        if (size == 0) {
            // Return an empty buffer for zero size
            return ByteBuffer.allocateDirect(0);
        }
        
        allocations.incrementAndGet();
        
        // Round up to power of 2 for better reuse
        int poolSize = roundUpToPowerOf2(size);
        
        lock.lock();
        try {
            var pool = pools.computeIfAbsent(poolSize, k -> new ConcurrentLinkedDeque<>());
            
            // Try to get from pool
            PooledBuffer buffer = pool.poll();
            if (buffer != null) {
                poolHits.incrementAndGet();
                // DON'T count as allocated when reusing from pool - it's already in totalMemory
                log.debug("Reusing buffer {} of size {} from pool", 
                         System.identityHashCode(buffer.buffer), poolSize);
                
                // Clear buffer before reuse - ensure it's zeroed
                buffer.buffer.clear();
                // Use bulk put operation for better performance and reliability
                int capacity = buffer.buffer.capacity();
                buffer.buffer.position(0);
                buffer.buffer.limit(capacity);
                
                // Fill with zeros using bulk operations for better performance
                // This works more reliably across different environments
                while (buffer.buffer.remaining() >= 8) {
                    buffer.buffer.putLong(0L);
                }
                while (buffer.buffer.hasRemaining()) {
                    buffer.buffer.put((byte) 0);
                }
                
                buffer.buffer.clear(); // Reset position and limit
                
                allocatedBuffers.put(buffer.buffer, buffer);
                return buffer.buffer;
            }
            
            // Need to allocate new buffer
            poolMisses.incrementAndGet();
            
            // Allocate new buffer
            ByteBuffer newBuffer = MemoryUtil.memAlloc(poolSize);
            
            // Zero the newly allocated buffer - LWJGL doesn't zero memory by default
            newBuffer.clear();
            while (newBuffer.remaining() >= 8) {
                newBuffer.putLong(0L);
            }
            while (newBuffer.hasRemaining()) {
                newBuffer.put((byte) 0);
            }
            newBuffer.clear(); // Reset position and limit
            
            var pooledBuffer = new PooledBuffer(
                newBuffer,
                MemoryUtil.memAddress(newBuffer),
                poolSize,
                false
            );
            
            totalBuffers.incrementAndGet();
            totalMemory.addAndGet(poolSize);
            
            log.debug("Allocated new buffer {} of size {} for pool", 
                     System.identityHashCode(newBuffer), poolSize);
            
            allocatedBuffers.put(newBuffer, pooledBuffer);
            return newBuffer;
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Return a buffer to the pool.
     */
    public void returnToPool(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        
        lock.lock();
        try {
            PooledBuffer pooledBuffer = allocatedBuffers.remove(buffer);
            if (pooledBuffer != null) {
                var pool = pools.get(pooledBuffer.size);
                
                // Check if we should keep this buffer based on size limits
                boolean shouldKeep = false;
                if (maxPoolSizeBytes > 0) {
                    // Using byte size limit - calculate current pool size inline to avoid lock issues
                    long currentPoolSize = 0;
                    for (var p : pools.values()) {
                        for (var buf : p) {
                            currentPoolSize += buf.size;
                        }
                    }
                    shouldKeep = (currentPoolSize + pooledBuffer.size) <= maxPoolSizeBytes;
                } else {
                    // Using buffer count limit
                    shouldKeep = pool != null && pool.size() < config.maxBuffersPerSize;
                }
                
                if (shouldKeep && pool != null) {
                    // Check if this buffer is already in the pool (duplicate return)
                    boolean alreadyInPool = pool.stream()
                        .anyMatch(pb -> pb.buffer == pooledBuffer.buffer);
                    
                    if (!alreadyInPool) {
                        // Mark the buffer as used when returning to pool so idle time resets
                        pooledBuffer.markUsed();
                        pool.offer(pooledBuffer);
                        log.debug("Returned buffer of size {} to pool (pool now has {} buffers, {} bytes total)", 
                                 pooledBuffer.size, pool.size(), getCurrentSize() + pooledBuffer.size);
                    } else {
                        log.debug("Buffer {} already in pool, ignoring duplicate return", 
                                 System.identityHashCode(buffer));
                    }
                } else {
                    // Pool is full or doesn't exist, free the buffer
                    log.debug("Pool limit reached (byte limit: {}, current: {}), freeing buffer of size {}", 
                             maxPoolSizeBytes > 0 ? maxPoolSizeBytes : "N/A", 
                             getCurrentSize(), pooledBuffer.size);
                    freeBuffer(pooledBuffer);
                }
            } else {
                // Buffer was not tracked or already returned
                log.trace("Buffer {} not tracked or already returned", 
                         System.identityHashCode(buffer));
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Evict expired buffers from the pool.
     */
    public void evictExpired() {
        evictOldBuffers();
    }
    
    /**
     * Get current size of pool in bytes.
     */
    public long getCurrentSize() {
        lock.lock();
        try {
            long size = 0;
            for (var pool : pools.values()) {
                for (var buffer : pool) {
                    size += buffer.size;
                }
            }
            return size;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get hit rate as a percentage.
     */
    public float getHitRate() {
        long total = allocations.get();
        if (total == 0) return 0.0f;
        return (float) poolHits.get() / total;
    }
    
    /**
     * Get pool statistics.
     */
    public String getStatistics() {
        float hitRate = allocations.get() > 0 
            ? (float) poolHits.get() / allocations.get() * 100 
            : 0;
        
        return String.format(
            "MemoryPool[buffers=%d, memory=%s, allocations=%d, hitRate=%.1f%%, borrowed=%d]",
            totalBuffers.get(),
            formatBytes(totalMemory.get()),
            allocations.get(),
            hitRate,
            borrowed.size()
        );
    }
    
    /**
     * Get detailed pool statistics.
     */
    public PoolStatistics getPoolStatistics() {
        lock.lock();
        try {
            return new PoolStatistics(
                totalBuffers.get(),
                totalMemory.get(),
                allocations.get(),
                poolHits.get(),
                poolMisses.get(),
                borrowed.size(),
                pools.size()
            );
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Statistics snapshot for the memory pool.
     */
    public static class PoolStatistics {
        public final int totalBuffers;
        public final long totalMemoryBytes;
        public final long totalAllocations;
        public final long poolHits;
        public final long poolMisses;
        public final int currentlyBorrowed;
        public final int poolSizes;
        
        public PoolStatistics(int totalBuffers, long totalMemoryBytes, long totalAllocations,
                            long poolHits, long poolMisses, int currentlyBorrowed, int poolSizes) {
            this.totalBuffers = totalBuffers;
            this.totalMemoryBytes = totalMemoryBytes;
            this.totalAllocations = totalAllocations;
            this.poolHits = poolHits;
            this.poolMisses = poolMisses;
            this.currentlyBorrowed = currentlyBorrowed;
            this.poolSizes = poolSizes;
        }
        
        public float getHitRate() {
            return totalAllocations > 0 ? (float) poolHits / totalAllocations : 0;
        }
        
        public long getTotalMemoryMB() {
            return totalMemoryBytes / (1024 * 1024);
        }
        
        public long getTotalAllocated() {
            return totalMemoryBytes;
        }
        
        public long getUsedMemory() {
            // Memory currently in use (borrowed buffers)
            return currentlyBorrowed * (totalMemoryBytes / Math.max(totalBuffers, 1));
        }
        
        public long getHitCount() {
            return poolHits;
        }
        
        public long getMissCount() {
            return poolMisses;
        }
        
        public long getEvictionCount() {
            // Not tracked separately, return 0 for now
            return 0;
        }
    }
    
    /**
     * Format bytes for display.
     * @deprecated Use {@link BufferPoolUtils#formatBytes(long)} instead
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private static String formatBytes(long bytes) {
        return BufferPoolUtils.formatBytes(bytes);
    }
    
    /**
     * Clear all pooled buffers.
     */
    public void clear() {
        lock.lock();
        try {
            for (var pool : pools.values()) {
                PooledBuffer buffer;
                while ((buffer = pool.poll()) != null) {
                    freeBuffer(buffer);
                }
            }
            pools.clear();
            
            log.debug("Cleared memory pool: {}", getStatistics());
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Mark a buffer size as "keep warm" - prevents eviction.
     * Useful for hot paths like FuzzyARTGPU batch operations.
     *
     * @param size Buffer size to keep warm (will be rounded to power-of-2)
     */
    public void keepWarm(int size) {
        int poolSize = roundUpToPowerOf2(size);
        lock.lock();
        try {
            keepWarmSizes.add(poolSize);
            log.debug("Marked buffer size {} as keep-warm", poolSize);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clear keep-warm status for a buffer size - allows eviction.
     *
     * @param size Buffer size to clear (will be rounded to power-of-2)
     */
    public void clearKeepWarm(int size) {
        int poolSize = roundUpToPowerOf2(size);
        lock.lock();
        try {
            keepWarmSizes.remove(poolSize);
            log.debug("Cleared keep-warm for buffer size {}", poolSize);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Allocate a pinned memory buffer for efficient GPU DMA transfers.
     * Pinned buffers use CL_MEM_ALLOC_HOST_PTR for zero-copy transfers.
     * Supports pooling and reuse for frequently-used sizes.
     *
     * @param size Size in bytes
     * @param context OpenCL context handle (currently unused, kept for API consistency)
     * @param access Access pattern (READ_ONLY, WRITE_ONLY, READ_WRITE)
     * @return PinnedBuffer combining host and GPU buffers
     * @throws IllegalStateException if GPU not configured (context/queue are 0)
     */
    public PinnedBuffer pinnedAllocate(int size, long context, AccessType access) {
        if (gpuContext == 0 || gpuQueue == 0) {
            throw new IllegalStateException("Cannot allocate pinned memory: GPU not configured. " +
                "Use MemoryPool(Config, ResourceTracker, long context, long queue) constructor.");
        }

        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive, got: " + size);
        }

        allocations.incrementAndGet();

        // Round up to power of 2 for better pooling
        int poolSize = roundUpToPowerOf2(size);

        lock.lock();
        try {
            // Check pool for existing buffer
            var pool = pinnedPools.computeIfAbsent(poolSize, k -> new ConcurrentLinkedDeque<>());
            var pooledBuffer = pool.poll();

            if (pooledBuffer != null) {
                poolHits.incrementAndGet();
                // Reset closed flag so the buffer can be closed again after use
                pooledBuffer.closed = false;
                activePinnedBuffers.add(pooledBuffer);
                log.debug("Reusing pinned buffer: size={}, access={}", size, access);
                return pooledBuffer;
            }

            // Need to allocate new pinned buffer
            poolMisses.incrementAndGet();

            // Map AccessType to OpenCL flags
            int clFlags = switch (access) {
                case READ_ONLY -> org.lwjgl.opencl.CL10.CL_MEM_READ_ONLY |
                                 org.lwjgl.opencl.CL10.CL_MEM_ALLOC_HOST_PTR;
                case WRITE_ONLY -> org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY |
                                  org.lwjgl.opencl.CL10.CL_MEM_ALLOC_HOST_PTR;
                case READ_WRITE -> org.lwjgl.opencl.CL10.CL_MEM_READ_WRITE |
                                  org.lwjgl.opencl.CL10.CL_MEM_ALLOC_HOST_PTR;
            };

            // Create GPU buffer with pinned memory (ALLOC_HOST_PTR)
            var gpuBuffer = com.hellblazer.luciferase.resource.opencl.CLBufferHandle.create(
                gpuContext,
                poolSize,
                clFlags
            );

            // Map the GPU buffer to get the pinned host pointer
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var errorCode = stack.mallocInt(1);
                int mapFlags = switch (access) {
                    case READ_ONLY -> org.lwjgl.opencl.CL10.CL_MAP_WRITE;   // Host writes, device reads
                    case WRITE_ONLY -> org.lwjgl.opencl.CL10.CL_MAP_READ;   // Host reads, device writes
                    case READ_WRITE -> org.lwjgl.opencl.CL10.CL_MAP_READ |
                                      org.lwjgl.opencl.CL10.CL_MAP_WRITE;
                };

                var pinnedHost = gpuBuffer.enqueueMap(
                    gpuQueue,
                    true, // blocking
                    mapFlags,
                    0,
                    poolSize,
                    null,
                    null,
                    errorCode
                );

                if (errorCode.get(0) != org.lwjgl.opencl.CL10.CL_SUCCESS) {
                    gpuBuffer.close();
                    throw new RuntimeException("Failed to map pinned buffer: " + errorCode.get(0));
                }

                var newBuffer = new PinnedBuffer(pinnedHost, gpuBuffer, poolSize);
                activePinnedBuffers.add(newBuffer);

                totalBuffers.incrementAndGet();
                totalMemory.addAndGet(poolSize);

                log.debug("Allocated new pinned buffer: size={}, access={}", poolSize, access);
                return newBuffer;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return a pinned buffer to the pool for reuse.
     */
    private void returnPinnedBuffer(PinnedBuffer buffer) {
        if (closed) {
            // Pool is closed, free the buffer
            buffer.cleanup();
            return;
        }

        lock.lock();
        try {
            activePinnedBuffers.remove(buffer);

            var pool = pinnedPools.get(buffer.size);
            if (pool != null && pool.size() < config.maxBuffersPerSize) {
                pool.offer(buffer);
                log.debug("Returned pinned buffer to pool: size={}", buffer.size);
            } else {
                // Pool full, free the buffer
                buffer.cleanup();
                totalBuffers.decrementAndGet();
                totalMemory.addAndGet(-buffer.size);
                log.debug("Freed pinned buffer (pool full): size={}", buffer.size);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        lock.lock();
        try {
            // Log warning if buffers still borrowed
            if (!borrowed.isEmpty()) {
                log.warn("Closing pool with {} buffers still borrowed", borrowed.size());
            }

            // Log warning if pinned buffers still active
            if (!activePinnedBuffers.isEmpty()) {
                log.warn("Closing pool with {} pinned buffers still active", activePinnedBuffers.size());
            }

            // Free all pooled buffers
            clear();

            // Free borrowed buffers (leak prevention)
            for (var buffer : borrowed) {
                freeBuffer(buffer);
            }
            borrowed.clear();

            // Free allocated buffers
            for (var buffer : allocatedBuffers.values()) {
                freeBuffer(buffer);
            }
            allocatedBuffers.clear();

            // Free active pinned buffers
            for (var buffer : activePinnedBuffers) {
                buffer.cleanup();
            }
            activePinnedBuffers.clear();

            // Free pooled pinned buffers
            for (var pool : pinnedPools.values()) {
                PinnedBuffer buffer;
                while ((buffer = pool.poll()) != null) {
                    buffer.cleanup();
                }
            }
            pinnedPools.clear();

            log.debug("Closed memory pool: total allocations={}, hit rate={}%",
                allocations.get(),
                allocations.get() > 0 ? String.format("%.1f", (float) poolHits.get() / allocations.get() * 100) : "0.0");

        } finally {
            lock.unlock();
        }
    }
}
