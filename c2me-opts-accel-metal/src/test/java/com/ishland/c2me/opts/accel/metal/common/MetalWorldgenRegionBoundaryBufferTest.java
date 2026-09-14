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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalWorldgenRegionBoundaryBufferTest {

    private static final int SLOT_COUNT = 2;

    @Test
    void scattersByCoordinateIndependentOfCellAndSampleTraversalOrder() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer forward = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        MetalWorldgenRegionBoundaryBuffer reversed = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);

        List<Cell> cells = cells(domain.geometry());
        for (Cell cell : cells) {
            submit(forward, cell, batchForCell(domain, cell, SLOT_COUNT, -1, false));
        }

        List<Cell> reverseCells = new ArrayList<>(cells);
        Collections.reverse(reverseCells);
        for (Cell cell : reverseCells) {
            submit(reversed, cell, batchForCell(domain, cell, SLOT_COUNT, -1, true));
        }

        float[] forwardValues = forward.finish();
        float[] reversedValues = reversed.finish();
        assertArrayEquals(expectedRegionValues(domain, SLOT_COUNT), forwardValues);
        assertArrayEquals(forwardValues, reversedValues);
    }

    @Test
    void rejectsDuplicateCellSubmission() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, SLOT_COUNT, -1, false);

        submit(buffer, cell, batch);
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> submit(buffer, cell, batch));
        assertTrue(exception.getMessage().contains("Duplicate Metal worldgen boundary cell"));
    }

    @Test
    void rejectsDuplicateCoordinateWrite() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, SLOT_COUNT, -1, false);

        batch.x()[1] = batch.x()[0];
        batch.y()[1] = batch.y()[0];
        batch.z()[1] = batch.z()[0];

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> submit(buffer, cell, batch));
        assertTrue(exception.getMessage().contains("Duplicate Metal worldgen boundary coordinate"));
    }

    @Test
    void rejectsCoordinateOutsideRegion() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, SLOT_COUNT, -1, false);
        batch.x()[0] = Math.subtractExact(domain.geometry().startBlockX(), 1);

        IndexOutOfBoundsException exception = assertThrows(IndexOutOfBoundsException.class, () -> submit(buffer, cell, batch));
        assertTrue(exception.getMessage().contains("outside region"));
    }

    @Test
    void rejectsSlotAndSampleShapeMismatchesWithoutConsumingCell() {
        MetalWorldgenRegionDomain domain = createDomain();
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, SLOT_COUNT, -1, false);

        MetalWorldgenRegionBoundaryBuffer slotMismatch = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        IllegalArgumentException slotException = assertThrows(IllegalArgumentException.class, () ->
                slotMismatch.submitCell(
                        cell.x(), cell.y(), cell.z(),
                        batch.x(), batch.y(), batch.z(),
                        SLOT_COUNT - 1, batch.values()
                ));
        assertTrue(slotException.getMessage().contains("slot count mismatch"));

        MetalWorldgenRegionBoundaryBuffer coordinateMismatch = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        int[] shortY = java.util.Arrays.copyOf(batch.y(), batch.y().length - 1);
        IllegalArgumentException coordinateException = assertThrows(IllegalArgumentException.class, () ->
                coordinateMismatch.submitCell(
                        cell.x(), cell.y(), cell.z(),
                        batch.x(), shortY, batch.z(),
                        SLOT_COUNT, batch.values()
                ));
        assertTrue(coordinateException.getMessage().contains("coordinate arrays"));

        MetalWorldgenRegionBoundaryBuffer valueMismatch = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        float[] shortValues = java.util.Arrays.copyOf(batch.values(), batch.values().length - 1);
        IllegalArgumentException valueException = assertThrows(IllegalArgumentException.class, () ->
                valueMismatch.submitCell(
                        cell.x(), cell.y(), cell.z(),
                        batch.x(), batch.y(), batch.z(),
                        SLOT_COUNT, shortValues
                ));
        assertTrue(valueException.getMessage().contains("sample/value count mismatch"));

        submit(valueMismatch, cell, batch);
    }

    @Test
    void finishRejectsMissingCell() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        List<Cell> cells = cells(domain.geometry());

        for (int i = 0; i < cells.size() - 1; i++) {
            Cell cell = cells.get(i);
            submit(buffer, cell, batchForCell(domain, cell, SLOT_COUNT, -1, false));
        }

        IllegalStateException exception = assertThrows(IllegalStateException.class, buffer::finish);
        assertTrue(exception.getMessage().contains("Missing Metal worldgen boundary cell"));
    }

    @Test
    void finishRejectsCoverageHoleAfterEveryCellWasSubmitted() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        List<Cell> cells = cells(domain.geometry());

        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            int omittedSample = i == 0 ? 0 : -1;
            submit(buffer, cell, batchForCell(domain, cell, SLOT_COUNT, omittedSample, false));
        }

        IllegalStateException exception = assertThrows(IllegalStateException.class, buffer::finish);
        assertTrue(exception.getMessage().contains("coverage hole"));
    }

    @Test
    void successfulFinishFreezesMutationAndReturnsDefensiveCopies() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryBuffer buffer = new MetalWorldgenRegionBoundaryBuffer(domain, SLOT_COUNT);
        List<Cell> cells = cells(domain.geometry());
        for (Cell cell : cells) {
            submit(buffer, cell, batchForCell(domain, cell, SLOT_COUNT, -1, false));
        }

        float[] first = buffer.finish();
        float expectedFirst = first[0];
        first[0] = Float.NaN;
        assertEquals(expectedFirst, buffer.finish()[0]);

        Cell firstCell = cells.getFirst();
        Batch firstBatch = batchForCell(domain, firstCell, SLOT_COUNT, -1, false);
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> submit(buffer, firstCell, firstBatch));
        assertTrue(exception.getMessage().contains("already finished"));
    }

    private static MetalWorldgenRegionDomain createDomain() {
        return new MetalWorldgenRegionDomain(new WorldgenRegionGeometry(
                -1, -2,
                1,
                -4, 4,
                8, 2
        ));
    }

    private static List<Cell> cells(WorldgenRegionGeometry geometry) {
        List<Cell> cells = new ArrayList<>();
        for (int relY = 0; relY < geometry.verticalCellCount(); relY++) {
            for (int relZ = 0; relZ < geometry.horizontalCellCount(); relZ++) {
                for (int relX = 0; relX < geometry.horizontalCellCount(); relX++) {
                    cells.add(new Cell(
                            Math.addExact(geometry.startCellX(), relX),
                            Math.addExact(geometry.startCellY(), relY),
                            Math.addExact(geometry.startCellZ(), relZ)
                    ));
                }
            }
        }
        return cells;
    }

    private static Batch batchForCell(
            MetalWorldgenRegionDomain domain,
            Cell cell,
            int slotCount,
            int omittedSample,
            boolean reverseSourceOrder
    ) {
        WorldgenRegionGeometry geometry = domain.geometry();
        int horizontalCellSize = geometry.horizontalCellBlockCount();
        int verticalCellSize = geometry.verticalCellBlockCount();
        int fullSampleCount = Math.multiplyExact(
                Math.multiplyExact(horizontalCellSize, horizontalCellSize),
                verticalCellSize
        );
        if (omittedSample < -1 || omittedSample >= fullSampleCount) {
            throw new IllegalArgumentException("invalid omitted sample index");
        }
        int sampleCount = omittedSample >= 0 ? fullSampleCount - 1 : fullSampleCount;
        int[] x = new int[sampleCount];
        int[] y = new int[sampleCount];
        int[] z = new int[sampleCount];
        float[] values = new float[Math.multiplyExact(slotCount, sampleCount)];

        int relCellX = Math.subtractExact(cell.x(), geometry.startCellX());
        int relCellY = Math.subtractExact(cell.y(), geometry.startCellY());
        int relCellZ = Math.subtractExact(cell.z(), geometry.startCellZ());
        int startX = Math.addExact(geometry.startBlockX(), Math.multiplyExact(relCellX, horizontalCellSize));
        int startY = Math.addExact(geometry.minimumY(), Math.multiplyExact(relCellY, verticalCellSize));
        int startZ = Math.addExact(geometry.startBlockZ(), Math.multiplyExact(relCellZ, horizontalCellSize));

        int ordinal = 0;
        int kept = 0;
        for (int localY = verticalCellSize - 1; localY >= 0; localY--) {
            for (int localX = 0; localX < horizontalCellSize; localX++) {
                for (int localZ = 0; localZ < horizontalCellSize; localZ++) {
                    if (ordinal++ == omittedSample) {
                        continue;
                    }
                    int target = reverseSourceOrder ? sampleCount - 1 - kept : kept;
                    int blockX = Math.addExact(startX, localX);
                    int blockY = Math.addExact(startY, localY);
                    int blockZ = Math.addExact(startZ, localZ);
                    x[target] = blockX;
                    y[target] = blockY;
                    z[target] = blockZ;
                    int spatialIndex = domain.linearIndex(
                            Math.subtractExact(blockX, geometry.startBlockX()),
                            Math.subtractExact(blockY, geometry.minimumY()),
                            Math.subtractExact(blockZ, geometry.startBlockZ())
                    );
                    for (int slot = 0; slot < slotCount; slot++) {
                        values[Math.multiplyExact(slot, sampleCount) + target] = value(slot, spatialIndex);
                    }
                    kept++;
                }
            }
        }
        assertEquals(sampleCount, kept);
        return new Batch(x, y, z, values);
    }

    private static float[] expectedRegionValues(MetalWorldgenRegionDomain domain, int slotCount) {
        float[] expected = new float[Math.multiplyExact(slotCount, domain.sampleCount())];
        for (int slot = 0; slot < slotCount; slot++) {
            int base = Math.multiplyExact(slot, domain.sampleCount());
            for (int spatialIndex = 0; spatialIndex < domain.sampleCount(); spatialIndex++) {
                expected[base + spatialIndex] = value(slot, spatialIndex);
            }
        }
        return expected;
    }

    private static float value(int slot, int spatialIndex) {
        return slot * 10_000.0f + spatialIndex + 0.25f;
    }

    private static void submit(MetalWorldgenRegionBoundaryBuffer buffer, Cell cell, Batch batch) {
        buffer.submitCell(
                cell.x(), cell.y(), cell.z(),
                batch.x(), batch.y(), batch.z(),
                SLOT_COUNT, batch.values()
        );
    }

    private record Cell(int x, int y, int z) {
    }

    private record Batch(int[] x, int[] y, int[] z, float[] values) {
    }
}
