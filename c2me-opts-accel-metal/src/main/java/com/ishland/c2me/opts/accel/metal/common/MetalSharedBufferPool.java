/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2021-2026 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.c2me.opts.accel.metal.common;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Small power-of-two pool of MTLStorageModeShared buffers.
 *
 * <p>Chunk generation dispatches many similarly sized batches. Reusing shared
 * buffers avoids repeatedly entering Objective-C allocation paths and gives the
 * future worldgen integration a stable CPU-visible staging allocation.</p>
 */
final class MetalSharedBufferPool implements AutoCloseable {

    private static final int MIN_CAPACITY = 4;
    private static final int MAX_BUFFERS_PER_BUCKET = 4;

    private final MetalNative nativeApi;
    private final long device;
    private final Map<Integer, ArrayDeque<SharedBuffer>> buffers = new HashMap<>();
    private boolean closed;
    private int allocatedBuffers;

    MetalSharedBufferPool(MetalNative nativeApi, long device) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        if (device == NULL) {
            throw new IllegalArgumentException("Metal device must not be NULL");
        }
        this.device = device;
    }

    synchronized SharedBuffer acquire(int minimumBytes) {
        this.ensureOpen();
        if (minimumBytes <= 0) {
            throw new IllegalArgumentException("Metal buffer size must be positive");
        }

        int capacity = bucketCapacity(minimumBytes);
        ArrayDeque<SharedBuffer> bucket = this.buffers.get(capacity);
        if (bucket != null) {
            SharedBuffer reused = bucket.pollFirst();
            if (reused != null) {
                return reused;
            }
        }

        long handle = this.nativeApi.newSharedBuffer(this.device, capacity);
        if (handle == NULL) {
            throw new IllegalStateException("Metal failed to allocate a " + capacity + " byte shared buffer");
        }
        long contents = this.nativeApi.getBufferContents(handle);
        if (contents == NULL) {
            this.nativeApi.releaseObject(handle);
            throw new IllegalStateException("Metal shared buffer does not expose CPU-visible contents");
        }
        this.allocatedBuffers++;
        return new SharedBuffer(handle, contents, capacity);
    }

    synchronized void release(SharedBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (this.closed) {
            this.nativeApi.releaseObject(buffer.handle());
            return;
        }

        ArrayDeque<SharedBuffer> bucket = this.buffers.computeIfAbsent(
                buffer.capacityBytes(),
                ignored -> new ArrayDeque<>()
        );
        if (bucket.size() >= MAX_BUFFERS_PER_BUCKET) {
            this.nativeApi.releaseObject(buffer.handle());
            return;
        }
        bucket.addFirst(buffer);
    }

    synchronized int allocatedBufferCount() {
        return this.allocatedBuffers;
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (ArrayDeque<SharedBuffer> bucket : this.buffers.values()) {
            SharedBuffer buffer;
            while ((buffer = bucket.pollFirst()) != null) {
                this.nativeApi.releaseObject(buffer.handle());
            }
        }
        this.buffers.clear();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Metal shared-buffer pool is closed");
        }
    }

    private static int bucketCapacity(int minimumBytes) {
        if (minimumBytes <= MIN_CAPACITY) {
            return MIN_CAPACITY;
        }
        if (minimumBytes > (1 << 30)) {
            return minimumBytes;
        }
        return Integer.highestOneBit(minimumBytes - 1) << 1;
    }

    record SharedBuffer(long handle, long contents, int capacityBytes) {
    }

}
