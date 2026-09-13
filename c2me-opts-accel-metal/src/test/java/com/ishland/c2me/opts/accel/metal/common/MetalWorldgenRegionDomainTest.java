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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetalWorldgenRegionDomainTest {

    @Test
    void matchesOpenclFinalNoiseOrderingForFourChunkRegion() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                -8, 12,
                4,
                -64, 384,
                4, 8
        );
        MetalWorldgenRegionDomain domain = new MetalWorldgenRegionDomain(geometry);

        assertEquals(64, domain.horizontalSize());
        assertEquals(384, domain.verticalSize());
        assertEquals(1_572_864, domain.sampleCount());

        assertCoordinate(domain, 0, -128, -64, 192);
        assertCoordinate(domain, 1, -127, -64, 192);
        assertCoordinate(domain, 63, -65, -64, 192);
        assertCoordinate(domain, 64, -128, -64, 193);
        assertCoordinate(domain, 4_095, -65, -64, 255);
        assertCoordinate(domain, 4_096, -128, -63, 192);
        assertCoordinate(domain, domain.sampleCount() - 1, -65, 319, 255);

        assertEquals(0, domain.linearIndex(0, 0, 0));
        assertEquals(63, domain.linearIndex(63, 0, 0));
        assertEquals(64, domain.linearIndex(0, 0, 1));
        assertEquals(4_096, domain.linearIndex(0, 1, 0));
        assertEquals(domain.sampleCount() - 1, domain.linearIndex(63, 383, 63));
    }

    @Test
    void materializesExactCoordinateArraysForNegativeChunks() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                -1, -3,
                1,
                -8, 8,
                4, 8
        );
        MetalWorldgenRegionDomain domain = new MetalWorldgenRegionDomain(geometry);
        MetalWorldgenRegionDomain.Coordinates coordinates = domain.materializeCoordinates();

        assertEquals(2_048, coordinates.sampleCount());
        assertEquals(-16, coordinates.x()[0]);
        assertEquals(-8, coordinates.y()[0]);
        assertEquals(-48, coordinates.z()[0]);

        assertEquals(-1, coordinates.x()[15]);
        assertEquals(-8, coordinates.y()[15]);
        assertEquals(-48, coordinates.z()[15]);

        assertEquals(-16, coordinates.x()[16]);
        assertEquals(-8, coordinates.y()[16]);
        assertEquals(-47, coordinates.z()[16]);

        assertEquals(-16, coordinates.x()[256]);
        assertEquals(-7, coordinates.y()[256]);
        assertEquals(-48, coordinates.z()[256]);

        int last = coordinates.sampleCount() - 1;
        assertEquals(-1, coordinates.x()[last]);
        assertEquals(-1, coordinates.y()[last]);
        assertEquals(-33, coordinates.z()[last]);
    }

    @Test
    void rejectsCoordinatesOutsideRegion() {
        WorldgenRegionGeometry geometry = new WorldgenRegionGeometry(
                0, 0,
                1,
                0, 8,
                4, 8
        );
        MetalWorldgenRegionDomain domain = new MetalWorldgenRegionDomain(geometry);

        assertThrows(IndexOutOfBoundsException.class, () -> domain.linearIndex(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> domain.linearIndex(0, 8, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> domain.linearIndex(0, 0, 16));
        assertThrows(IndexOutOfBoundsException.class, () -> domain.blockXAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> domain.blockYAt(domain.sampleCount()));
    }

    private static void assertCoordinate(
            MetalWorldgenRegionDomain domain,
            int index,
            int expectedX,
            int expectedY,
            int expectedZ
    ) {
        assertEquals(expectedX, domain.blockXAt(index));
        assertEquals(expectedY, domain.blockYAt(index));
        assertEquals(expectedZ, domain.blockZAt(index));
    }
}
