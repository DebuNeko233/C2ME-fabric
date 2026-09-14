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

import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32SplinePlan;
import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen;
import com.ishland.c2me.opts.dfc.common.gen.jvm.CompiledEntry;
import com.ishland.c2me.opts.dfc.common.gen.jvm.SubCompiledDensityFunction;
import com.ishland.c2me.opts.dfc.common.gen.jvm.internalapi.ArgumentVisitor;
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
import com.ishland.c2me.opts.dfc.common.gen.jvm.vif.EachApplierVanillaInterface;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Compiles the exact/F64 boundary producers of a hybrid spline plan through
 * C2ME's existing JVM DFC generator and evaluates them in coordinate batches.
 *
 * <p>No AST interpretation happens here. Every boundary producer becomes a
 * normal generated DFC root and is executed through
 * {@link SubCompiledDensityFunction#fill(double[], DensityFunction.EachApplier)}.
 * {@link EachApplierVanillaInterface} exposes the already-batched x/y/z arrays,
 * so {@code AbstractCompiledDensityFunction.fill} reaches the generated
 * {@code IMultiMethod.evalMulti} path without per-sample {@code NoisePos}
 * construction. Results remain F64 until the explicit Java {@code (float)}
 * assignment into the slot-major output buffer.</p>
 *
 * <p>The compiled entry is retained so its DensityFunction arguments can be
 * rebound to a particular ChunkNoiseSampler. Runtime cache/interpolator wrappers
 * are never handed directly to the generated entry: each one is first wrapped
 * by {@link MetalFastCacheView}. The view shares cache state but owns the Metal
 * generated delegate independently, so generated {@code c2me$withDelegate}
 * calls cannot replace the delegate used by normal world generation.</p>
 *
 * <p>Binding additionally snapshots every live fast-cache delegate by identity
 * and verifies it after generated-entry construction. If a future DFC change
 * makes this supposedly non-mutating path replace a live world-generation
 * delegate, binding fails immediately before any boundary evaluation occurs.</p>
 *
 * <p>The generated roots intentionally have no blending fallback. Any real
 * world-generation caller must retain the existing no-blending eligibility gate
 * before evaluating these roots or dispatching the corresponding F32 island.</p>
 */
final class MetalExactBoundaryBatch {

    private final CompiledEntry compiledEntry;
    private final SubCompiledDensityFunction[] roots;

    private MetalExactBoundaryBatch(CompiledEntry compiledEntry) {
        this.compiledEntry = Objects.requireNonNull(compiledEntry, "compiledEntry");
        this.roots = compiledEntry.getRoots();
    }

    private MetalExactBoundaryBatch() {
        this.compiledEntry = null;
        this.roots = new SubCompiledDensityFunction[0];
    }

    static MetalExactBoundaryBatch compile(MetalF32SplinePlan plan) {
        Objects.requireNonNull(plan, "plan");
        int boundaryCount = plan.boundaryInputs().size();
        if (boundaryCount == 0) {
            return new MetalExactBoundaryBatch();
        }

        BytecodeGen.Context context = BytecodeGen.initContext();
        for (MetalF32SplinePlan.BoundaryInput input : plan.boundaryInputs()) {
            int rootIndex = context.registerRoot("metal_boundary_" + input.index(), input.producer());
            if (rootIndex != input.index()) {
                throw new IllegalStateException("DFC boundary root index mismatch: expected "
                        + input.index() + ", got " + rootIndex);
            }
        }

        CompiledEntry compiledEntry = BytecodeGen.finalizeCompilation(context);
        if (compiledEntry.getRootsUnsafe().length != boundaryCount) {
            throw new IllegalStateException("DFC boundary root count mismatch: expected "
                    + boundaryCount + ", got " + compiledEntry.getRootsUnsafe().length);
        }
        return new MetalExactBoundaryBatch(compiledEntry);
    }

    /**
     * Re-instantiates this template for one sampler visitor without mutating the
     * sampler's live IFastCacheLike delegates.
     */
    MetalExactBoundaryBatch bindWithCacheViews(DensityFunction.DensityFunctionVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        if (this.compiledEntry == null) {
            return this;
        }

        IdentityHashMap<IFastCacheLike, DensityFunction> liveDelegates = new IdentityHashMap<>();
        ArgumentVisitor argumentVisitor = next -> {
            if (next instanceof DensityFunction densityFunction) {
                DensityFunction rebound = densityFunction.apply(visitor);
                if (rebound instanceof IFastCacheLike fastCacheLike
                        && !(rebound instanceof MetalFastCacheView)
                        && !liveDelegates.containsKey(fastCacheLike)) {
                    liveDelegates.put(fastCacheLike, fastCacheLike.c2me$getDelegate());
                }
                return MetalFastCacheView.wrap(rebound);
            }
            if (next instanceof DensityFunction.Noise noise) {
                return visitor.apply(noise);
            }
            return next;
        };

        CompiledEntry rebound = this.compiledEntry.newInstance(this.compiledEntry.getArgs(), argumentVisitor);
        liveDelegates.forEach((fastCacheLike, expectedDelegate) -> {
            if (fastCacheLike.c2me$getDelegate() != expectedDelegate) {
                throw new IllegalStateException("Metal boundary binding mutated live DFC cache delegate: "
                        + fastCacheLike.c2me$describeCacheLike());
            }
        });
        return new MetalExactBoundaryBatch(rebound);
    }

    int boundaryCount() {
        return this.roots.length;
    }

    float[] evaluateSlotMajor(int[] x, int[] y, int[] z, EvalType type) {
        return this.evaluateSlotMajor(x, y, z, type, DfcObjectCache.Noop.INSTANCE);
    }

    float[] evaluateSlotMajor(
            int[] x,
            int[] y,
            int[] z,
            EvalType type,
            DfcObjectCache cache
    ) {
        Objects.requireNonNull(x, "x");
        float[] output = new float[Math.multiplyExact(this.roots.length, x.length)];
        this.fillSlotMajor(x, y, z, type, cache, output);
        return output;
    }

    void fillSlotMajor(
            int[] x,
            int[] y,
            int[] z,
            EvalType type,
            DfcObjectCache cache,
            float[] output
    ) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(z, "z");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(output, "output");

        if (y.length != x.length || z.length != x.length) {
            throw new IllegalArgumentException("Metal boundary coordinate arrays must have equal lengths");
        }

        int sampleCount = x.length;
        int expectedOutputLength = Math.multiplyExact(this.roots.length, sampleCount);
        if (output.length != expectedOutputLength) {
            throw new IllegalArgumentException("Expected " + expectedOutputLength
                    + " slot-major Metal boundary values, got " + output.length);
        }
        if (this.roots.length == 0 || sampleCount == 0) {
            return;
        }

        EachApplierVanillaInterface applier = new EachApplierVanillaInterface(x, y, z, type, cache);
        double[] exactValues = new double[sampleCount];
        for (int slot = 0; slot < this.roots.length; slot++) {
            this.roots[slot].fill(exactValues, applier);
            int outputBase = slot * sampleCount;
            for (int sample = 0; sample < sampleCount; sample++) {
                output[outputBase + sample] = (float) exactValues[sample];
            }
        }
    }
}
