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

import com.ishland.c2me.opts.accel.metal.common.compiler.GeneratedMetalSource;
import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import org.lwjgl.system.MemoryUtil;

import java.util.Objects;

/**
 * Minimal reusable 1D compute executor for generated F32 programs.
 *
 * <p>This intentionally exposes raw IEEE-754 bits for now. It gives the Metal
 * backend an exact validation surface before any world-generation call site is
 * redirected, while already exercising the same pipeline cache and shared
 * buffer pool that real batched workloads will use.</p>
 */
final class MetalBatchExecutor implements AutoCloseable {

    private final MetalNative nativeApi;
    private final long commandQueue;
    private final MetalPipelineCache pipelineCache;
    private final MetalSharedBufferPool bufferPool;
    private boolean closed;

    MetalBatchExecutor(MetalNative nativeApi, long device, long commandQueue) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        this.commandQueue = commandQueue;
        this.pipelineCache = new MetalPipelineCache(nativeApi, device);
        this.bufferPool = new MetalSharedBufferPool(nativeApi, device);
    }

    int[] executeF32Bits(GeneratedMetalSource generatedSource, int elementCount) {
        Objects.requireNonNull(generatedSource, "generatedSource");
        this.ensureOpen();
        if (generatedSource.returnType() != AstNode.ReturnType.F32) {
            throw new IllegalArgumentException("Metal F32 executor received " + generatedSource.returnType());
        }
        if (elementCount < 0) {
            throw new IllegalArgumentException("Metal batch size must be non-negative");
        }
        if (elementCount == 0) {
            return new int[0];
        }

        int outputBytes = Math.multiplyExact(elementCount, Integer.BYTES);
        long pipeline = this.pipelineCache.getOrCompile(generatedSource);
        MetalSharedBufferPool.SharedBuffer output = this.bufferPool.acquire(outputBytes);
        try {
            // Poison the visible range before dispatch so a stale/reused buffer
            // cannot accidentally satisfy validation if a thread was skipped.
            MemoryUtil.memSet(output.contents(), 0xA5, outputBytes);
            if (!this.nativeApi.execute1DBatch(this.commandQueue, pipeline, output.handle(), elementCount)) {
                throw new IllegalStateException("Metal command buffer did not complete successfully");
            }

            int[] result = new int[elementCount];
            for (int i = 0; i < elementCount; i++) {
                result[i] = MemoryUtil.memGetInt(output.contents() + (long) i * Integer.BYTES);
            }
            return result;
        } finally {
            this.bufferPool.release(output);
        }
    }

    int cachedPipelineCount() {
        return this.pipelineCache.size();
    }

    int allocatedBufferCount() {
        return this.bufferPool.allocatedBufferCount();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.bufferPool.close();
        this.pipelineCache.close();
    }

    private synchronized void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Metal batch executor is closed");
        }
    }

}
