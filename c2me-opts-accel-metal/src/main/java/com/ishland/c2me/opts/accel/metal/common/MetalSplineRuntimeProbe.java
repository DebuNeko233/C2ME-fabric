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
import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.ast.binary.MulNode;
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantF32Node;
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import com.ishland.c2me.opts.dfc.common.ast.misc.CoordinateNode;
import com.ishland.c2me.opts.dfc.common.ast.spline.SplineNormalNode;

/**
 * Runtime differential probe for the first nontrivial hybrid F32 workload.
 *
 * <p>This is deliberately stronger than a shader-compilation probe: the F64
 * boundary producers are first compiled and batch-evaluated by the existing JVM
 * DFC generator, explicitly rounded to F32 on the host, and then the same nested
 * spline plan is evaluated by the Java/OpenCL-ordered reference and by Metal.
 * The final results are compared using raw binary32 bits. A mismatch disables
 * the backend before any real world-generation path can depend on it.</p>
 */
final class MetalSplineRuntimeProbe {

    private static final int SAMPLE_COUNT = 257;
    private static final double OUTER_SCALE = 0.1D;
    private static final double INNER_SCALE = 1.0D / 12.0D;

    private MetalSplineRuntimeProbe() {
    }

    static void validate(MetalBatchExecutor executor) {
        AstNode outerLocation = new MulNode(CoordinateNode.AXIS_X, new ConstantNode(OUTER_SCALE));
        AstNode innerLocation = new MulNode(CoordinateNode.AXIS_Z, new ConstantNode(INNER_SCALE));

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

        CoordinateBatch coordinates = createCoordinates();
        MetalExactBoundaryBatch exactBoundaries = MetalExactBoundaryBatch.compile(plan);
        if (exactBoundaries.boundaryCount() != 2) {
            throw new IllegalStateException("Metal exact boundary evaluator did not compile both DFC roots");
        }
        float[] boundaryValues = exactBoundaries.evaluateSlotMajor(
                coordinates.x(),
                coordinates.y(),
                coordinates.z(),
                EvalType.NORMAL
        );
        validateBoundaryValues(boundaryValues, coordinates);

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

    private static CoordinateBatch createCoordinates() {
        int[] x = new int[SAMPLE_COUNT];
        int[] y = new int[SAMPLE_COUNT];
        int[] z = new int[SAMPLE_COUNT];
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            // The non-binary scales intentionally force real F64 -> F32 rounding
            // while the coordinate ranges cross all spline knots and both
            // extrapolation regions.
            x[sample] = Math.floorMod(sample * 29, 161) - 80;
            y[sample] = Math.floorMod(sample * 13, 33) - 16;
            z[sample] = Math.floorMod(sample * 47, 145) - 72;
        }
        return new CoordinateBatch(x, y, z);
    }

    private static void validateBoundaryValues(float[] actual, CoordinateBatch coordinates) {
        if (actual.length != 2 * SAMPLE_COUNT) {
            throw new IllegalStateException("Metal exact boundary evaluator returned an unexpected result length");
        }
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            float expectedOuter = (float) (coordinates.x()[sample] * OUTER_SCALE);
            float expectedInner = (float) (coordinates.z()[sample] * INNER_SCALE);
            if (Float.floatToRawIntBits(actual[sample]) != Float.floatToRawIntBits(expectedOuter)) {
                throw new IllegalStateException("DFC outer boundary mismatch at sample " + sample);
            }
            if (Float.floatToRawIntBits(actual[SAMPLE_COUNT + sample]) != Float.floatToRawIntBits(expectedInner)) {
                throw new IllegalStateException("DFC inner boundary mismatch at sample " + sample);
            }
        }
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

    private record CoordinateBatch(int[] x, int[] y, int[] z) {
    }
}
