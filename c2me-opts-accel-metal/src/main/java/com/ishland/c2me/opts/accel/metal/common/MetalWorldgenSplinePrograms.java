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
import net.minecraft.world.gen.noise.NoiseRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * World-lifetime compiled representation of the currently supported Metal F32
 * spline islands.
 *
 * <p>The original NoiseRouter can be scanned once when a world is initialized,
 * producing MSL source and exact JVM-DFC boundary templates without touching a
 * live ChunkNoiseSampler. The templates intentionally remain encapsulated here
 * until a non-mutating or construction-ordered cache/interpolator binding path
 * is implemented.</p>
 *
 * <p>In particular, callers must not take an already-live ChunkNoiseSampler
 * cache/interpolator and rebind an exact boundary CompiledEntry to it after
 * construction. Current DFC runtime cache wrappers implement
 * {@code c2me$withDelegate} by mutating their delegate in place, so that would
 * overwrite state owned by the normal world-generation graph.</p>
 *
 * <p>This class still does not redirect world generation. It establishes the
 * safe world-scoped compilation lifetime while deliberately keeping the
 * sampler-binding phase closed until its ownership semantics are resolved.</p>
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
                    MetalExactBoundaryBatch.compile(plan)
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

    private record ProgramTemplate(
            String binding,
            String path,
            MetalF32SplinePlan plan,
            GeneratedMetalSource generatedSource,
            MetalExactBoundaryBatch boundaries
    ) {
        private ProgramTemplate {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(generatedSource, "generatedSource");
            Objects.requireNonNull(boundaries, "boundaries");
        }
    }
}
