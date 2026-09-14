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

import com.ishland.c2me.opts.dfc.common.worldgen.WorldgenRegionGeometry;

import java.util.BitSet;
import java.util.Objects;

/**
 * Collects exact-boundary F32 handoff values from individual interpolation
 * cells into one region-wide slot-major buffer.
 *
 * <p>The source traversal order is deliberately irrelevant. Every source sample
 * is scattered by its absolute block coordinate through
 * {@link MetalWorldgenRegionDomain}, so the finished spatial order is always the
 * proven final-noise order: X fastest, then Z, then Y. The output layout is
 * {@code slot * regionSampleCount + spatialIndex}.</p>
 *
 * <p>A cell can be preflighted before exact evaluation. Preflight validates the
 * complete cell identity and coordinates without mutating this buffer; only the
 * returned prepared submission can commit F32 values. This lets sampler-bound
 * exact DFC work run only after all cheap structural checks have passed.</p>
 *
 * <p>This is a staging/validation container only. It does not dispatch Metal
 * work, alter the worldgen read/write ABI, touch aquifers or event dependencies,
 * or write results back to chunks.</p>
 */
final class MetalWorldgenRegionBoundaryBuffer {

    private final MetalWorldgenRegionDomain domain;
    private final int slotCount;
    private final int regionSampleCount;
    private final int horizontalCellCount;
    private final int verticalCellCount;
    private final int expectedCellCount;
    private final float[] values;
    private final BitSet writtenCoordinates;
    private final BitSet submittedCells;
    private boolean finished;

    MetalWorldgenRegionBoundaryBuffer(MetalWorldgenRegionDomain domain, int slotCount) {
        this.domain = Objects.requireNonNull(domain, "domain");
        if (slotCount < 0) {
            throw new IllegalArgumentException("slotCount must not be negative");
        }
        this.slotCount = slotCount;
        this.regionSampleCount = domain.sampleCount();
        WorldgenRegionGeometry geometry = domain.geometry();
        this.horizontalCellCount = geometry.horizontalCellCount();
        this.verticalCellCount = geometry.verticalCellCount();
        this.expectedCellCount = Math.multiplyExact(
                Math.multiplyExact(this.horizontalCellCount, this.horizontalCellCount),
                this.verticalCellCount
        );
        this.values = new float[Math.multiplyExact(slotCount, this.regionSampleCount)];
        this.writtenCoordinates = new BitSet(this.regionSampleCount);
        this.submittedCells = new BitSet(this.expectedCellCount);
    }

    void submitCell(
            int cellX,
            int cellY,
            int cellZ,
            int[] x,
            int[] y,
            int[] z,
            int sourceSlotCount,
            float[] slotMajorValues
    ) {
        this.prepareCell(cellX, cellY, cellZ, x, y, z, sourceSlotCount).commit(slotMajorValues);
    }

    PreparedCell prepareCell(
            int cellX,
            int cellY,
            int cellZ,
            int[] x,
            int[] y,
            int[] z,
            int sourceSlotCount
    ) {
        this.requireMutable();
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");

        int cellIndex = this.cellIndex(cellX, cellY, cellZ);
        if (this.submittedCells.get(cellIndex)) {
            throw new IllegalStateException("Duplicate Metal worldgen boundary cell submission: ("
                    + cellX + "," + cellY + "," + cellZ + ")");
        }
        if (y.length != x.length || z.length != x.length) {
            throw new IllegalArgumentException("Metal worldgen boundary coordinate arrays must have equal lengths");
        }
        if (sourceSlotCount != this.slotCount) {
            throw new IllegalArgumentException("Metal worldgen boundary slot count mismatch: expected "
                    + this.slotCount + ", got " + sourceSlotCount);
        }

        int sourceSampleCount = x.length;
        int[] spatialIndices = new int[sourceSampleCount];
        BitSet batchCoordinates = new BitSet(this.regionSampleCount);
        for (int sample = 0; sample < sourceSampleCount; sample++) {
            int spatialIndex = this.spatialIndex(x[sample], y[sample], z[sample]);
            if (batchCoordinates.get(spatialIndex) || this.writtenCoordinates.get(spatialIndex)) {
                throw new IllegalStateException("Duplicate Metal worldgen boundary coordinate write: ("
                        + x[sample] + "," + y[sample] + "," + z[sample] + ")");
            }
            this.requireCoordinateBelongsToCell(cellX, cellY, cellZ, x[sample], y[sample], z[sample]);
            batchCoordinates.set(spatialIndex);
            spatialIndices[sample] = spatialIndex;
        }

        return new PreparedCell(
                cellX,
                cellY,
                cellZ,
                cellIndex,
                sourceSampleCount,
                spatialIndices,
                batchCoordinates
        );
    }

    float[] finish() {
        if (!this.finished) {
            int missingCell = this.submittedCells.nextClearBit(0);
            if (missingCell < this.expectedCellCount) {
                throw new IllegalStateException("Missing Metal worldgen boundary cell: "
                        + this.describeCell(missingCell));
            }

            int missingCoordinate = this.writtenCoordinates.nextClearBit(0);
            if (missingCoordinate < this.regionSampleCount) {
                throw new IllegalStateException("Metal worldgen boundary coverage hole at ("
                        + this.domain.blockXAt(missingCoordinate) + ","
                        + this.domain.blockYAt(missingCoordinate) + ","
                        + this.domain.blockZAt(missingCoordinate) + ")");
            }
            this.finished = true;
        }
        return this.values.clone();
    }

    private void requireMutable() {
        if (this.finished) {
            throw new IllegalStateException("Metal worldgen boundary buffer is already finished");
        }
    }

    private int cellIndex(int cellX, int cellY, int cellZ) {
        WorldgenRegionGeometry geometry = this.domain.geometry();
        long relX = (long) cellX - geometry.startCellX();
        long relY = (long) cellY - geometry.startCellY();
        long relZ = (long) cellZ - geometry.startCellZ();
        if (relX < 0 || relX >= this.horizontalCellCount
                || relZ < 0 || relZ >= this.horizontalCellCount
                || relY < 0 || relY >= this.verticalCellCount) {
            throw new IndexOutOfBoundsException("Metal worldgen boundary cell outside region: ("
                    + cellX + "," + cellY + "," + cellZ + ")");
        }
        return Math.addExact(
                Math.multiplyExact(
                        Math.addExact(
                                Math.multiplyExact((int) relY, this.horizontalCellCount),
                                (int) relZ
                        ),
                        this.horizontalCellCount
                ),
                (int) relX
        );
    }

    private int spatialIndex(int blockX, int blockY, int blockZ) {
        WorldgenRegionGeometry geometry = this.domain.geometry();
        long relX = (long) blockX - geometry.startBlockX();
        long relY = (long) blockY - geometry.minimumY();
        long relZ = (long) blockZ - geometry.startBlockZ();
        if (relX < 0 || relX >= this.domain.horizontalSize()
                || relZ < 0 || relZ >= this.domain.horizontalSize()
                || relY < 0 || relY >= this.domain.verticalSize()) {
            throw new IndexOutOfBoundsException("Metal worldgen boundary coordinate outside region: ("
                    + blockX + "," + blockY + "," + blockZ + ")");
        }
        return this.domain.linearIndex((int) relX, (int) relY, (int) relZ);
    }

    private void requireCoordinateBelongsToCell(
            int cellX,
            int cellY,
            int cellZ,
            int blockX,
            int blockY,
            int blockZ
    ) {
        WorldgenRegionGeometry geometry = this.domain.geometry();
        long relCellX = (long) cellX - geometry.startCellX();
        long relCellY = (long) cellY - geometry.startCellY();
        long relCellZ = (long) cellZ - geometry.startCellZ();

        long startX = (long) geometry.startBlockX()
                + relCellX * geometry.horizontalCellBlockCount();
        long startY = (long) geometry.minimumY()
                + relCellY * geometry.verticalCellBlockCount();
        long startZ = (long) geometry.startBlockZ()
                + relCellZ * geometry.horizontalCellBlockCount();
        long endX = startX + geometry.horizontalCellBlockCount();
        long endY = startY + geometry.verticalCellBlockCount();
        long endZ = startZ + geometry.horizontalCellBlockCount();

        if (blockX < startX || blockX >= endX
                || blockY < startY || blockY >= endY
                || blockZ < startZ || blockZ >= endZ) {
            throw new IllegalArgumentException("Metal worldgen boundary coordinate ("
                    + blockX + "," + blockY + "," + blockZ + ") does not belong to cell ("
                    + cellX + "," + cellY + "," + cellZ + ")");
        }
    }

    private String describeCell(int cellIndex) {
        int relX = cellIndex % this.horizontalCellCount;
        int yzIndex = cellIndex / this.horizontalCellCount;
        int relZ = yzIndex % this.horizontalCellCount;
        int relY = yzIndex / this.horizontalCellCount;
        WorldgenRegionGeometry geometry = this.domain.geometry();
        return "(" + Math.addExact(geometry.startCellX(), relX)
                + "," + Math.addExact(geometry.startCellY(), relY)
                + "," + Math.addExact(geometry.startCellZ(), relZ) + ")";
    }

    final class PreparedCell {

        private final int cellX;
        private final int cellY;
        private final int cellZ;
        private final int cellIndex;
        private final int sourceSampleCount;
        private final int[] spatialIndices;
        private final BitSet batchCoordinates;
        private boolean committed;

        private PreparedCell(
                int cellX,
                int cellY,
                int cellZ,
                int cellIndex,
                int sourceSampleCount,
                int[] spatialIndices,
                BitSet batchCoordinates
        ) {
            this.cellX = cellX;
            this.cellY = cellY;
            this.cellZ = cellZ;
            this.cellIndex = cellIndex;
            this.sourceSampleCount = sourceSampleCount;
            this.spatialIndices = spatialIndices;
            this.batchCoordinates = batchCoordinates;
        }

        void commit(float[] slotMajorValues) {
            MetalWorldgenRegionBoundaryBuffer.this.requireMutable();
            if (this.committed) {
                throw new IllegalStateException("Metal worldgen boundary prepared cell is already committed: ("
                        + this.cellX + "," + this.cellY + "," + this.cellZ + ")");
            }
            Objects.requireNonNull(slotMajorValues, "slotMajorValues");
            int expectedValueCount = Math.multiplyExact(
                    MetalWorldgenRegionBoundaryBuffer.this.slotCount,
                    this.sourceSampleCount
            );
            if (slotMajorValues.length != expectedValueCount) {
                throw new IllegalArgumentException("Metal worldgen boundary sample/value count mismatch: expected "
                        + expectedValueCount + " slot-major value(s) for " + this.sourceSampleCount
                        + " sample(s), got " + slotMajorValues.length);
            }
            if (MetalWorldgenRegionBoundaryBuffer.this.submittedCells.get(this.cellIndex)) {
                throw new IllegalStateException("Duplicate Metal worldgen boundary cell submission: ("
                        + this.cellX + "," + this.cellY + "," + this.cellZ + ")");
            }
            if (this.batchCoordinates.intersects(MetalWorldgenRegionBoundaryBuffer.this.writtenCoordinates)) {
                throw new IllegalStateException("Metal worldgen boundary prepared coordinates overlap an already submitted cell");
            }

            for (int slot = 0; slot < MetalWorldgenRegionBoundaryBuffer.this.slotCount; slot++) {
                int sourceBase = Math.multiplyExact(slot, this.sourceSampleCount);
                int targetBase = Math.multiplyExact(slot, MetalWorldgenRegionBoundaryBuffer.this.regionSampleCount);
                for (int sample = 0; sample < this.sourceSampleCount; sample++) {
                    MetalWorldgenRegionBoundaryBuffer.this.values[targetBase + this.spatialIndices[sample]] =
                            slotMajorValues[sourceBase + sample];
                }
            }
            MetalWorldgenRegionBoundaryBuffer.this.writtenCoordinates.or(this.batchCoordinates);
            MetalWorldgenRegionBoundaryBuffer.this.submittedCells.set(this.cellIndex);
            this.committed = true;
        }
    }
}
