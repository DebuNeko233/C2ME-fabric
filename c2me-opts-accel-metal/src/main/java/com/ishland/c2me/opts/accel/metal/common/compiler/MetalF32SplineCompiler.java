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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Emits the F32 portion of a {@link MetalF32SplinePlan} as MSL.
 *
 * <p>F64 producers are intentionally absent from the generated shader. The
 * kernel receives their already-rounded F32 raw bits in a slot-major shared
 * buffer and evaluates only the audited spline portion. Output is written after
 * the boundary input region in the same buffer, allowing the already-validated
 * single-buffer Metal dispatch path to be reused.</p>
 */
public final class MetalF32SplineCompiler {

    public static final String ENTRY_POINT = "c2me_dfc_f32_spline_batch";

    private MetalF32SplineCompiler() {
    }

    public static GeneratedMetalSource compile(MetalF32SplinePlan plan) {
        Objects.requireNonNull(plan, "plan");
        Compiler compiler = new Compiler(plan);
        return compiler.compile();
    }

    private static final class Compiler {

        private final MetalF32SplinePlan plan;
        private final Map<MetalF32SplinePlan.PlannedValue, Integer> ids = new IdentityHashMap<>();
        private final List<MetalF32SplinePlan.PlannedValue> orderedValues = new ArrayList<>();

        private Compiler(MetalF32SplinePlan plan) {
            this.plan = plan;
            this.assignIds(plan.root());
        }

        private GeneratedMetalSource compile() {
            StringBuilder source = new StringBuilder();
            source.append("#include <metal_stdlib>\n")
                    .append("using namespace metal;\n\n")
                    .append("static inline float c2me_lerpf(float delta, float start, float end) {\n")
                    .append("    return start + delta * (end - start);\n")
                    .append("}\n\n");

            for (MetalF32SplinePlan.PlannedValue value : this.orderedValues) {
                if (value instanceof MetalF32SplinePlan.SplineValue spline) {
                    this.emitConstantArrays(source, this.idOf(spline), spline);
                }
            }

            // IDs are assigned parent-first. Emit definitions child-first so a
            // spline function never calls a function that has not been declared.
            for (int i = this.orderedValues.size() - 1; i >= 0; i--) {
                this.emitFunction(source, this.orderedValues.get(i));
            }

            int boundaryCount = this.plan.boundaryInputs().size();
            source.append("kernel void ").append(ENTRY_POINT)
                    .append("(device uint *io [[buffer(0)]],\n")
                    .append("             uint gid [[thread_position_in_grid]],\n")
                    .append("             uint sample_count [[threads_per_grid]]) {\n")
                    .append("    const float value = c2me_spline_value_").append(this.idOf(this.plan.root()))
                    .append("(io, gid, sample_count);\n")
                    .append("    io[").append(boundaryCount).append("u * sample_count + gid] = as_type<uint>(value);\n")
                    .append("}\n");

            return new GeneratedMetalSource(
                    source.toString(),
                    ENTRY_POINT,
                    AstNode.ReturnType.F32
            );
        }

        private void assignIds(MetalF32SplinePlan.PlannedValue value) {
            if (this.ids.containsKey(value)) {
                return;
            }
            int id = this.orderedValues.size();
            this.ids.put(value, id);
            this.orderedValues.add(value);
            if (value instanceof MetalF32SplinePlan.SplineValue spline) {
                for (MetalF32SplinePlan.PlannedValue child : spline.values()) {
                    this.assignIds(child);
                }
            }
        }

        private int idOf(MetalF32SplinePlan.PlannedValue value) {
            Integer id = this.ids.get(value);
            if (id == null) {
                throw new IllegalStateException("Unregistered planned spline value");
            }
            return id;
        }

        private void emitConstantArrays(StringBuilder source, int id, MetalF32SplinePlan.SplineValue spline) {
            float[] locations = spline.locations();
            float[] derivatives = spline.derivatives();

            source.append("constant uint c2me_spline_").append(id).append("_location_bits[")
                    .append(locations.length).append("] = {");
            appendFloatBits(source, locations);
            source.append("};\n");

            source.append("constant uint c2me_spline_").append(id).append("_derivative_bits[")
                    .append(derivatives.length).append("] = {");
            appendFloatBits(source, derivatives);
            source.append("};\n\n");
        }

        private void emitFunction(StringBuilder source, MetalF32SplinePlan.PlannedValue value) {
            int id = this.idOf(value);
            source.append("static inline float c2me_spline_value_").append(id)
                    .append("(device const uint *io, uint gid, uint sample_count) {\n");

            if (value instanceof MetalF32SplinePlan.ConstantValue constant) {
                source.append("    return ").append(floatFromBits(constant.rawBits())).append(";\n")
                        .append("}\n\n");
                return;
            }

            MetalF32SplinePlan.SplineValue spline = (MetalF32SplinePlan.SplineValue) value;
            int length = spline.values().size();
            int last = length - 1;
            String prefix = "c2me_spline_" + id;

            source.append("    const float point = as_type<float>(io[")
                    .append(spline.boundaryInputIndex()).append("u * sample_count + gid]);\n");

            if (length == 1) {
                source.append("    const float value = c2me_spline_value_")
                        .append(this.idOf(spline.values().getFirst()))
                        .append("(io, gid, sample_count);\n")
                        .append("    const float location = as_type<float>(").append(prefix).append("_location_bits[0]);\n")
                        .append("    const float derivative = as_type<float>(").append(prefix).append("_derivative_bits[0]);\n")
                        .append("    return derivative == 0.0f ? value : value + derivative * (point - location);\n")
                        .append("}\n\n");
                return;
            }

            source.append("    int min_index = 0;\n")
                    .append("    int remaining = ").append(length).append(";\n")
                    .append("    while (remaining > 0) {\n")
                    .append("        const int half = remaining / 2;\n")
                    .append("        const int index = min_index + half;\n")
                    .append("        const float location = as_type<float>(").append(prefix).append("_location_bits[index]);\n")
                    .append("        if (point < location) {\n")
                    .append("            remaining = half;\n")
                    .append("        } else {\n")
                    .append("            min_index = index + 1;\n")
                    .append("            remaining -= half + 1;\n")
                    .append("        }\n")
                    .append("    }\n")
                    .append("    const int range_for_location = min_index - 1;\n")
                    .append("    if (range_for_location < 0) {\n");
            this.emitOutsideRange(source, spline, id, 0, "        ");
            source.append("    }\n")
                    .append("    if (range_for_location == ").append(last).append(") {\n");
            this.emitOutsideRange(source, spline, id, last, "        ");
            source.append("    }\n")
                    .append("    const float loc0 = as_type<float>(").append(prefix).append("_location_bits[range_for_location]);\n")
                    .append("    const float loc1 = as_type<float>(").append(prefix).append("_location_bits[range_for_location + 1]);\n")
                    .append("    const float loc_dist = loc1 - loc0;\n")
                    .append("    const float k = (point - loc0) / loc_dist;\n")
                    .append("    float n;\n")
                    .append("    float o;\n")
                    .append("    switch (range_for_location) {\n");

            for (int i = 0; i < last; i++) {
                source.append("        case ").append(i).append(":\n")
                        .append("            n = c2me_spline_value_").append(this.idOf(spline.values().get(i)))
                        .append("(io, gid, sample_count);\n")
                        .append("            o = c2me_spline_value_").append(this.idOf(spline.values().get(i + 1)))
                        .append("(io, gid, sample_count);\n")
                        .append("            break;\n");
            }

            source.append("        default:\n")
                    .append("            n = o = as_type<float>(0x7FC00000u);\n")
                    .append("            break;\n")
                    .append("    }\n")
                    .append("    const float on_dist = o - n;\n")
                    .append("    const float derivative0 = as_type<float>(").append(prefix).append("_derivative_bits[range_for_location]);\n")
                    .append("    const float derivative1 = as_type<float>(").append(prefix).append("_derivative_bits[range_for_location + 1]);\n")
                    .append("    const float p = derivative0 * loc_dist - on_dist;\n")
                    .append("    const float q = -derivative1 * loc_dist + on_dist;\n")
                    .append("    return c2me_lerpf(k, n, o) + k * (1.0f - k) * c2me_lerpf(k, p, q);\n")
                    .append("}\n\n");
        }

        private void emitOutsideRange(
                StringBuilder source,
                MetalF32SplinePlan.SplineValue spline,
                int splineId,
                int valueIndex,
                String indent
        ) {
            source.append(indent).append("const float value = c2me_spline_value_")
                    .append(this.idOf(spline.values().get(valueIndex)))
                    .append("(io, gid, sample_count);\n")
                    .append(indent).append("const float location = as_type<float>(c2me_spline_")
                    .append(splineId).append("_location_bits[").append(valueIndex).append("]);\n")
                    .append(indent).append("const float derivative = as_type<float>(c2me_spline_")
                    .append(splineId).append("_derivative_bits[").append(valueIndex).append("]);\n")
                    .append(indent).append("return derivative == 0.0f ? value : value + derivative * (point - location);\n");
        }

        private static void appendFloatBits(StringBuilder source, float[] values) {
            for (int i = 0; i < values.length; i++) {
                if (i != 0) {
                    source.append(", ");
                }
                source.append(String.format(Locale.ROOT, "0x%08Xu", Float.floatToRawIntBits(values[i])));
            }
        }

        private static String floatFromBits(int bits) {
            return String.format(Locale.ROOT, "as_type<float>(0x%08Xu)", bits);
        }
    }
}
