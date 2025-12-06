package com.hellblazer.luciferase.resource.opencl;

import com.hellblazer.luciferase.resource.ResourceHandle;
import com.hellblazer.luciferase.resource.ResourceTracker;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * RAII handle for OpenCL buffers with pool awareness and performance metrics.
 *
 * Phase 1 Enhancements (ART-529):
 * - Pool awareness via onRelease callback
 * - Large buffer support (120MB-240MB)
 * - Performance metrics tracking
 */
public class CLBufferHandle extends ResourceHandle<Long> {
    private static final Logger log = LoggerFactory.getLogger(CLBufferHandle.class);

    // Global statistics (shared across all instances)
    private static final CLBufferStatistics GLOBAL_STATISTICS = new CLBufferStatistics();

    private final long context;
    private final long size;
    private final int flags;
    private final BufferType type;
    private final Runnable onRelease; // Pool awareness callback
    
    public enum BufferType {
        READ_ONLY(CL10.CL_MEM_READ_ONLY),
        WRITE_ONLY(CL10.CL_MEM_WRITE_ONLY),
        READ_WRITE(CL10.CL_MEM_READ_WRITE),
        HOST_READ_ONLY(CL12.CL_MEM_HOST_READ_ONLY),
        HOST_WRITE_ONLY(CL12.CL_MEM_HOST_WRITE_ONLY),
        HOST_NO_ACCESS(CL12.CL_MEM_HOST_NO_ACCESS),
        USE_HOST_PTR(CL10.CL_MEM_USE_HOST_PTR),
        ALLOC_HOST_PTR(CL10.CL_MEM_ALLOC_HOST_PTR),
        COPY_HOST_PTR(CL10.CL_MEM_COPY_HOST_PTR),
        READ_ONLY_PERSISTENT(CL10.CL_MEM_READ_ONLY | CL10.CL_MEM_ALLOC_HOST_PTR),
        READ_WRITE_PERSISTENT(CL10.CL_MEM_READ_WRITE | CL10.CL_MEM_ALLOC_HOST_PTR);

        private final int flag;

        BufferType(int flag) {
            this.flag = flag;
        }

        public int getFlag() {
            return flag;
        }
    }
    
    /**
     * Create a new OpenCL buffer
     */
    public static CLBufferHandle create(long context, long size, BufferType type) {
        return create(context, size, type.getFlag(), null, null);
    }

    /**
     * Create a new OpenCL buffer with pool awareness callback
     */
    public static CLBufferHandle create(long context, long size, BufferType type, Runnable onRelease) {
        return create(context, size, type.getFlag(), null, onRelease);
    }

    /**
     * Create a new OpenCL buffer with combined flags
     */
    public static CLBufferHandle create(long context, long size, int flags) {
        return create(context, size, flags, null, null);
    }
    
    /**
     * Create a new OpenCL buffer with host data
     */
    public static CLBufferHandle createWithData(long context, ByteBuffer hostData, BufferType type) {
        int flags = type.getFlag() | CL10.CL_MEM_COPY_HOST_PTR;
        return create(context, hostData.remaining(), flags, hostData, null);
    }

    /**
     * Create a new OpenCL buffer with explicit ResourceTracker
     */
    public static CLBufferHandle createWithTracker(long context, long size, BufferType type,
                                                   ResourceTracker tracker) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            long buffer = CL10.clCreateBuffer(
                context,
                type.getFlag(),
                size,
                errcode
            );

            checkError(errcode.get(0));

            if (buffer == 0) {
                throw new IllegalStateException("Failed to create OpenCL buffer");
            }

            // Create handle with explicit tracker
            var handle = new CLBufferHandle(buffer, context, size, type.getFlag(), null, tracker);

            // Record allocation in statistics
            GLOBAL_STATISTICS.recordAllocation();

            return handle;
        }
    }

    private static CLBufferHandle create(long context, long size, int flags, ByteBuffer hostData,
                                        Runnable onRelease) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer errcode = stack.mallocInt(1);

            long buffer = CL10.clCreateBuffer(
                context,
                flags,
                size,
                errcode
            );

            checkError(errcode.get(0));

            if (buffer == 0) {
                throw new IllegalStateException("Failed to create OpenCL buffer");
            }

            var handle = new CLBufferHandle(buffer, context, size, flags, onRelease);
            // Note: Tracking is handled by ResourceHandle constructor - don't double-track!

            // Record allocation in statistics
            GLOBAL_STATISTICS.recordAllocation();

            // Warn if large buffer without pool callback
            if (com.hellblazer.luciferase.resource.memory.BufferPoolUtils.isLargeBuffer(size) && onRelease == null) {
                log.warn("Large buffer ({}) allocated without pool callback - consider using memory pooling",
                        com.hellblazer.luciferase.resource.memory.BufferPoolUtils.formatBytes(size));
            }

            // Enhanced logging to track buffer allocation with stack trace for debugging
            var stackTrace = Thread.currentThread().getStackTrace();
            var caller = "unknown";
            for (int i = 2; i < Math.min(stackTrace.length, 10); i++) {
                var element = stackTrace[i];
                if (element.getClassName().contains("BipoleCellNetwork")) {
                    caller = element.getMethodName() + ":" + element.getLineNumber();
                    break;
                }
            }

            log.debug("CLBufferHandle created: handle={}, size={} bytes, flags={}, caller={}",
                     buffer, size, flags, caller);

            // Copy host data if provided
            if (hostData != null && (flags & CL10.CL_MEM_COPY_HOST_PTR) != 0) {
                // Data is already copied by clCreateBuffer when CL_MEM_COPY_HOST_PTR is set
                log.debug("Initialized buffer with {} bytes of host data", hostData.remaining());
            }

            return handle;
        }
    }
    
    /**
     * Wrap an existing OpenCL buffer handle
     */
    public static CLBufferHandle wrap(long buffer, long context, long size, int flags) {
        if (buffer == 0) {
            throw new IllegalArgumentException("Invalid buffer handle");
        }

        var handle = new CLBufferHandle(buffer, context, size, flags, null);
        // Note: Tracking is handled by ResourceHandle constructor - don't double-track!
        return handle;
    }

    // Package-private constructor for testing
    CLBufferHandle(long buffer, long context, long size, int flags, Runnable onRelease) {
        this(buffer, context, size, flags, onRelease, ResourceTracker.getGlobalTracker());
    }

    // Constructor with explicit tracker
    CLBufferHandle(long buffer, long context, long size, int flags, Runnable onRelease,
                   ResourceTracker tracker) {
        super(buffer, tracker);
        this.context = context;
        this.size = size;
        this.flags = flags;
        this.onRelease = onRelease;

        // Determine buffer type from flags
        boolean hasAllocHostPtr = (flags & CL10.CL_MEM_ALLOC_HOST_PTR) != 0;

        if ((flags & CL10.CL_MEM_READ_ONLY) != 0) {
            this.type = hasAllocHostPtr ? BufferType.READ_ONLY_PERSISTENT : BufferType.READ_ONLY;
        } else if ((flags & CL10.CL_MEM_WRITE_ONLY) != 0) {
            this.type = BufferType.WRITE_ONLY;
        } else {
            this.type = hasAllocHostPtr ? BufferType.READ_WRITE_PERSISTENT : BufferType.READ_WRITE;
        }
    }
    
    /**
     * Get buffer size
     */
    public long getSize() {
        return size;
    }
    
    /**
     * Get buffer flags
     */
    public int getFlags() {
        return flags;
    }
    
    /**
     * Get buffer type
     */
    public BufferType getType() {
        return type;
    }
    
    /**
     * Get context
     */
    public long getContext() {
        return context;
    }
    
    /**
     * Get actual buffer size from OpenCL
     */
    public long getActualSize() {
        Long buffer = get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var sizeBuffer = stack.mallocPointer(1);
            
            int error = CL10.clGetMemObjectInfo(
                buffer.longValue(),
                CL10.CL_MEM_SIZE,
                sizeBuffer,
                null
            );
            checkError(error);
            
            return sizeBuffer.get(0);
        }
    }
    
    /**
     * Get reference count
     */
    public int getReferenceCount() {
        Long buffer = get(); // Ensure not closed
        
        try (var stack = MemoryStack.stackPush()) {
            var countBuffer = stack.mallocInt(1);
            
            int error = CL10.clGetMemObjectInfo(
                buffer.longValue(),
                CL10.CL_MEM_REFERENCE_COUNT,
                countBuffer,
                null
            );
            checkError(error);
            
            return countBuffer.get(0);
        }
    }
    
    /**
     * Check if buffer uses host pointer
     */
    public boolean usesHostPointer() {
        return (flags & CL10.CL_MEM_USE_HOST_PTR) != 0;
    }
    
    /**
     * Check if buffer is allocated on host
     */
    public boolean isHostAllocated() {
        return (flags & CL10.CL_MEM_ALLOC_HOST_PTR) != 0;
    }
    
    /**
     * Enqueue read from buffer to host memory
     */
    public void enqueueRead(long queue, boolean blocking, long offset, ByteBuffer data, 
                           PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Read exceeds buffer bounds");
        }
        
        int error = CL10.clEnqueueReadBuffer(
            queue,
            buffer.longValue(),
            blocking,
            offset,
            data,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued read of {} bytes from buffer at offset {}", data.remaining(), offset);
    }
    
    /**
     * Enqueue write from host memory to buffer
     */
    public void enqueueWrite(long queue, boolean blocking, long offset, ByteBuffer data,
                            PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Write exceeds buffer bounds");
        }
        
        int error = CL10.clEnqueueWriteBuffer(
            queue,
            buffer.longValue(),
            blocking,
            offset,
            data,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued write of {} bytes to buffer at offset {}", data.remaining(), offset);
    }
    
    /**
     * Enqueue copy from this buffer to another
     */
    public void enqueueCopyTo(long queue, CLBufferHandle dest, long srcOffset, long destOffset,
                             long size, PointerBuffer events, PointerBuffer event) {
        Long srcBuffer = get(); // Ensure not closed
        Long destBuffer = dest.get(); // Ensure dest not closed
        
        if (srcOffset + size > this.size) {
            throw new IllegalArgumentException("Copy exceeds source buffer bounds");
        }
        if (destOffset + size > dest.size) {
            throw new IllegalArgumentException("Copy exceeds destination buffer bounds");
        }
        
        int error = CL10.clEnqueueCopyBuffer(
            queue,
            srcBuffer.longValue(),
            destBuffer.longValue(),
            srcOffset,
            destOffset,
            size,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued copy of {} bytes from buffer to buffer", size);
    }
    
    /**
     * Map buffer for host access
     */
    public ByteBuffer enqueueMap(long queue, boolean blocking, int mapFlags, long offset, long size,
                                 PointerBuffer events, PointerBuffer event, IntBuffer errorCode) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + size > this.size) {
            throw new IllegalArgumentException("Map exceeds buffer bounds");
        }
        
        ByteBuffer mapped = CL10.clEnqueueMapBuffer(
            queue,
            buffer.longValue(),
            blocking,
            mapFlags,
            offset,
            size,
            events,
            event,
            errorCode,
            null
        );
        
        if (errorCode != null && errorCode.get(0) != CL10.CL_SUCCESS) {
            checkError(errorCode.get(0));
        }
        
        log.debug("Mapped {} bytes of buffer at offset {}", size, offset);
        return mapped;
    }
    
    /**
     * Unmap previously mapped buffer
     */
    public void enqueueUnmap(long queue, ByteBuffer mappedPtr, PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        int error = CL10.clEnqueueUnmapMemObject(queue, buffer.longValue(), mappedPtr, events, event);
        checkError(error);
        
        log.debug("Unmapped buffer");
    }
    
    /**
     * Fill buffer with pattern
     */
    public void enqueueFill(long queue, ByteBuffer pattern, long offset, long size,
                           PointerBuffer events, PointerBuffer event) {
        Long buffer = get(); // Ensure not closed
        
        if (offset + size > this.size) {
            throw new IllegalArgumentException("Fill exceeds buffer bounds");
        }
        
        // Use the standard clEnqueueFillBuffer from CL12
        int error = CL12.clEnqueueFillBuffer(
            queue,
            buffer.longValue(),
            pattern,
            offset,
            size,
            events,
            event
        );
        checkError(error);
        
        log.debug("Enqueued fill of {} bytes at offset {} with pattern", size, offset);
    }
    
    @Override
    protected void doCleanup(Long buffer) {
        // Invoke onRelease callback before cleanup (pool awareness)
        if (onRelease != null) {
            try {
                onRelease.run();
            } catch (Exception e) {
                log.error("Error in onRelease callback for buffer {}", buffer, e);
                // Continue with cleanup despite callback error
            }
        }

        // Record buffer closure metrics
        var lifetimeNs = System.nanoTime() - getAllocationTime();
        GLOBAL_STATISTICS.recordClosure(lifetimeNs);

        // Release OpenCL buffer
        int error = CL10.clReleaseMemObject(buffer);
        if (error != CL10.CL_SUCCESS) {
            log.error("CLBufferHandle FAILED TO RELEASE: handle={}, error={}", buffer, error);
        } else {
            log.debug("CLBufferHandle released: handle={}, size={} bytes", buffer, size);
        }
        // Note: Untracking is handled by ResourceHandle.close() - don't double-untrack!
    }
    
    private static void checkError(int error) {
        if (error != CL10.CL_SUCCESS) {
            throw new RuntimeException("OpenCL error: " + translateError(error));
        }
    }

    /**
     * Translate OpenCL error codes to human-readable messages
     */
    public static String translateError(int error) {
        return switch (error) {
            case CL10.CL_SUCCESS -> "Success";
            case CL10.CL_DEVICE_NOT_FOUND -> "Device not found (-1)";
            case CL10.CL_DEVICE_NOT_AVAILABLE -> "Device not available (-2)";
            case CL10.CL_COMPILER_NOT_AVAILABLE -> "Compiler not available (-3)";
            case CL10.CL_MEM_OBJECT_ALLOCATION_FAILURE -> "Memory object allocation failure (-4)";
            case CL10.CL_OUT_OF_RESOURCES -> "Out of resources (-5)";
            case CL10.CL_OUT_OF_HOST_MEMORY -> "Out of host memory (-6)";
            case CL10.CL_PROFILING_INFO_NOT_AVAILABLE -> "Profiling info not available (-7)";
            case CL10.CL_MEM_COPY_OVERLAP -> "Memory copy overlap (-8)";
            case CL10.CL_IMAGE_FORMAT_MISMATCH -> "Image format mismatch (-9)";
            case CL10.CL_IMAGE_FORMAT_NOT_SUPPORTED -> "Image format not supported (-10)";
            case CL10.CL_BUILD_PROGRAM_FAILURE -> "Build program failure (-11)";
            case CL10.CL_MAP_FAILURE -> "Map failure (-12)";
            case -13 -> "Misaligned sub-buffer offset (-13)";
            case -14 -> "Exec status error for events in wait list (-14)";
            case -15 -> "Compile program failure (-15)";
            case -16 -> "Linker not available (-16)";
            case -17 -> "Link program failure (-17)";
            case -18 -> "Device partition failed (-18)";
            case -19 -> "Kernel arg info not available (-19)";
            case CL10.CL_INVALID_VALUE -> "Invalid value (-30)";
            case CL10.CL_INVALID_DEVICE_TYPE -> "Invalid device type (-31)";
            case CL10.CL_INVALID_PLATFORM -> "Invalid platform (-32)";
            case CL10.CL_INVALID_DEVICE -> "Invalid device (-33)";
            case CL10.CL_INVALID_CONTEXT -> "Invalid context (-34)";
            case CL10.CL_INVALID_QUEUE_PROPERTIES -> "Invalid queue properties (-35)";
            case CL10.CL_INVALID_COMMAND_QUEUE -> "Invalid command queue (-36)";
            case CL10.CL_INVALID_HOST_PTR -> "Invalid host pointer (-37)";
            case CL10.CL_INVALID_MEM_OBJECT -> "Invalid memory object (-38)";
            case CL10.CL_INVALID_IMAGE_FORMAT_DESCRIPTOR -> "Invalid image format descriptor (-39)";
            case CL10.CL_INVALID_IMAGE_SIZE -> "Invalid image size (-40)";
            case CL10.CL_INVALID_SAMPLER -> "Invalid sampler (-41)";
            case CL10.CL_INVALID_BINARY -> "Invalid binary (-42)";
            case CL10.CL_INVALID_BUILD_OPTIONS -> "Invalid build options (-43)";
            case CL10.CL_INVALID_PROGRAM -> "Invalid program (-44)";
            case CL10.CL_INVALID_PROGRAM_EXECUTABLE -> "Invalid program executable (-45)";
            case CL10.CL_INVALID_KERNEL_NAME -> "Invalid kernel name (-46)";
            case CL10.CL_INVALID_KERNEL_DEFINITION -> "Invalid kernel definition (-47)";
            case CL10.CL_INVALID_KERNEL -> "Invalid kernel (-48)";
            case CL10.CL_INVALID_ARG_INDEX -> "Invalid argument index (-49)";
            case CL10.CL_INVALID_ARG_VALUE -> "Invalid argument value (-50)";
            case CL10.CL_INVALID_ARG_SIZE -> "Invalid argument size (-51)";
            case CL10.CL_INVALID_KERNEL_ARGS -> "Invalid kernel arguments (-52)";
            case CL10.CL_INVALID_WORK_DIMENSION -> "Invalid work dimension (-53)";
            case CL10.CL_INVALID_WORK_GROUP_SIZE -> "Invalid work group size (-54)";
            case CL10.CL_INVALID_WORK_ITEM_SIZE -> "Invalid work item size (-55)";
            case CL10.CL_INVALID_GLOBAL_OFFSET -> "Invalid global offset (-56)";
            case CL10.CL_INVALID_EVENT_WAIT_LIST -> "Invalid event wait list (-57)";
            case CL10.CL_INVALID_EVENT -> "Invalid event (-58)";
            case CL10.CL_INVALID_OPERATION -> "Invalid operation (-59)";
            case -60 -> "Invalid GL object (-60)";
            case CL10.CL_INVALID_BUFFER_SIZE -> "Invalid buffer size (-61) - size may be 0 or exceed device limits";
            case -62 -> "Invalid mip level (-62)";
            case CL10.CL_INVALID_GLOBAL_WORK_SIZE -> "Invalid global work size (-63)";
            case -64 -> "Invalid property (-64)";
            case -65 -> "Invalid image descriptor (-65)";
            case -66 -> "Invalid compiler options (-66)";
            case -67 -> "Invalid linker options (-67)";
            case -68 -> "Invalid device partition count (-68)";
            case -69 -> "Invalid pipe size (-69)";
            case -70 -> "Invalid device queue (-70)";
            default -> "Unknown error (" + error + ")";
        };
    }

    // === Performance Metrics API ===

    /**
     * Get global buffer statistics
     */
    public static CLBufferStatistics getStatistics() {
        return GLOBAL_STATISTICS;
    }

    /**
     * Record a buffer reuse from pool (called by memory pool implementations)
     */
    public static void recordReuse() {
        GLOBAL_STATISTICS.recordReuse();
    }

    /**
     * Reset global statistics (for testing)
     */
    static void resetStatistics() {
        GLOBAL_STATISTICS.reset();
    }
}