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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Process-lifetime cache for compiled Metal compute pipelines.
 *
 * <p>MSL compilation is intentionally kept off the hot world-generation path.
 * Equivalent generated source/entry-point pairs share one retained
 * MTLComputePipelineState.</p>
 */
final class MetalPipelineCache implements AutoCloseable {

    private final MetalNative nativeApi;
    private final long device;
    private final Map<Key, Long> pipelines = new HashMap<>();
    private boolean closed;

    MetalPipelineCache(MetalNative nativeApi, long device) {
        this.nativeApi = Objects.requireNonNull(nativeApi, "nativeApi");
        if (device == NULL) {
            throw new IllegalArgumentException("Metal device must not be NULL");
        }
        this.device = device;
    }

    synchronized long getOrCompile(GeneratedMetalSource generatedSource) {
        Objects.requireNonNull(generatedSource, "generatedSource");
        this.ensureOpen();

        Key key = new Key(generatedSource.source(), generatedSource.entryPoint());
        Long cached = this.pipelines.get(key);
        if (cached != null) {
            return cached;
        }

        long pipeline = this.nativeApi.compilePipeline(
                this.device,
                generatedSource.source(),
                generatedSource.entryPoint()
        );
        if (pipeline == NULL) {
            throw new IllegalStateException("Metal failed to compile generated compute pipeline '"
                    + generatedSource.entryPoint() + "'");
        }
        this.pipelines.put(key, pipeline);
        return pipeline;
    }

    synchronized int size() {
        return this.pipelines.size();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (long pipeline : this.pipelines.values()) {
            this.nativeApi.releaseObject(pipeline);
        }
        this.pipelines.clear();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Metal pipeline cache is closed");
        }
    }

    private record Key(String source, String entryPoint) {
    }

}
