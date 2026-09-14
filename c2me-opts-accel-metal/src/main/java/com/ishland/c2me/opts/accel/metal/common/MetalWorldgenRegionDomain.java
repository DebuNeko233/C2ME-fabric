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

import java.util.Objects;

/**
 * Final-noise block domain for one backend-neutral world-generation region.
 *
 * <p>The linear order deliberately matches C2ME's OpenCL {@code df_noise_kernel}:
 * X is the fastest-changing axis, then Z, then Y. The corresponding OpenCL
 * index is {@code ((relY * sizeX) + relZ) * sizeZ + relX}; both horizontal
 * sizes are {@link WorldgenRegionGeometry#horizontalBlockSize()} for the square
 * 2x2 / 4x4 region contract.</p>
 *
 * <p>This class only describes/materializes coordinates. It performs no native
 * Metal work and does not imply that production chunk generation is routed to
 * Metal.</p>
 */
final class MetalWorldgenRegionDomain {

    private final WorldgenRegionGeometry geometry;
    private final int horizontalSize;
    private final int verticalSize;
    private final int sampleCount;

    MetalWorldgenRegionDomain(WorldgenRegionGeometry geometry) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.horizontalSize = geometry.horizontalBlockSize();
        this.verticalSize = geometry.verticalBlockSize();
        this.sampleCount = Math.multiplyExact(
                Math.multiplyExact(this.horizontalSize, this.horizontalSize),
                this.verticalSize
        );
    }

    WorldgenRegionGeometry geometry() {
        return this.geometry;
    }

    int horizontalSize() {
        return this.horizontalSize;
    }

    int verticalSize() {
        return this.verticalSize;
    }

    int sampleCount() {
        return this.sampleCount;
    }

    int linearIndex(int relX, int relY, int relZ) {
        this.checkRelativeCoordinate(relX, relY, relZ);
        return Math.addExact(
                Math.multiplyExact(
                        Math.addExact(Math.multiplyExact(relY, this.horizontalSize), relZ),
                        this.horizontalSize
                ),
                relX
        );
    }

    int blockXAt(int index) {
        this.checkIndex(index);
        int relX = index % this.horizontalSize;
        return Math.addExact(this.geometry.startBlockX(), relX);
    }

    int blockYAt(int index) {
        this.checkIndex(index);
        int relY = index / Math.multiplyExact(this.horizontalSize, this.horizontalSize);
        return Math.addExact(this.geometry.minimumY(), relY);
    }

    int blockZAt(int index) {
        this.checkIndex(index);
        int yzIndex = index / this.horizontalSize;
        int relZ = yzIndex % this.horizontalSize;
        return Math.addExact(this.geometry.startBlockZ(), relZ);
    }

    Coordinates materializeCoordinates() {
        int[] x = new int[this.sampleCount];
        int[] y = new int[this.sampleCount];
        int[] z = new int[this.sampleCount];

        int index = 0;
        for (int relY = 0; relY < this.verticalSize; relY++) {
            int blockY = Math.addExact(this.geometry.minimumY(), relY);
            for (int relZ = 0; relZ < this.horizontalSize; relZ++) {
                int blockZ = Math.addExact(this.geometry.startBlockZ(), relZ);
                for (int relX = 0; relX < this.horizontalSize; relX++) {
                    x[index] = Math.addExact(this.geometry.startBlockX(), relX);
                    y[index] = blockY;
                    z[index] = blockZ;
                    index++;
                }
            }
        }
        if (index != this.sampleCount) {
            throw new IllegalStateException("Metal worldgen region coordinate count mismatch");
        }
        return new Coordinates(x, y, z);
    }

    private void checkRelativeCoordinate(int relX, int relY, int relZ) {
        if (relX < 0 || relX >= this.horizontalSize
                || relZ < 0 || relZ >= this.horizontalSize
                || relY < 0 || relY >= this.verticalSize) {
            throw new IndexOutOfBoundsException("Metal worldgen relative coordinate outside region: ("
                    + relX + "," + relY + "," + relZ + ")");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= this.sampleCount) {
            throw new IndexOutOfBoundsException("Metal worldgen sample index outside region: " + index);
        }
    }

    record Coordinates(int[] x, int[] y, int[] z) {
        Coordinates {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
            Objects.requireNonNull(z, "z");
            if (y.length != x.length || z.length != x.length) {
                throw new IllegalArgumentException("Metal worldgen coordinate arrays must have equal lengths");
            }
        }

        int sampleCount() {
            return this.x.length;
        }
    }
}
