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
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
import com.ishland.c2me.opts.dfc.common.gen.jvm.vif.EachApplierVanillaInterface;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.Objects;

/**
 * Independent sampler-bound reference evaluation for Metal spline boundary
 * inputs.
 *
 * <p>The roots are the original Minecraft density functions captured before DFC
 * AST optimization. Binding applies the real ChunkNoiseSampler visitor, while
 * evaluation uses the same explicit coordinate batch and F64 -> F32 rounding as
 * {@link MetalExactBoundaryBatch}. This path is validation-only and is never
 * used to produce Metal inputs.</p>
 */
final class MetalReferenceBoundaryBatch {

    private final DensityFunction[] roots;

    private MetalReferenceBoundaryBatch(DensityFunction[] roots) {
        this.roots = Objects.requireNonNull(roots, "roots").clone();
    }

    static MetalReferenceBoundaryBatch fromPlan(MetalF32SplinePlan plan) {
        Objects.requireNonNull(plan, "plan");
        DensityFunction[] roots = new DensityFunction[plan.boundaryInputs().size()];
        for (MetalF32SplinePlan.BoundaryInput input : plan.boundaryInputs()) {
            roots[input.index()] = input.referenceProducer();
        }
        return new MetalReferenceBoundaryBatch(roots);
    }

    MetalReferenceBoundaryBatch bind(DensityFunction.DensityFunctionVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        DensityFunction[] rebound = new DensityFunction[this.roots.length];
        for (int i = 0; i < this.roots.length; i++) {
            DensityFunction root = this.roots[i];
            rebound[i] = root != null ? root.apply(visitor) : null;
        }
        return new MetalReferenceBoundaryBatch(rebound);
    }

    boolean[] referenceMask() {
        boolean[] mask = new boolean[this.roots.length];
        for (int i = 0; i < this.roots.length; i++) {
            mask[i] = this.roots[i] != null;
        }
        return mask;
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
            throw new IllegalArgumentException("Metal reference boundary coordinate arrays must have equal lengths");
        }

        int sampleCount = x.length;
        int expectedOutputLength = Math.multiplyExact(this.roots.length, sampleCount);
        if (output.length != expectedOutputLength) {
            throw new IllegalArgumentException("Expected " + expectedOutputLength
                    + " slot-major Metal reference values, got " + output.length);
        }
        if (this.roots.length == 0 || sampleCount == 0) {
            return;
        }

        EachApplierVanillaInterface applier = new EachApplierVanillaInterface(x, y, z, type, cache);
        double[] exactValues = new double[sampleCount];
        for (int slot = 0; slot < this.roots.length; slot++) {
            DensityFunction root = this.roots[slot];
            if (root == null) {
                continue;
            }
            root.fill(exactValues, applier);
            int outputBase = slot * sampleCount;
            for (int sample = 0; sample < sampleCount; sample++) {
                output[outputBase + sample] = (float) exactValues[sample];
            }
        }
    }
}
