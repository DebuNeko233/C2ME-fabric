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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalWorldgenRegionBoundaryStagerTest {

    private static final int SLOT_COUNT = 2;

    @Test
    void stagesCompleteCellsIntoRegionLayoutWithOneReusableCellScratch() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryStager stager = new MetalWorldgenRegionBoundaryStager(domain, SLOT_COUNT);
        AtomicInteger evaluations = new AtomicInteger();
        AtomicReference<float[]> scratch = new AtomicReference<>();
        DfcObjectCache cache = DfcObjectCache.Noop.INSTANCE;
        MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator evaluator = (x, y, z, type, passedCache, output) -> {
            evaluations.incrementAndGet();
            assertSame(EvalType.INTERPOLATION, type);
            assertSame(cache, passedCache);
            float[] first = scratch.get();
            if (first == null) {
                scratch.set(output);
            } else {
                assertSame(first, output);
            }
            fillValues(domain, x, y, z, output);
        };

        List<Cell> cells = cells(domain.geometry());
        Collections.reverse(cells);
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            Batch batch = batchForCell(domain, cell, (i & 1) == 0);
            stager.submitCell(
                    cell.x(), cell.y(), cell.z(),
                    batch.x(), batch.y(), batch.z(),
                    SLOT_COUNT,
                    evaluator,
                    EvalType.INTERPOLATION,
                    cache
            );
        }

        assertArrayEquals(expectedRegionValues(domain), stager.finish());
        assertEquals(cells.size(), evaluations.get());
    }

    @Test
    void rejectsPartialCellBeforeExactEvaluation() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryStager stager = new MetalWorldgenRegionBoundaryStager(domain, SLOT_COUNT);
        AtomicInteger evaluations = new AtomicInteger();
        MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator evaluator =
                (x, y, z, type, cache, output) -> evaluations.incrementAndGet();
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, false);
        int shortLength = batch.x().length - 1;

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> stager.submitCell(
                cell.x(), cell.y(), cell.z(),
                Arrays.copyOf(batch.x(), shortLength),
                Arrays.copyOf(batch.y(), shortLength),
                Arrays.copyOf(batch.z(), shortLength),
                SLOT_COUNT,
                evaluator,
                EvalType.INTERPOLATION,
                DfcObjectCache.Noop.INSTANCE
        ));

        assertTrue(exception.getMessage().contains("complete interpolation cell"));
        assertEquals(0, evaluations.get());
    }

    @Test
    void rejectsSlotMismatchBeforeExactEvaluation() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryStager stager = new MetalWorldgenRegionBoundaryStager(domain, SLOT_COUNT);
        AtomicInteger evaluations = new AtomicInteger();
        MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator evaluator =
                (x, y, z, type, cache, output) -> evaluations.incrementAndGet();
        Cell cell = cells(domain.geometry()).getFirst();
        Batch batch = batchForCell(domain, cell, false);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> stager.submitCell(
                cell.x(), cell.y(), cell.z(),
                batch.x(), batch.y(), batch.z(),
                SLOT_COUNT - 1,
                evaluator,
                EvalType.INTERPOLATION,
                DfcObjectCache.Noop.INSTANCE
        ));

        assertTrue(exception.getMessage().contains("slot count mismatch"));
        assertEquals(0, evaluations.get());
    }

    @Test
    void failedCoordinatePreflightDoesNotRunExactEvaluationOrConsumeCell() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryStager stager = new MetalWorldgenRegionBoundaryStager(domain, SLOT_COUNT);
        AtomicInteger evaluations = new AtomicInteger();
        List<Cell> cells = cells(domain.geometry());
        Cell firstCell = cells.getFirst();
        Batch invalid = batchForCell(domain, firstCell, false);
        invalid.x()[0] = Math.subtractExact(domain.geometry().startBlockX(), 1);

        assertThrows(IndexOutOfBoundsException.class, () -> stager.submitCell(
                firstCell.x(), firstCell.y(), firstCell.z(),
                invalid.x(), invalid.y(), invalid.z(),
                SLOT_COUNT,
                (x, y, z, type, cache, output) -> evaluations.incrementAndGet(),
                EvalType.INTERPOLATION,
                DfcObjectCache.Noop.INSTANCE
        ));
        assertEquals(0, evaluations.get());

        MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator evaluator =
                valueEvaluator(domain, evaluations);
        for (Cell cell : cells) {
            Batch batch = batchForCell(domain, cell, false);
            stager.submitCell(
                    cell.x(), cell.y(), cell.z(),
                    batch.x(), batch.y(), batch.z(),
                    SLOT_COUNT,
                    evaluator,
                    EvalType.INTERPOLATION,
                    DfcObjectCache.Noop.INSTANCE
            );
        }

        assertArrayEquals(expectedRegionValues(domain), stager.finish());
        assertEquals(cells.size(), evaluations.get());
    }

    @Test
    void successfulFinishPreventsFurtherExactEvaluation() {
        MetalWorldgenRegionDomain domain = createDomain();
        MetalWorldgenRegionBoundaryStager stager = new MetalWorldgenRegionBoundaryStager(domain, SLOT_COUNT);
        AtomicInteger evaluations = new AtomicInteger();
        MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator evaluator =
                valueEvaluator(domain, evaluations);
        List<Cell> cells = cells(domain.geometry());
        for (Cell cell : cells) {
            Batch batch = batchForCell(domain, cell, false);
            stager.submitCell(
                    cell.x(), cell.y(), cell.z(),
                    batch.x(), batch.y(), batch.z(),
                    SLOT_COUNT,
                    evaluator,
                    EvalType.INTERPOLATION,
                    DfcObjectCache.Noop.INSTANCE
            );
        }

        float[] first = stager.finish();
        float expectedFirst = first[0];
        first[0] = Float.NaN;
        assertEquals(expectedFirst, stager.finish()[0]);

        int evaluationCount = evaluations.get();
        Cell firstCell = cells.getFirst();
        Batch firstBatch = batchForCell(domain, firstCell, false);
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> stager.submitCell(
                firstCell.x(), firstCell.y(), firstCell.z(),
                firstBatch.x(), firstBatch.y(), firstBatch.z(),
                SLOT_COUNT,
                evaluator,
                EvalType.INTERPOLATION,
                DfcObjectCache.Noop.INSTANCE
        ));
        assertTrue(exception.getMessage().contains("already finished"));
        assertEquals(evaluationCount, evaluations.get());
    }

    private static MetalWorldgenRegionBoundaryStager.ExactBoundaryEvaluator valueEvaluator(
            MetalWorldgenRegionDomain domain,
            AtomicInteger evaluations
    ) {
        return (x, y, z, type, cache, output) -> {
            evaluations.incrementAndGet();
            fillValues(domain, x, y, z, output);
        };
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
            boolean reverseSourceOrder
    ) {
        WorldgenRegionGeometry geometry = domain.geometry();
        int horizontalCellSize = geometry.horizontalCellBlockCount();
        int verticalCellSize = geometry.verticalCellBlockCount();
        int sampleCount = Math.multiplyExact(
                Math.multiplyExact(horizontalCellSize, horizontalCellSize),
                verticalCellSize
        );
        int[] x = new int[sampleCount];
        int[] y = new int[sampleCount];
        int[] z = new int[sampleCount];

        int relCellX = Math.subtractExact(cell.x(), geometry.startCellX());
        int relCellY = Math.subtractExact(cell.y(), geometry.startCellY());
        int relCellZ = Math.subtractExact(cell.z(), geometry.startCellZ());
        int startX = Math.addExact(geometry.startBlockX(), Math.multiplyExact(relCellX, horizontalCellSize));
        int startY = Math.addExact(geometry.minimumY(), Math.multiplyExact(relCellY, verticalCellSize));
        int startZ = Math.addExact(geometry.startBlockZ(), Math.multiplyExact(relCellZ, horizontalCellSize));

        int ordinal = 0;
        for (int localY = verticalCellSize - 1; localY >= 0; localY--) {
            for (int localX = 0; localX < horizontalCellSize; localX++) {
                for (int localZ = 0; localZ < horizontalCellSize; localZ++) {
                    int target = reverseSourceOrder ? sampleCount - 1 - ordinal : ordinal;
                    x[target] = Math.addExact(startX, localX);
                    y[target] = Math.addExact(startY, localY);
                    z[target] = Math.addExact(startZ, localZ);
                    ordinal++;
                }
            }
        }
        assertEquals(sampleCount, ordinal);
        return new Batch(x, y, z);
    }

    private static void fillValues(
            MetalWorldgenRegionDomain domain,
            int[] x,
            int[] y,
            int[] z,
            float[] output
    ) {
        int sampleCount = x.length;
        assertEquals(Math.multiplyExact(SLOT_COUNT, sampleCount), output.length);
        WorldgenRegionGeometry geometry = domain.geometry();
        for (int sample = 0; sample < sampleCount; sample++) {
            int spatialIndex = domain.linearIndex(
                    Math.subtractExact(x[sample], geometry.startBlockX()),
                    Math.subtractExact(y[sample], geometry.minimumY()),
                    Math.subtractExact(z[sample], geometry.startBlockZ())
            );
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                output[Math.multiplyExact(slot, sampleCount) + sample] = value(slot, spatialIndex);
            }
        }
    }

    private static float[] expectedRegionValues(MetalWorldgenRegionDomain domain) {
        float[] expected = new float[Math.multiplyExact(SLOT_COUNT, domain.sampleCount())];
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int base = Math.multiplyExact(slot, domain.sampleCount());
            for (int spatialIndex = 0; spatialIndex < domain.sampleCount(); spatialIndex++) {
                expected[base + spatialIndex] = value(slot, spatialIndex);
            }
        }
        return expected;
    }

    private static float value(int slot, int spatialIndex) {
        return slot * 10_000.0f + spatialIndex + 0.5f;
    }

    private record Cell(int x, int y, int z) {
    }

    private record Batch(int[] x, int[] y, int[] z) {
    }
}
