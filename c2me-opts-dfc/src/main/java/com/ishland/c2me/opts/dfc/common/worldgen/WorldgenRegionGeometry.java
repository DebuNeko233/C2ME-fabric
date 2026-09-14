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

package com.ishland.c2me.opts.dfc.common.worldgen;

/**
 * Backend-neutral geometry for one rectangular world-generation chunk batch.
 *
 * <p>The derived values intentionally mirror the geometry currently consumed by
 * the OpenCL batched biome/noise path. Keeping them independent of OpenCL and
 * Metal lets both accelerators share exactly the same chunk, cell, interpolation,
 * cache2d, and surface-height-cache domains.</p>
 */
public record WorldgenRegionGeometry(
        int startChunkX,
        int startChunkZ,
        int chunkCount,
        int minimumY,
        int height,
        int horizontalCellBlockCount,
        int verticalCellBlockCount
) {

    public WorldgenRegionGeometry {
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("chunkCount must be positive");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height must not be negative");
        }
        if (horizontalCellBlockCount <= 0) {
            throw new IllegalArgumentException("horizontalCellBlockCount must be positive");
        }
        if (verticalCellBlockCount <= 0) {
            throw new IllegalArgumentException("verticalCellBlockCount must be positive");
        }
        if (horizontalBlockSize(chunkCount) % horizontalCellBlockCount != 0) {
            throw new IllegalArgumentException("region width must be divisible by horizontalCellBlockCount");
        }
    }

    public static boolean isAligned(int chunkX, int chunkZ, int chunkCount) {
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("chunkCount must be positive");
        }
        return Math.floorMod(chunkX, chunkCount) == 0 && Math.floorMod(chunkZ, chunkCount) == 0;
    }

    /**
     * Require this descriptor to describe the exact generation shape expected by
     * a backend consumer. This keeps a scheduler-created region from being reused
     * accidentally with another world's or generator's cell geometry.
     */
    public void requireGenerationShape(
            int minimumY,
            int height,
            int horizontalCellBlockCount,
            int verticalCellBlockCount
    ) {
        if (this.minimumY != minimumY
                || this.height != height
                || this.horizontalCellBlockCount != horizontalCellBlockCount
                || this.verticalCellBlockCount != verticalCellBlockCount) {
            throw new IllegalArgumentException(
                    "Worldgen region generation shape mismatch: descriptor=[minimumY=" + this.minimumY
                            + ", height=" + this.height
                            + ", horizontalCellBlockCount=" + this.horizontalCellBlockCount
                            + ", verticalCellBlockCount=" + this.verticalCellBlockCount
                            + "], requested=[minimumY=" + minimumY
                            + ", height=" + height
                            + ", horizontalCellBlockCount=" + horizontalCellBlockCount
                            + ", verticalCellBlockCount=" + verticalCellBlockCount + "]"
            );
        }
    }

    private static int horizontalBlockSize(int chunkCount) {
        return Math.multiplyExact(chunkCount, 16);
    }

    public int startBlockX() {
        return Math.multiplyExact(startChunkX, 16);
    }

    public int startBlockZ() {
        return Math.multiplyExact(startChunkZ, 16);
    }

    public int horizontalBlockSize() {
        return horizontalBlockSize(chunkCount);
    }

    public int verticalBlockSize() {
        return verticalCellCount() * verticalCellBlockCount;
    }

    public int horizontalCellCount() {
        return horizontalBlockSize() / horizontalCellBlockCount;
    }

    public int verticalCellCount() {
        return Math.floorDiv(height, verticalCellBlockCount);
    }

    public int startCellX() {
        return Math.floorDiv(startBlockX(), horizontalCellBlockCount);
    }

    public int startCellY() {
        return Math.floorDiv(minimumY, verticalCellBlockCount);
    }

    public int startCellZ() {
        return Math.floorDiv(startBlockZ(), horizontalCellBlockCount);
    }

    public int interpolatorSizeX() {
        return horizontalCellCount() + 1;
    }

    public int interpolatorSizeY() {
        return verticalCellCount() + 1;
    }

    public int interpolatorSizeZ() {
        return horizontalCellCount() + 1;
    }

    public int startBiomeX() {
        return Math.multiplyExact(startChunkX, 4);
    }

    public int startBiomeZ() {
        return Math.multiplyExact(startChunkZ, 4);
    }

    public int biomeSizeX() {
        return Math.multiplyExact(chunkCount, 4);
    }

    public int biomeSizeZ() {
        return Math.multiplyExact(chunkCount, 4);
    }

    /** Four chunks of padding on each side, matching Stage1 surface-height caching. */
    public int surfaceHeightStartBiomeX() {
        return Math.multiplyExact(Math.subtractExact(startChunkX, 4), 4);
    }

    /** Four chunks of padding on each side, matching Stage1 surface-height caching. */
    public int surfaceHeightStartBiomeZ() {
        return Math.multiplyExact(Math.subtractExact(startChunkZ, 4), 4);
    }

    public int surfaceHeightBiomeSizeX() {
        return Math.addExact(32, biomeSizeX());
    }

    public int surfaceHeightBiomeSizeZ() {
        return Math.addExact(32, biomeSizeZ());
    }

    public int cache2dStartX() {
        return startBlockX();
    }

    public int cache2dStartZ() {
        return startBlockZ();
    }

    public int cache2dSizeX() {
        return horizontalBlockSize();
    }

    public int cache2dSizeZ() {
        return horizontalBlockSize();
    }
}
