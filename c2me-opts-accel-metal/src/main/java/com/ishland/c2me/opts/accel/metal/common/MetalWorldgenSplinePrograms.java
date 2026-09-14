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
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalWorldgenSplineDiscovery;
import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * World-lifetime compiled representation of the currently supported Metal F32
 * spline islands.
 *
 * <p>The original NoiseRouter is scanned once when a world is initialized,
 * producing MSL source and exact JVM-DFC boundary templates without touching a
 * live ChunkNoiseSampler. A sampler-specific binding later re-instantiates only
 * the generated DFC arguments. Live IFastCacheLike objects are isolated behind
 * non-mutating Metal cache views, so their normal world-generation delegates are
 * not replaced.</p>
 *
 * <p>This class still does not redirect world generation. It establishes the
 * world-scoped compilation and sampler-scoped exact-boundary lifetimes required
 * before the existing 2x2 / 4x4 chunk scheduling path can safely dispatch Metal
 * work.</p>
 */
public final class MetalWorldgenSplinePrograms {

    private final List<ProgramTemplate> templates;

    private MetalWorldgenSplinePrograms(List<ProgramTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    public static MetalWorldgenSplinePrograms compile(NoiseRouter originalNoiseRouter) {
        Objects.requireNonNull(originalNoiseRouter, "originalNoiseRouter");

        List<ProgramTemplate> templates = new ArrayList<>();
        for (MetalWorldgenSplineDiscovery.DiscoveredSpline discovered
                : MetalWorldgenSplineDiscovery.discover(originalNoiseRouter)) {
            MetalF32SplinePlan plan = discovered.plan();
            templates.add(new ProgramTemplate(
                    discovered.binding(),
                    discovered.path(),
                    plan,
                    MetalF32SplineCompiler.compile(plan),
                    MetalExactBoundaryBatch.compile(plan),
                    MetalReferenceBoundaryBatch.fromPlan(plan)
            ));
        }
        return new MetalWorldgenSplinePrograms(templates);
    }

    public int size() {
        return this.templates.size();
    }

    public List<Descriptor> descriptors() {
        List<Descriptor> descriptors = new ArrayList<>(this.templates.size());
        for (ProgramTemplate template : this.templates) {
            descriptors.add(new Descriptor(
                    template.binding(),
                    template.path(),
                    template.plan().boundaryInputs().size(),
                    template.generatedSource().source().length()
            ));
        }
        return List.copyOf(descriptors);
    }

    BoundPrograms bindToSamplerVisitor(DensityFunction.DensityFunctionVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");

        List<BoundProgram> programs = new ArrayList<>(this.templates.size());
        for (ProgramTemplate template : this.templates) {
            programs.add(new BoundProgram(
                    template.binding(),
                    template.path(),
                    template.plan(),
                    template.generatedSource(),
                    template.boundaries().bindWithCacheViews(visitor),
                    template.references().bind(visitor)
            ));
        }
        return new BoundPrograms(programs);
    }

    public record Descriptor(
            String binding,
            String path,
            int boundaryCount,
            int sourceLength
    ) {
        public Descriptor {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(path, "path");
            if (boundaryCount < 0) throw new IllegalArgumentException("boundaryCount must be non-negative");
            if (sourceLength < 0) throw new IllegalArgumentException("sourceLength must be non-negative");
        }
    }

    public static final class BoundPrograms {

        private final List<BoundProgram> programs;

        private BoundPrograms(List<BoundProgram> programs) {
            this.programs = List.copyOf(programs);
        }

        public List<BoundProgram> programs() {
            return this.programs;
        }
    }

    public static final class BoundProgram {

        private final String binding;
        private final String path;
        private final MetalF32SplinePlan plan;
        private final GeneratedMetalSource generatedSource;
        private final MetalExactBoundaryBatch boundaries;
        private final MetalReferenceBoundaryBatch references;

        private BoundProgram(
                String binding,
                String path,
                MetalF32SplinePlan plan,
                GeneratedMetalSource generatedSource,
                MetalExactBoundaryBatch boundaries,
                MetalReferenceBoundaryBatch references
        ) {
            this.binding = Objects.requireNonNull(binding, "binding");
            this.path = Objects.requireNonNull(path, "path");
            this.plan = Objects.requireNonNull(plan, "plan");
            this.generatedSource = Objects.requireNonNull(generatedSource, "generatedSource");
            this.boundaries = Objects.requireNonNull(boundaries, "boundaries");
            this.references = Objects.requireNonNull(references, "references");
        }

        public String binding() {
            return this.binding;
        }

        public String path() {
            return this.path;
        }

        public MetalF32SplinePlan plan() {
            return this.plan;
        }

        public GeneratedMetalSource generatedSource() {
            return this.generatedSource;
        }

        public int boundaryCount() {
            return this.boundaries.boundaryCount();
        }

        public boolean[] referenceBoundaryMask() {
            return this.references.referenceMask();
        }

        public float[] evaluateBoundarySlotMajor(
                int[] x,
                int[] y,
                int[] z,
                EvalType type,
                DfcObjectCache cache
        ) {
            return this.boundaries.evaluateSlotMajor(x, y, z, type, cache);
        }

        public void fillBoundarySlotMajor(
                int[] x,
                int[] y,
                int[] z,
                EvalType type,
                DfcObjectCache cache,
                float[] output
        ) {
            this.boundaries.fillSlotMajor(x, y, z, type, cache, output);
        }

        public float[] evaluateReferenceBoundarySlotMajor(
                int[] x,
                int[] y,
                int[] z,
                EvalType type,
                DfcObjectCache cache
        ) {
            return this.references.evaluateSlotMajor(x, y, z, type, cache);
        }
    }

    private record ProgramTemplate(
            String binding,
            String path,
            MetalF32SplinePlan plan,
            GeneratedMetalSource generatedSource,
            MetalExactBoundaryBatch boundaries,
            MetalReferenceBoundaryBatch references
    ) {
        private ProgramTemplate {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(generatedSource, "generatedSource");
            Objects.requireNonNull(boundaries, "boundaries");
            Objects.requireNonNull(references, "references");
        }
    }
}
