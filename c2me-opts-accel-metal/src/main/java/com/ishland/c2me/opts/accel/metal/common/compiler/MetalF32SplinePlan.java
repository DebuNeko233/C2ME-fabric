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

import com.ishland.c2me.opts.dfc.common.ast.AstNode;

import java.util.List;
import java.util.Objects;

/**
 * Backend-internal plan for a mixed-precision spline workload.
 *
 * <p>The plan deliberately does not change the DFC AST type system. Every
 * {@link BoundaryInput} is evaluated by the existing exact/F64 path, explicitly
 * rounded to F32 on the host, and only then supplied to Metal. The planned tree
 * contains F32-only work that may later be emitted as MSL after differential
 * validation.</p>
 */
public record MetalF32SplinePlan(
        PlannedValue root,
        List<BoundaryInput> boundaryInputs
) {

    public MetalF32SplinePlan {
        Objects.requireNonNull(root, "root");
        boundaryInputs = List.copyOf(Objects.requireNonNull(boundaryInputs, "boundaryInputs"));
        for (int i = 0; i < boundaryInputs.size(); i++) {
            BoundaryInput input = Objects.requireNonNull(boundaryInputs.get(i), "boundaryInputs[" + i + "]");
            if (input.index() != i) {
                throw new IllegalArgumentException("Boundary input index mismatch at " + i + ": " + input.index());
            }
            if (input.producer().getReturnType() != AstNode.ReturnType.F64) {
                throw new IllegalArgumentException("Boundary producer must preserve F64 semantics: "
                        + input.producer().getClass().getName());
            }
        }
    }

    public sealed interface PlannedValue permits ConstantValue, SplineValue {
    }

    /** Raw F32 leaf preserved exactly from ConstantF32Node. */
    public record ConstantValue(int rawBits) implements PlannedValue {

        public float value() {
            return Float.intBitsToFloat(this.rawBits);
        }
    }

    /**
     * F32 spline node whose location/point value is supplied through an explicit
     * host-computed boundary slot.
     */
    public record SplineValue(
            int boundaryInputIndex,
            float[] locations,
            List<PlannedValue> values,
            float[] derivatives
    ) implements PlannedValue {

        public SplineValue {
            if (boundaryInputIndex < 0) {
                throw new IllegalArgumentException("boundaryInputIndex must be non-negative");
            }
            locations = Objects.requireNonNull(locations, "locations").clone();
            derivatives = Objects.requireNonNull(derivatives, "derivatives").clone();
            values = List.copyOf(Objects.requireNonNull(values, "values"));

            if (locations.length == 0) {
                throw new IllegalArgumentException("Spline must contain at least one location");
            }
            if (locations.length != derivatives.length || locations.length != values.size()) {
                throw new IllegalArgumentException("Spline locations, values and derivatives must have equal lengths");
            }
            for (int i = 0; i < values.size(); i++) {
                Objects.requireNonNull(values.get(i), "values[" + i + "]");
            }
        }

        @Override
        public float[] locations() {
            return this.locations.clone();
        }

        @Override
        public float[] derivatives() {
            return this.derivatives.clone();
        }
    }

    /**
     * Exact/F64 AST producer for one host-rounded F32 input slot. Repeated spline
     * nodes that reference the same AST object share a slot.
     */
    public record BoundaryInput(int index, AstNode producer) {

        public BoundaryInput {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
            Objects.requireNonNull(producer, "producer");
        }
    }
}
