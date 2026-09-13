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

import com.ishland.c2me.opts.accel.metal.common.compiler.GeneratedMetalSource;
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32SplineCompiler;
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32SplinePlan;
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32SplinePlanner;
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32SplineReference;
import com.ishland.c2me.opts.dfc.common.ast.AstNode;
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantF32Node;
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import com.ishland.c2me.opts.dfc.common.ast.spline.SplineNormalNode;

/**
 * Runtime differential probe for the first nontrivial hybrid F32 workload.
 *
 * <p>This is deliberately stronger than a shader-compilation probe: the same
 * nested spline plan is evaluated by the Java/OpenCL-ordered reference and by
 * Metal, then compared using raw binary32 bits. A mismatch disables the backend
 * before any real world-generation path can depend on it.</p>
 */
final class MetalSplineRuntimeProbe {

    private static final int SAMPLE_COUNT = 257;

    private MetalSplineRuntimeProbe() {
    }

    static void validate(MetalBatchExecutor executor) {
        ConstantNode outerLocation = new ConstantNode(0.0);
        ConstantNode innerLocation = new ConstantNode(1.0);

        SplineNormalNode inner = new SplineNormalNode(
                innerLocation,
                new float[]{-1.5F, 0.25F, 2.0F},
                new AstNode[]{
                        new ConstantF32Node(-2.25F),
                        new ConstantF32Node(0.625F),
                        new ConstantF32Node(3.5F),
                },
                new float[]{0.5F, -0.75F, 0.25F}
        );
        SplineNormalNode outer = new SplineNormalNode(
                outerLocation,
                new float[]{-2.0F, 0.5F, 3.0F},
                new AstNode[]{
                        new ConstantF32Node(-1.125F),
                        inner,
                        new ConstantF32Node(4.75F),
                },
                new float[]{-0.375F, 1.25F, -0.5F}
        );

        MetalF32SplinePlan plan = MetalF32SplinePlanner.plan(outer);
        if (plan.boundaryInputs().size() != 2
                || plan.boundaryInputs().get(0).producer() != outerLocation
                || plan.boundaryInputs().get(1).producer() != innerLocation) {
            throw new IllegalStateException("Metal hybrid spline planner did not preserve explicit F64 boundary slots");
        }

        float[] boundaryValues = createBoundaryValues();
        int[] expected = MetalF32SplineReference.evaluateBatchBits(plan, boundaryValues, SAMPLE_COUNT);
        GeneratedMetalSource generated = MetalF32SplineCompiler.compile(plan);

        int pipelinesBefore = executor.cachedPipelineCount();
        int buffersBefore = executor.allocatedBufferCount();
        int[] first = executor.executeF32SplineBits(generated, plan, boundaryValues, SAMPLE_COUNT);
        validateBits(expected, first);

        int pipelinesAfterFirst = executor.cachedPipelineCount();
        int buffersAfterFirst = executor.allocatedBufferCount();
        if (pipelinesAfterFirst != pipelinesBefore + 1) {
            throw new IllegalStateException("Metal spline probe did not compile exactly one pipeline");
        }
        if (buffersAfterFirst < buffersBefore || buffersAfterFirst > buffersBefore + 1) {
            throw new IllegalStateException("Metal spline probe allocated an unexpected number of shared buffers");
        }

        int[] second = executor.executeF32SplineBits(generated, plan, boundaryValues, SAMPLE_COUNT);
        validateBits(expected, second);
        if (executor.cachedPipelineCount() != pipelinesAfterFirst) {
            throw new IllegalStateException("Metal spline probe did not reuse its compiled pipeline");
        }
        if (executor.allocatedBufferCount() != buffersAfterFirst) {
            throw new IllegalStateException("Metal spline probe did not reuse its shared buffer bucket");
        }
    }

    private static float[] createBoundaryValues() {
        double[] outerValues = {
                -8.0,
                -2.000000238418579,
                -2.0,
                -1.9999998807907104,
                -0.75,
                0.0,
                0.4999999701976776,
                0.5,
                0.5000000596046448,
                1.75,
                2.999999761581421,
                3.0,
                3.000000238418579,
                8.0,
        };
        double[] innerValues = {
                -6.0,
                -1.5000001192092896,
                -1.5,
                -1.4999998807907104,
                -0.125,
                0.2499999850988388,
                0.25,
                0.2500000298023224,
                1.0 / 3.0,
                1.25,
                1.9999998807907104,
                2.0,
                2.000000238418579,
                Math.PI,
        };

        float[] values = new float[2 * SAMPLE_COUNT];
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            // These casts model the exact host-side F64 -> F32 boundary. Metal
            // never sees the double values themselves.
            values[sample] = (float) outerValues[sample % outerValues.length];
            values[SAMPLE_COUNT + sample] = (float) innerValues[(sample * 5 + 3) % innerValues.length];
        }
        return values;
    }

    private static void validateBits(int[] expected, int[] actual) {
        if (actual.length != expected.length) {
            throw new IllegalStateException("Metal spline probe returned an unexpected result length");
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                throw new IllegalStateException("Metal spline/reference mismatch at sample " + i
                        + ": expected 0x" + Integer.toHexString(expected[i])
                        + ", got 0x" + Integer.toHexString(actual[i]));
            }
        }
    }
}
