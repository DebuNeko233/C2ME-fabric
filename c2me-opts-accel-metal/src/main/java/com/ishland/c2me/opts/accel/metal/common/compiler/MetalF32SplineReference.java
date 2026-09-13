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

package com.ishland.c2me.opts.accel.metal.common.compiler;

import java.util.Objects;

/**
 * CPU reference implementation for {@link MetalF32SplinePlan}.
 *
 * <p>The operation ordering intentionally mirrors the existing OpenCL spline
 * emitter and helpers. It is not an optimized CPU implementation; it is the
 * oracle used by future Metal differential tests. Boundary inputs are already
 * host-rounded F32 values, preserving the exact F64 -> F32 cut point.</p>
 */
public final class MetalF32SplineReference {

    private MetalF32SplineReference() {
    }

    /** Evaluate one planned spline sample from one F32 value per boundary slot. */
    public static float evaluate(MetalF32SplinePlan plan, float[] boundaryValues) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(boundaryValues, "boundaryValues");
        if (boundaryValues.length != plan.boundaryInputs().size()) {
            throw new IllegalArgumentException("Expected " + plan.boundaryInputs().size()
                    + " boundary values, got " + boundaryValues.length);
        }
        return evaluateValue(plan.root(), boundaryValues, 0, 1);
    }

    /** Evaluate one sample and return its exact binary32 representation. */
    public static int evaluateBits(MetalF32SplinePlan plan, float[] boundaryValues) {
        return Float.floatToRawIntBits(evaluate(plan, boundaryValues));
    }

    /**
     * Evaluate a batch using slot-major boundary storage:
     * {@code boundaryValues[slot * sampleCount + sample]}.
     */
    public static int[] evaluateBatchBits(MetalF32SplinePlan plan, float[] boundaryValues, int sampleCount) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(boundaryValues, "boundaryValues");
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be non-negative");
        }
        int expected = Math.multiplyExact(plan.boundaryInputs().size(), sampleCount);
        if (boundaryValues.length != expected) {
            throw new IllegalArgumentException("Expected " + expected
                    + " slot-major boundary values, got " + boundaryValues.length);
        }

        int[] results = new int[sampleCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            results[sample] = Float.floatToRawIntBits(evaluateValue(plan.root(), boundaryValues, sample, sampleCount));
        }
        return results;
    }

    private static float evaluateValue(
            MetalF32SplinePlan.PlannedValue value,
            float[] boundaryValues,
            int sample,
            int sampleCount
    ) {
        if (value instanceof MetalF32SplinePlan.ConstantValue constant) {
            return constant.value();
        }
        if (value instanceof MetalF32SplinePlan.SplineValue spline) {
            return evaluateSpline(spline, boundaryValues, sample, sampleCount);
        }
        throw new IllegalStateException("Unknown planned spline value: " + value.getClass().getName());
    }

    private static float evaluateSpline(
            MetalF32SplinePlan.SplineValue spline,
            float[] boundaryValues,
            int sample,
            int sampleCount
    ) {
        float[] locations = spline.locations();
        float[] derivatives = spline.derivatives();
        int last = locations.length - 1;
        float point = boundaryValues[Math.addExact(Math.multiplyExact(spline.boundaryInputIndex(), sampleCount), sample)];

        if (locations.length == 1) {
            float value = evaluateValue(spline.values().getFirst(), boundaryValues, sample, sampleCount);
            return sampleOutsideRange(point, locations, value, derivatives, 0);
        }

        int rangeForLocation = findRangeForLocation(locations, point);
        if (rangeForLocation < 0) {
            float value = evaluateValue(spline.values().getFirst(), boundaryValues, sample, sampleCount);
            return sampleOutsideRange(point, locations, value, derivatives, 0);
        }
        if (rangeForLocation == last) {
            float value = evaluateValue(spline.values().get(last), boundaryValues, sample, sampleCount);
            return sampleOutsideRange(point, locations, value, derivatives, last);
        }

        float loc0 = locations[rangeForLocation];
        float loc1 = locations[rangeForLocation + 1];
        float locDist = loc1 - loc0;
        float k = (point - loc0) / locDist;

        float n = evaluateValue(spline.values().get(rangeForLocation), boundaryValues, sample, sampleCount);
        float o = evaluateValue(spline.values().get(rangeForLocation + 1), boundaryValues, sample, sampleCount);
        float onDist = o - n;
        float p = derivatives[rangeForLocation] * locDist - onDist;
        float q = -derivatives[rangeForLocation + 1] * locDist + onDist;

        return lerp(k, n, o) + k * (1.0F - k) * lerp(k, p, q);
    }

    /** Mirrors df_spline_findRangeForLocation from the OpenCL runtime. */
    private static int findRangeForLocation(float[] locations, float x) {
        int min = 0;
        int remaining = locations.length;
        while (remaining > 0) {
            int half = remaining / 2;
            int index = min + half;
            if (x < locations[index]) {
                remaining = half;
            } else {
                min = index + 1;
                remaining -= half + 1;
            }
        }
        return min - 1;
    }

    /** Mirrors df_spline_sampleOutsideRange from the OpenCL runtime. */
    private static float sampleOutsideRange(
            float point,
            float[] locations,
            float value,
            float[] derivatives,
            int index
    ) {
        float derivative = derivatives[index];
        return derivative == 0.0F ? value : value + derivative * (point - locations[index]);
    }

    /** Mirrors math_lerpf from the OpenCL runtime. */
    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }
}
