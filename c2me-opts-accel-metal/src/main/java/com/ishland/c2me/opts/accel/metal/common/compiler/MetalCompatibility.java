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

import java.util.Objects;
import java.util.Set;

/**
 * Conservative capability gate for the Metal backend.
 *
 * <p>Metal Shading Language does not provide the F64 execution model used by
 * the existing C2ME/OpenCL density-function backend. A node is therefore only
 * accepted when DFC explicitly marks it as F32 and the exact node class has an
 * audited MSL emitter. Unsupported or F64 subtrees must stay on the existing
 * exact path.</p>
 */
public final class MetalCompatibility {

    private static final Set<Class<? extends AstNode>> SUPPORTED_F32_NODES = Set.of(
            ConstantF32Node.class
    );

    private MetalCompatibility() {
    }

    public static Result check(AstNode root) {
        Objects.requireNonNull(root, "root");
        return check(root, "root");
    }

    private static Result check(AstNode node, String path) {
        if (node.getReturnType() != AstNode.ReturnType.F32) {
            return Result.unsupported(path + " is " + node.getReturnType()
                    + " (" + node.getClass().getName() + ")");
        }

        if (!SUPPORTED_F32_NODES.contains(node.getClass())) {
            return Result.unsupported(path + " uses an unsupported F32 node: "
                    + node.getClass().getName());
        }

        AstNode[] children = node.getChildren();
        for (int i = 0; i < children.length; i++) {
            Result childResult = check(children[i], path + "/" + i);
            if (!childResult.supported()) {
                return childResult;
            }
        }

        return Result.SUPPORTED;
    }

    public record Result(boolean supported, String reason) {

        private static final Result SUPPORTED = new Result(true, "supported");

        private static Result unsupported(String reason) {
            return new Result(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
