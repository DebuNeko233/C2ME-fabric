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

import com.ishland.c2me.opts.dfc.common.ast.EvalType;
import com.ishland.c2me.opts.dfc.common.ducks.IDfcObjectCacheCapable;
import com.ishland.c2me.opts.dfc.common.ducks.IPreloadedCoordinates;
import com.ishland.c2me.opts.dfc.common.ducks.NoiseRouterExtension;
import com.ishland.c2me.opts.dfc.common.gen.jvm.util.DfcObjectCache;
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
 *
 * <p>At a bounded number of real interpolation cells the probe also evaluates
 * original, sampler-bound Minecraft spline-location functions first and the
 * generated exact DFC boundary roots second. Their explicit F64 -> F32 results
 * must match bit-for-bit for every reference-backed boundary slot.</p>
 */
public final class MetalSamplerBindingIntegrationProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetalSamplerBindingIntegrationProbe.class);
    private static final int MAX_DIFFERENTIAL_CELLS = 64;
    private static final Map<NoiseRouter, MetalWorldgenSplinePrograms> PROGRAMS = new IdentityHashMap<>();
    private static final AtomicInteger SAMPLERS = new AtomicInteger();
    private static final AtomicInteger BOUND_PROGRAMS = new AtomicInteger();
    private static final AtomicInteger DIFFERENTIAL_CELLS = new AtomicInteger();
    private static final AtomicInteger DIFFERENTIAL_VALUES = new AtomicInteger();

    private MetalSamplerBindingIntegrationProbe() {
    }

    public static MetalWorldgenSplinePrograms.BoundPrograms bind(
            ChunkNoiseSampler sampler,
            NoiseRouter routedNoiseRouter
    ) {
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
        return boundPrograms;
    }

    public static void probeInterpolationCell(
            ChunkNoiseSampler sampler,
            MetalWorldgenSplinePrograms.BoundPrograms boundPrograms
    ) {
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(boundPrograms, "boundPrograms");
        if (boundPrograms.programs().isEmpty()) {
            return;
        }

        int probeIndex = DIFFERENTIAL_CELLS.getAndIncrement();
        if (probeIndex >= MAX_DIFFERENTIAL_CELLS) {
            return;
        }

        IPreloadedCoordinates coordinates = (IPreloadedCoordinates) (Object) sampler;
        int[] x = coordinates.c2me$getXArray();
        int[] y = coordinates.c2me$getYArray();
        int[] z = coordinates.c2me$getZArray();
        DfcObjectCache cache = ((IDfcObjectCacheCapable) (Object) sampler).c2me$getDfcObjectCache();

        int comparedValues = 0;
        for (MetalWorldgenSplinePrograms.BoundProgram program : boundPrograms.programs()) {
            boolean[] mask = program.referenceBoundaryMask();
            boolean hasReference = false;
            for (boolean present : mask) {
                hasReference |= present;
            }
            if (!hasReference) {
                continue;
            }

            // Reference first: establish exactly the cache/interpolator state that
            // normal world generation observes at this interpolation phase.
            float[] reference = program.evaluateReferenceBoundarySlotMajor(
                    x, y, z, EvalType.INTERPOLATION, cache
            );
            float[] exact = program.evaluateBoundarySlotMajor(
                    x, y, z, EvalType.INTERPOLATION, cache
            );

            int sampleCount = x.length;
            for (int slot = 0; slot < mask.length; slot++) {
                if (!mask[slot]) {
                    continue;
                }
                int base = slot * sampleCount;
                for (int sample = 0; sample < sampleCount; sample++) {
                    int referenceBits = Float.floatToRawIntBits(reference[base + sample]);
                    int exactBits = Float.floatToRawIntBits(exact[base + sample]);
                    if (referenceBits != exactBits) {
                        throw new IllegalStateException("Metal sampler boundary differential mismatch for "
                                + program.binding() + "/" + program.path()
                                + " slot=" + slot
                                + " sample=" + sample
                                + " xyz=(" + x[sample] + "," + y[sample] + "," + z[sample] + ")"
                                + " reference=0x" + Integer.toHexString(referenceBits)
                                + " exact=0x" + Integer.toHexString(exactBits));
                    }
                    comparedValues++;
                }
            }
        }

        if (comparedValues != 0) {
            int total = DIFFERENTIAL_VALUES.addAndGet(comparedValues);
            if (probeIndex < 4) {
                LOGGER.info("Metal sampler boundary differential cell #{} matched {} raw F32 value(s), total={}",
                        probeIndex + 1, comparedValues, total);
            }
        }
    }

    public static int samplerCount() {
        return SAMPLERS.get();
    }

    public static int boundProgramCount() {
        return BOUND_PROGRAMS.get();
    }

    public static int differentialValueCount() {
        return DIFFERENTIAL_VALUES.get();
    }
}
