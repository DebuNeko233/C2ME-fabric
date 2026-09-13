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
import com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import com.ishland.c2me.opts.dfc.common.gen.jvm.vif.EachApplierVanillaInterface;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.Objects;

/**
 * Non-mutating view over one live ChunkNoiseSampler fast-cache wrapper.
 *
 * <p>DFC generated cache fields call {@link IFastCacheLike#c2me$withDelegate}
 * while a generated class is instantiated. The normal ChunkNoiseSampler cache
 * implementations satisfy that operation by replacing their own delegate in
 * place. That is correct when the generated DFC graph owns the wrapper, but it
 * is unsafe when Metal wants a second generated graph to share an already-live
 * sampler cache.</p>
 *
 * <p>This view separates those two kinds of state. Cache reads and writes are
 * forwarded to {@code backing}, so Metal observes the same interpolation/cache
 * state as the normal sampler. The generated delegate is owned only by the
 * view, and {@code c2me$withDelegate} returns another view instead of mutating
 * the backing wrapper. Normal world-generation delegate ownership is therefore
 * left untouched.</p>
 */
final class MetalFastCacheView implements IFastCacheLike {

    private final IFastCacheLike backing;
    private final DensityFunction delegate;

    private MetalFastCacheView(IFastCacheLike backing, DensityFunction delegate) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static DensityFunction wrap(DensityFunction densityFunction) {
        Objects.requireNonNull(densityFunction, "densityFunction");
        if (densityFunction instanceof MetalFastCacheView) {
            return densityFunction;
        }
        if (densityFunction instanceof IFastCacheLike fastCacheLike) {
            return new MetalFastCacheView(fastCacheLike, fastCacheLike.c2me$getDelegate());
        }
        return densityFunction;
    }

    @Override
    public double sample(NoisePos pos) {
        EvalType type = EvalType.from(pos);
        int x = pos.blockX();
        int y = pos.blockY();
        int z = pos.blockZ();
        double cached = this.backing.c2me$getCached(x, y, z, type);
        if (Double.doubleToRawLongBits(cached) != CACHE_MISS_NAN_BITS) {
            return cached;
        }

        double value = this.delegate.sample(pos);
        this.backing.c2me$cache(x, y, z, type, value);
        return value;
    }

    @Override
    public void fill(double[] densities, EachApplier applier) {
        Objects.requireNonNull(densities, "densities");
        Objects.requireNonNull(applier, "applier");

        if (applier instanceof EachApplierVanillaInterface vanillaInterface) {
            EvalType type = vanillaInterface.getType();
            if (this.backing.c2me$getCached(
                    densities,
                    vanillaInterface.getX(),
                    vanillaInterface.getY(),
                    vanillaInterface.getZ(),
                    type
            )) {
                return;
            }
            this.delegate.fill(densities, applier);
            this.backing.c2me$cache(
                    densities,
                    vanillaInterface.getX(),
                    vanillaInterface.getY(),
                    vanillaInterface.getZ(),
                    type
            );
            return;
        }

        this.delegate.fill(densities, applier);
    }

    @Override
    public DensityFunction applyInternal(DensityFunctionVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        DensityFunction applied = this.delegate.applyInternal(visitor);
        if (applied == this.delegate) {
            return this;
        }
        return new MetalFastCacheView(this.backing, applied);
    }

    @Override
    public DensityFunction apply(DensityFunctionVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        return visitor.apply(this.applyInternal(visitor));
    }

    @Override
    public double minValue() {
        return this.delegate.minValue();
    }

    @Override
    public double maxValue() {
        return this.delegate.maxValue();
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return this.delegate.getCodecHolder();
    }

    @Override
    public double c2me$getCached(int x, int y, int z, EvalType evalType) {
        return this.backing.c2me$getCached(x, y, z, evalType);
    }

    @Override
    public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        return this.backing.c2me$getCached(res, x, y, z, evalType);
    }

    @Override
    public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
        this.backing.c2me$cache(x, y, z, evalType, cached);
    }

    @Override
    public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        this.backing.c2me$cache(res, x, y, z, evalType);
    }

    @Override
    public boolean c2me$isActualCache() {
        return this.backing.c2me$isActualCache();
    }

    @Override
    public String c2me$describeCacheLike() {
        return "metal_view(" + this.backing.c2me$describeCacheLike() + ")";
    }

    @Override
    public DensityFunction c2me$getDelegate() {
        return this.delegate;
    }

    @Override
    public DensityFunction c2me$withDelegate(DensityFunction delegate) {
        return new MetalFastCacheView(this.backing, delegate);
    }
}
