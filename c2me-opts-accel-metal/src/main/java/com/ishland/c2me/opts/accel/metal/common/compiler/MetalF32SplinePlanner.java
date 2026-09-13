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
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantF32Node;
import com.ishland.c2me.opts.dfc.common.ast.spline.SplineNormalNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an explicit mixed-precision execution plan without changing DFC's
 * return-type contract.
 *
 * <p>Only structure whose precision semantics are already understood is
 * accepted. In particular, every {@link SplineNormalNode#locationFunction}
 * remains an F64 boundary producer. The planner never recursively lowers that
 * subtree to Metal F32.</p>
 */
public final class MetalF32SplinePlanner {

    private MetalF32SplinePlanner() {
    }

    public static MetalF32SplinePlan plan(AstNode root) {
        Objects.requireNonNull(root, "root");
        if (root.getReturnType() != AstNode.ReturnType.F32) {
            throw unsupported("root", root, "spline plan root must be F32");
        }

        PlanningContext context = new PlanningContext();
        MetalF32SplinePlan.PlannedValue plannedRoot = context.planValue(root, "root");
        return new MetalF32SplinePlan(plannedRoot, context.boundaryInputs);
    }

    private static IllegalArgumentException unsupported(String path, AstNode node, String reason) {
        return new IllegalArgumentException("Unsupported Metal hybrid spline node at " + path + ": "
                + node.getClass().getName() + " (" + reason + ")");
    }

    private static final class PlanningContext {

        private final Map<AstNode, Integer> boundaryIndices = new IdentityHashMap<>();
        private final List<MetalF32SplinePlan.BoundaryInput> boundaryInputs = new ArrayList<>();

        private MetalF32SplinePlan.PlannedValue planValue(AstNode node, String path) {
            if (node instanceof ConstantF32Node constant) {
                return new MetalF32SplinePlan.ConstantValue(Float.floatToRawIntBits(constant.getValue()));
            }

            if (node instanceof SplineNormalNode spline) {
                return this.planSpline(spline, path);
            }

            throw unsupported(path, node, "only ConstantF32Node and SplineNormalNode are audited for planning");
        }

        private MetalF32SplinePlan.SplineValue planSpline(SplineNormalNode spline, String path) {
            if (spline.getReturnType() != AstNode.ReturnType.F32) {
                throw unsupported(path, spline, "SplineNormalNode must remain F32");
            }
            if (spline.locationFunction.getReturnType() != AstNode.ReturnType.F64) {
                throw unsupported(path + "/location", spline.locationFunction,
                        "existing OpenCL semantics evaluate spline locations as F64 before the explicit float cast");
            }
            if (spline.locations.length == 0
                    || spline.locations.length != spline.derivatives.length
                    || spline.locations.length != spline.values.length) {
                throw unsupported(path, spline, "invalid spline array lengths");
            }

            int boundaryIndex = this.boundaryIndex(spline.locationFunction);
            List<MetalF32SplinePlan.PlannedValue> values = new ArrayList<>(spline.values.length);
            for (int i = 0; i < spline.values.length; i++) {
                AstNode value = Objects.requireNonNull(spline.values[i], "spline.values[" + i + "]");
                if (value.getReturnType() != AstNode.ReturnType.F32) {
                    throw unsupported(path + "/value/" + i, value,
                            "existing spline value delegates are F32");
                }
                values.add(this.planValue(value, path + "/value/" + i));
            }

            return new MetalF32SplinePlan.SplineValue(
                    boundaryIndex,
                    spline.locations,
                    values,
                    spline.derivatives
            );
        }

        private int boundaryIndex(AstNode producer) {
            Integer existing = this.boundaryIndices.get(producer);
            if (existing != null) {
                return existing;
            }

            int index = this.boundaryInputs.size();
            this.boundaryIndices.put(producer, index);
            this.boundaryInputs.add(new MetalF32SplinePlan.BoundaryInput(index, producer));
            return index;
        }
    }
}
