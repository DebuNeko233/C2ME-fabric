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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenRegionGeometryTest {

    @Test
    void matchesBatchedWorldgenDomains() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                -8, 12,
                4,
                -64, 384,
                4, 8
        );

        assertEquals(-128, geometry.startBlockX());
        assertEquals(192, geometry.startBlockZ());
        assertEquals(64, geometry.horizontalBlockSize());
        assertEquals(384, geometry.verticalBlockSize());

        assertEquals(-32, geometry.startCellX());
        assertEquals(-8, geometry.startCellY());
        assertEquals(48, geometry.startCellZ());
        assertEquals(16, geometry.horizontalCellCount());
        assertEquals(48, geometry.verticalCellCount());
        assertEquals(17, geometry.interpolatorSizeX());
        assertEquals(49, geometry.interpolatorSizeY());
        assertEquals(17, geometry.interpolatorSizeZ());

        assertEquals(-32, geometry.startBiomeX());
        assertEquals(48, geometry.startBiomeZ());
        assertEquals(16, geometry.biomeSizeX());
        assertEquals(16, geometry.biomeSizeZ());

        assertEquals(-48, geometry.surfaceHeightStartBiomeX());
        assertEquals(32, geometry.surfaceHeightStartBiomeZ());
        assertEquals(48, geometry.surfaceHeightBiomeSizeX());
        assertEquals(48, geometry.surfaceHeightBiomeSizeZ());

        assertEquals(-128, geometry.cache2dStartX());
        assertEquals(192, geometry.cache2dStartZ());
        assertEquals(64, geometry.cache2dSizeX());
        assertEquals(64, geometry.cache2dSizeZ());
    }

    @Test
    void supportsTwoChunkBatchesAndNegativeCoordinates() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                -1, -3,
                2,
                -64, 384,
                4, 8
        );

        assertEquals(-16, geometry.startBlockX());
        assertEquals(-48, geometry.startBlockZ());
        assertEquals(-4, geometry.startCellX());
        assertEquals(-12, geometry.startCellZ());
        assertEquals(32, geometry.horizontalBlockSize());
        assertEquals(8, geometry.horizontalCellCount());
        assertEquals(9, geometry.interpolatorSizeX());
        assertEquals(8, geometry.biomeSizeX());
        assertEquals(40, geometry.surfaceHeightBiomeSizeX());
    }

    @Test
    void alignmentMatchesPowerOfTwoOpenclBatchingForNegativeCoordinates() {
        assertTrue(WorldgenRegionGeometry.isAligned(0, 0, 4));
        assertTrue(WorldgenRegionGeometry.isAligned(8, -12, 4));
        assertTrue(WorldgenRegionGeometry.isAligned(-8, -4, 4));
        assertFalse(WorldgenRegionGeometry.isAligned(-7, -4, 4));
        assertFalse(WorldgenRegionGeometry.isAligned(-8, -3, 4));

        assertTrue(WorldgenRegionGeometry.isAligned(-2, 6, 2));
        assertFalse(WorldgenRegionGeometry.isAligned(-1, 6, 2));
        assertThrows(IllegalArgumentException.class, () ->
                WorldgenRegionGeometry.isAligned(0, 0, 0));
    }

    @Test
    void validatesConsumerGenerationShape() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                -8, 12,
                4,
                -64, 384,
                4, 8
        );

        assertDoesNotThrow(() -> geometry.requireGenerationShape(-64, 384, 4, 8));
        assertThrows(IllegalArgumentException.class, () ->
                geometry.requireGenerationShape(-63, 384, 4, 8));
        assertThrows(IllegalArgumentException.class, () ->
                geometry.requireGenerationShape(-64, 320, 4, 8));
        assertThrows(IllegalArgumentException.class, () ->
                geometry.requireGenerationShape(-64, 384, 2, 8));
        assertThrows(IllegalArgumentException.class, () ->
                geometry.requireGenerationShape(-64, 384, 4, 4));
    }

    @Test
    void rejectsInvalidGeometry() {
        assertThrows(IllegalArgumentException.class, () ->
                new WorldgenRegionGeometry(0, 0, 0, -64, 384, 4, 8));
        assertThrows(IllegalArgumentException.class, () ->
                new WorldgenRegionGeometry(0, 0, 1, -64, 384, 3, 8));
        assertThrows(IllegalArgumentException.class, () ->
                new WorldgenRegionGeometry(0, 0, 1, -64, -1, 4, 8));
    }
}
