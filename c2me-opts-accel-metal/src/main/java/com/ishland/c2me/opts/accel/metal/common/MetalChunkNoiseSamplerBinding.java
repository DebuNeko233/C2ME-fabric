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
import com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Safely binds world-scoped Metal spline templates to one constructed
 * ChunkNoiseSampler.
 *
 * <p>The sampler remains the authority for mapping original NoiseRouter wrapper
 * stubs to its actual runtime cache/interpolator objects. The exact boundary
 * compiler immediately places every returned IFastCacheLike behind a
 * {@link MetalFastCacheView}, so generated Metal-bound DFC entries can share the
 * cache state without replacing the delegate owned by normal world generation.</p>
 *
 * <p>Binding is intentionally forbidden once the interpolation loop has begun,
 * and blended samplers are rejected because Metal exact boundary roots have no
 * blending fallback. Binding also requires the sampler's actual-density cache
 * size to remain unchanged: all wrapper stubs referenced by a discovered
 * boundary must already have been resolved during normal sampler construction.</p>
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

        Map<DensityFunction, DensityFunction> actualDensityCache = accessor.getActualDensityFunctionCache();
        int actualDensityCacheSize = actualDensityCache.size();
        IdentityHashMap<IFastCacheLike, DensityFunction> liveDelegates = new IdentityHashMap<>();
        for (DensityFunction densityFunction : actualDensityCache.values()) {
            if (densityFunction instanceof IFastCacheLike fastCacheLike) {
                liveDelegates.put(fastCacheLike, fastCacheLike.c2me$getDelegate());
            }
        }

        DensityFunction.DensityFunctionVisitor visitor = accessor::invokeGetActualDensityFunction;
        MetalWorldgenSplinePrograms.BoundPrograms boundPrograms = programs.bindToSamplerVisitor(visitor);

        int reboundCacheSize = actualDensityCache.size();
        if (reboundCacheSize != actualDensityCacheSize) {
            throw new IllegalStateException("Metal boundary binding created new ChunkNoiseSampler density wrappers: "
                    + actualDensityCacheSize + " -> " + reboundCacheSize);
        }
        liveDelegates.forEach((fastCacheLike, expectedDelegate) -> {
            if (fastCacheLike.c2me$getDelegate() != expectedDelegate) {
                throw new IllegalStateException("Metal sampler binding mutated live DFC cache delegate: "
                        + fastCacheLike.c2me$describeCacheLike());
            }
        });
        return boundPrograms;
    }
}
