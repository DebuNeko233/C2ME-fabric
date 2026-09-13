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

/**
 * Programs used at an explicit exact/F64 -> F32 backend boundary.
 *
 * <p>C2ME's existing OpenCL spline path evaluates the location function with
 * F64 semantics and performs an explicit cast to float before the spline math.
 * Metal must preserve that conversion point instead of recursively compiling
 * the F64 subtree as float. The input buffer of programs generated here is
 * therefore already rounded to IEEE-754 binary32 by the exact host path.</p>
 */
public final class MetalF32BoundaryCompiler {

    public static final String IDENTITY_ENTRY_POINT = "c2me_f32_boundary_identity";

    private MetalF32BoundaryCompiler() {
    }

    /**
     * A bit-preserving boundary probe. This deliberately performs no arithmetic;
     * it proves that values rounded by the exact path arrive at Metal unchanged
     * before downstream F32-only work is enabled.
     */
    public static GeneratedMetalSource compileIdentity() {
        String source = """
                #include <metal_stdlib>
                using namespace metal;

                kernel void %s(device const float *input [[buffer(0)]],
                               device uint *output [[buffer(1)]],
                               uint gid [[thread_position_in_grid]]) {
                    output[gid] = as_type<uint>(input[gid]);
                }
                """.formatted(IDENTITY_ENTRY_POINT);
        return new GeneratedMetalSource(source, IDENTITY_ENTRY_POINT, AstNode.ReturnType.F32);
    }
}
