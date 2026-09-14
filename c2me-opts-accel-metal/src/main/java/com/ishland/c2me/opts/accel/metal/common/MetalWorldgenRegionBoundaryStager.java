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

import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
import com.ishland.c2me.opts.dfc.common.worldgen.WorldgenRegionGeometry;

import java.util.Objects;

/**
 * Stages sampler-bound exact/F64 boundary evaluation one interpolation cell at
 * a time into a region-wide {@link MetalWorldgenRegionBoundaryBuffer}.
 *
 * <p>The stager deliberately does not retain a {@link MetalExactBoundaryBatch}.
 * A caller supplies the batch that is valid for the current real interpolation
 * cell. The cell is structurally preflighted first, then exact values are
 * evaluated immediately into one reusable cell-local F32 scratch buffer and
 * committed into the region buffer. This keeps invalid coordinates from
 * reaching sampler-bound exact DFC work and keeps exact evaluation at the real
 * interpolation-cell boundary.</p>
 *
 * <p>This remains a preparation layer only. It does not dispatch Metal work,
 * alter the worldgen read/write ABI, touch aquifers or event dependencies, or
 * write results back to chunks.</p>
 */
final class MetalWorldgenRegionBoundaryStager {

    private final MetalWorldgenRegionBoundaryBuffer buffer;
    private final int slotCount;
    private final int expectedCellSampleCount;
    private final float[] cellValues;
    private boolean finished;

    MetalWorldgenRegionBoundaryStager(MetalWorldgenRegionDomain domain, int slotCount) {
        Objects.requireNonNull(domain, "domain");
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount must not be negative");
        }
        this.slotCount = slotCount;
        WorldgenRegionGeometry geometry = domain.geometry();
        this.expectedCellSampleCount = Math.multiplyExact(
                Math.multiplyExact(
                        geometry.horizontalCellBlockCount(),
                        geometry.horizontalCellBlockCount()
                ),
                geometry.verticalCellBlockCount()
        );
        this.cellValues = new float[Math.multiplyExact(slotCount, this.expectedCellSampleCount)];
        this.buffer = new MetalWorldgenRegionBoundaryBuffer(domain, slotCount);
    }

    void submitCell(
            int cellX,
            int cellY,
            int cellZ,
            int[] x,
            int[] y,
            int[] z,
            MetalExactBoundaryBatch boundaryBatch,
            EvalType type,
            DfcObjectCache cache
    ) {
        Objects.requireNonNull(boundaryBatch, "boundaryBatch");
        this.submitCell(
                cellX,
                cellY,
                cellZ,
                x,
                y,
                z,
                boundaryBatch.boundaryCount(),
                boundaryBatch::fillSlotMajor,
                type,
                cache
        );
    }

    void submitCell(
            int cellX,
            int cellY,
            int cellZ,
            int[] x,
            int[] y,
            int[] z,
            int sourceSlotCount,
            ExactBoundaryEvaluator evaluator,
            EvalType type,
            DfcObjectCache cache
    ) {
        this.requireMutable();
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cache, "cache");

        if (y.length != x.length || z.length != x.length) {
            throw new IllegalArgumentException("Metal worldgen boundary coordinate arrays must have equal lengths");
        }
        if (sourceSlotCount != this.slotCount) {
            throw new IllegalArgumentException("Metal worldgen boundary stager slot count mismatch: expected "
                    + this.slotCount + ", got " + sourceSlotCount);
        }
        if (x.length != this.expectedCellSampleCount) {
            throw new IllegalArgumentException("Metal worldgen boundary stager requires one complete interpolation cell: expected "
                    + this.expectedCellSampleCount + " sample(s), got " + x.length);
        }

        MetalWorldgenRegionBoundaryBuffer.PreparedCell prepared = this.buffer.prepareCell(
                cellX,
                cellY,
                cellZ,
                x,
                y,
                z,
                sourceSlotCount
        );
        evaluator.fill(x, y, z, type, cache, this.cellValues);
        prepared.commit(this.cellValues);
    }

    float[] finish() {
        float[] result = this.buffer.finish();
        this.finished = true;
        return result;
    }

    private void requireMutable() {
        if (this.finished) {
            throw new IllegalStateException("Metal worldgen boundary stager is already finished");
        }
    }

    @FunctionalInterface
    interface ExactBoundaryEvaluator {
        void fill(
                int[] x,
                int[] y,
                int[] z,
                EvalType type,
                DfcObjectCache cache,
                float[] output
        );
    }
}
