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
import com.ishland.c2me.opts.dfc.common.ast.McToAst;
import com.ishland.c2me.opts.dfc.common.ast.opto.OptoPasses;
import com.ishland.c2me.opts.dfc.common.ast.spline.SplineNormalNode;
import com.ishland.c2me.opts.dfc.common.ducks.NoiseRouterExtension;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseRouter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds maximal Metal-compatible F32 spline islands in the same optimized
 * world-generation DensityFunction graphs consumed by the OpenCL backend.
 *
 * <p>The discovery pass deliberately uses {@link OptoPasses#optimizeOCL(AstNode)}
 * so cache elimination and the resulting mixed-precision AST shape match the
 * existing accelerator. It does not mutate or replace the NoiseRouter. The
 * resulting islands are only compilation candidates until a real batched
 * world-generation dispatch path opts into them.</p>
 *
 * <p>Once a compatible spline root is found, its children are not emitted as
 * separate islands because {@link MetalF32SplinePlanner} already includes all
 * compatible nested spline values in that plan. If an outer spline is not yet
 * supported, discovery may continue through its F32 value children, but never
 * descends into that spline's F64 location producer: doing so would create a
 * CPU -> GPU -> CPU dependency inside the exact boundary path.</p>
 */
public final class MetalWorldgenSplineDiscovery {

    private MetalWorldgenSplineDiscovery() {
    }

    public static List<DiscoveredSpline> discover(NoiseRouter originalNoiseRouter) {
        Objects.requireNonNull(originalNoiseRouter, "originalNoiseRouter");

        Map<String, DensityFunction> bindings = new LinkedHashMap<>();
        bindings.put("barrier", originalNoiseRouter.barrierNoise());
        bindings.put("fluid_level_floodedness", originalNoiseRouter.fluidLevelFloodednessNoise());
        bindings.put("fluid_level_spread", originalNoiseRouter.fluidLevelSpreadNoise());
        bindings.put("lava", originalNoiseRouter.lavaNoise());
        bindings.put("temperature", originalNoiseRouter.temperature());
        bindings.put("vegetation", originalNoiseRouter.vegetation());
        bindings.put("continents", originalNoiseRouter.continents());
        bindings.put("erosion", originalNoiseRouter.erosion());
        bindings.put("depth", originalNoiseRouter.depth());
        bindings.put("ridges", originalNoiseRouter.ridges());
        bindings.put("preliminary_surface_level", originalNoiseRouter.preliminarySurfaceLevel());
        bindings.put("final_density", originalNoiseRouter.finalDensity());
        bindings.put("vein_toggle", originalNoiseRouter.veinToggle());
        bindings.put("vein_ridged", originalNoiseRouter.veinRidged());
        bindings.put("vein_gap", originalNoiseRouter.veinGap());

        DensityFunction finalFinalDensity = ((NoiseRouterExtension) (Object) originalNoiseRouter).c2me$getFinalFinalDensity();
        if (finalFinalDensity != null) {
            bindings.put("final_final_density", finalFinalDensity);
        }

        List<DiscoveredSpline> result = new ArrayList<>();
        for (Map.Entry<String, DensityFunction> binding : bindings.entrySet()) {
            AstNode optimized = OptoPasses.optimizeOCL(McToAst.toAst(binding.getValue())).optimized();
            IdentityHashMap<AstNode, Boolean> visited = new IdentityHashMap<>();
            discover(binding.getKey(), optimized, "root", visited, result);
        }
        return List.copyOf(result);
    }

    private static void discover(
            String binding,
            AstNode node,
            String path,
            IdentityHashMap<AstNode, Boolean> visited,
            List<DiscoveredSpline> result
    ) {
        if (visited.put(node, Boolean.TRUE) != null) {
            return;
        }

        if (node instanceof SplineNormalNode spline) {
            try {
                MetalF32SplinePlan plan = MetalF32SplinePlanner.plan(spline);
                result.add(new DiscoveredSpline(binding, path, spline, plan));
                return;
            } catch (IllegalArgumentException ignored) {
                AstNode[] children = spline.getChildren();
                for (int i = 0; i < children.length; i++) {
                    AstNode child = children[i];
                    if (child.getReturnType() == AstNode.ReturnType.F32) {
                        discover(binding, child, path + "/" + i, visited, result);
                    }
                }
                return;
            }
        }

        AstNode[] children = node.getChildren();
        for (int i = 0; i < children.length; i++) {
            discover(binding, children[i], path + "/" + i, visited, result);
        }
    }

    public record DiscoveredSpline(
            String binding,
            String path,
            AstNode root,
            MetalF32SplinePlan plan
    ) {

        public DiscoveredSpline {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(plan, "plan");
            if (root.getReturnType() != AstNode.ReturnType.F32) {
                throw new IllegalArgumentException("Discovered Metal spline root must be F32");
            }
        }
    }
}
