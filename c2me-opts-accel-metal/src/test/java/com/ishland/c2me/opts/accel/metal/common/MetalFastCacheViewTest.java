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
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MetalFastCacheViewTest {

    @Test
    void withDelegateDoesNotMutateBackingDelegate() {
        DensityFunction original = new DummyFunction(1.25);
        DensityFunction replacement = new DummyFunction(-3.5);
        FakeFastCache backing = new FakeFastCache(original);

        IFastCacheLike view = (IFastCacheLike) MetalFastCacheView.wrap(backing);
        DensityFunction reboundFunction = view.c2me$withDelegate(replacement);
        IFastCacheLike rebound = (IFastCacheLike) reboundFunction;

        assertNotSame(backing, view);
        assertNotSame(view, rebound);
        assertSame(original, backing.c2me$getDelegate());
        assertSame(original, view.c2me$getDelegate());
        assertSame(replacement, rebound.c2me$getDelegate());
    }

    @Test
    void cacheStateIsForwardedToBacking() {
        DensityFunction original = new DummyFunction(2.0);
        FakeFastCache backing = new FakeFastCache(original);
        IFastCacheLike view = (IFastCacheLike) MetalFastCacheView.wrap(backing);

        view.c2me$cache(4, 8, 12, EvalType.NORMAL, 17.75);

        assertEquals(17.75, backing.c2me$getCached(4, 8, 12, EvalType.NORMAL));
        assertEquals(17.75, view.c2me$getCached(4, 8, 12, EvalType.NORMAL));
        assertSame(original, backing.c2me$getDelegate());
    }

    private static final class FakeFastCache implements IFastCacheLike {

        private DensityFunction delegate;
        private double cached = Double.longBitsToDouble(CACHE_MISS_NAN_BITS);

        private FakeFastCache(DensityFunction delegate) {
            this.delegate = delegate;
        }

        @Override
        public double sample(DensityFunction.NoisePos pos) {
            return this.delegate.sample(pos);
        }

        @Override
        public void fill(double[] densities, DensityFunction.EachApplier applier) {
            this.delegate.fill(densities, applier);
        }

        @Override
        public DensityFunction applyInternal(DensityFunction.DensityFunctionVisitor visitor) {
            return this;
        }

        @Override
        public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
            return visitor.apply(this);
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
            return this.cached;
        }

        @Override
        public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
            if (Double.doubleToRawLongBits(this.cached) == CACHE_MISS_NAN_BITS) {
                return false;
            }
            Arrays.fill(res, this.cached);
            return true;
        }

        @Override
        public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
            this.cached = cached;
        }

        @Override
        public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
            if (res.length != 0) {
                this.cached = res[0];
            }
        }

        @Override
        public boolean c2me$isActualCache() {
            return true;
        }

        @Override
        public String c2me$describeCacheLike() {
            return "test_cache";
        }

        @Override
        public DensityFunction c2me$getDelegate() {
            return this.delegate;
        }

        @Override
        public DensityFunction c2me$withDelegate(DensityFunction delegate) {
            this.delegate = delegate;
            return this;
        }
    }

    private record DummyFunction(double value) implements DensityFunction {

        @Override
        public double sample(DensityFunction.NoisePos pos) {
            return this.value;
        }

        @Override
        public void fill(double[] densities, DensityFunction.EachApplier applier) {
            Arrays.fill(densities, this.value);
        }

        @Override
        public DensityFunction applyInternal(DensityFunction.DensityFunctionVisitor visitor) {
            return this;
        }

        @Override
        public DensityFunction apply(DensityFunction.DensityFunctionVisitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return this.value;
        }

        @Override
        public double maxValue() {
            return this.value;
        }

        @Override
        public CodecHolder<? extends DensityFunction> getCodecHolder() {
            throw new UnsupportedOperationException();
        }
    }
}
