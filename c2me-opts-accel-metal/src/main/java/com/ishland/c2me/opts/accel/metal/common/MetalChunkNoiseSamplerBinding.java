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

import com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.Objects;

/**
 * Binds world-scoped Metal spline templates to the actual DensityFunction
 * replacements owned by one ChunkNoiseSampler.
 *
 * <p>This intentionally reuses ChunkNoiseSampler#getActualDensityFunction via a
 * base accessor instead of trying to capture constructor arguments with another
 * ModifyArg/WrapOperation mixin. The sampler therefore remains the authority for
 * turning NoiseRouter wrapper stubs into its DensityInterpolator / FlatCache /
 * Cache2D / CacheOnce / CellCache instances and can reuse its normal
 * actual-density-function cache.</p>
 *
 * <p>Binding must happen after the sampler has been constructed but before it
 * enters the interpolation loop. Metal exact boundary roots have no blending
 * fallback, so blended samplers are rejected here as an additional local safety
 * check; the future region scheduler must retain its existing region-wide gate
 * as well.</p>
 */
public final class MetalChunkNoiseSamplerBinding {

    private MetalChunkNoiseSamplerBinding() {
    }

    public static MetalWorldgenSplinePrograms.BoundPrograms bind(
            ChunkNoiseSampler sampler,
            MetalWorldgenSplinePrograms programs
    ) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(programs, "programs");

        IChunkNoiseSampler accessor = (IChunkNoiseSampler) (Object) sampler;
        if (accessor.getIsInInterpolationLoop()) {
            throw new IllegalStateException("Cannot bind Metal DFC boundaries during the ChunkNoiseSampler interpolation loop");
        }
        if (!accessor.getBlender().isEmpty()) {
            throw new IllegalStateException("Metal DFC boundary roots do not provide a blending fallback");
        }

        DensityFunction.DensityFunctionVisitor visitor = accessor::invokeGetActualDensityFunction;
        return programs.bind(visitor);
    }
}
