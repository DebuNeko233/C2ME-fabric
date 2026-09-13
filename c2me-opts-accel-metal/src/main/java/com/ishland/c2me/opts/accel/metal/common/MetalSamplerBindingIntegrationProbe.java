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

import com.ishland.c2me.opts.dfc.common.ducks.NoiseRouterExtension;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development-server integration probe for the sampler-bound exact Metal path.
 *
 * <p>No native Metal API is used here. The existing C2ME pre-generation test can
 * therefore exercise this path on Linux CI: the original NoiseRouter is compiled
 * once per identity, then every real ChunkNoiseSampler is bound through the same
 * cache/interpolator ownership bridge intended for future region dispatch.</p>
 */
public final class MetalSamplerBindingIntegrationProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetalSamplerBindingIntegrationProbe.class);
    private static final Map<NoiseRouter, MetalWorldgenSplinePrograms> PROGRAMS = new IdentityHashMap<>();
    private static final AtomicInteger SAMPLERS = new AtomicInteger();
    private static final AtomicInteger BOUND_PROGRAMS = new AtomicInteger();

    private MetalSamplerBindingIntegrationProbe() {
    }

    public static void probe(ChunkNoiseSampler sampler, NoiseRouter routedNoiseRouter) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(routedNoiseRouter, "routedNoiseRouter");

        NoiseRouter originalNoiseRouter = ((NoiseRouterExtension) (Object) routedNoiseRouter).c2me$getOriginalNoiseRouter();
        if (originalNoiseRouter == null) {
            originalNoiseRouter = routedNoiseRouter;
        }

        MetalWorldgenSplinePrograms programs;
        synchronized (PROGRAMS) {
            programs = PROGRAMS.get(originalNoiseRouter);
            if (programs == null) {
                programs = MetalWorldgenSplinePrograms.compile(originalNoiseRouter);
                PROGRAMS.put(originalNoiseRouter, programs);
                LOGGER.info("Metal sampler-binding test compiled {} spline program(s) for a NoiseRouter", programs.size());
            }
        }

        MetalWorldgenSplinePrograms.BoundPrograms boundPrograms = MetalChunkNoiseSamplerBinding.bind(sampler, programs);
        int samplerCount = SAMPLERS.incrementAndGet();
        BOUND_PROGRAMS.addAndGet(boundPrograms.programs().size());
        if (samplerCount <= 4) {
            LOGGER.info("Metal sampler-binding test bound sampler #{} with {} spline program(s)",
                    samplerCount, boundPrograms.programs().size());
        }
    }

    public static int samplerCount() {
        return SAMPLERS.get();
    }

    public static int boundProgramCount() {
        return BOUND_PROGRAMS.get();
    }
}
