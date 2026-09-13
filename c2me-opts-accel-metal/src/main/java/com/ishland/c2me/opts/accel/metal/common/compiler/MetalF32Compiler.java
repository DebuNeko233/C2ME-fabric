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

import java.util.Locale;
import java.util.Objects;

/**
 * First-stage MSL generator for density-function AST nodes that are proven to
 * be F32-safe by {@link MetalCompatibility}.
 *
 * <p>The generated kernel writes the raw IEEE-754 bits rather than converting
 * the result through an integer cast. This gives the runtime probe an exact
 * bit-for-bit validation point, including signed zero and unusual float bit
 * patterns.</p>
 */
public final class MetalF32Compiler {

    public static final String ENTRY_POINT = "c2me_dfc_f32_probe";

    private MetalF32Compiler() {
    }

    public static GeneratedMetalSource compile(AstNode root) {
        Objects.requireNonNull(root, "root");

        MetalCompatibility.Result compatibility = MetalCompatibility.check(root);
        if (!compatibility.supported()) {
            throw new IllegalArgumentException("Density-function AST is not Metal F32 compatible: "
                    + compatibility.reason());
        }

        String expression = emitExpression(root);
        String source = """
                #include <metal_stdlib>
                using namespace metal;

                kernel void %s(device uint *output [[buffer(0)]],
                               uint gid [[thread_position_in_grid]]) {
                    if (gid != 0u) {
                        return;
                    }
                    const float value = %s;
                    output[0] = as_type<uint>(value);
                }
                """.formatted(ENTRY_POINT, expression);

        return new GeneratedMetalSource(source, ENTRY_POINT, AstNode.ReturnType.F32);
    }

    private static String emitExpression(AstNode node) {
        if (node instanceof ConstantF32Node constant) {
            return floatLiteral(constant.getValue());
        }

        throw new IllegalStateException("Metal compatibility accepted a node without an emitter: "
                + node.getClass().getName());
    }

    private static String floatLiteral(float value) {
        // Construct the float from its raw bits so code generation is exact and
        // independent of decimal literal parsing/rounding in the MSL compiler.
        return String.format(
                Locale.ROOT,
                "as_type<float>(0x%08Xu)",
                Float.floatToRawIntBits(value)
        );
    }
}
