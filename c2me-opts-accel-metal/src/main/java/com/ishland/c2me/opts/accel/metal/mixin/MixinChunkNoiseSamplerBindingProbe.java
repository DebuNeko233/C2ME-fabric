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

package com.ishland.c2me.opts.accel.metal.mixin;

import com.ishland.c2me.opts.accel.metal.common.MetalSamplerBindingIntegrationProbe;
import com.ishland.c2me.opts.accel.metal.common.MetalWorldgenSplinePrograms;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Test-only hook enabled by MetalTestMixinPlugin in the development server run.
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class MixinChunkNoiseSamplerBindingProbe {

    @Unique
    private NoiseRouter c2me$metal$probeNoiseRouter;

    @Unique
    private MetalWorldgenSplinePrograms.BoundPrograms c2me$metal$boundPrograms;

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/noise/NoiseRouter;apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$DensityFunctionVisitor;)Lnet/minecraft/world/gen/noise/NoiseRouter;"
            )
    )
    private NoiseRouter c2me$metal$captureNoiseRouter(
            NoiseRouter instance,
            DensityFunction.DensityFunctionVisitor visitor,
            Operation<NoiseRouter> original
    ) {
        this.c2me$metal$probeNoiseRouter = instance;
        return original.call(instance, visitor);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void c2me$metal$probeSamplerBinding(CallbackInfo ci) {
        if (this.c2me$metal$probeNoiseRouter == null) {
            throw new IllegalStateException("Metal sampler-binding test failed to capture the NoiseRouter");
        }
        this.c2me$metal$boundPrograms = MetalSamplerBindingIntegrationProbe.bind(
                (ChunkNoiseSampler) (Object) this,
                this.c2me$metal$probeNoiseRouter
        );
    }

    @Inject(method = "onSampledCellCorners", at = @At("RETURN"))
    private void c2me$metal$probeBoundaryDifferential(int cellY, int cellZ, CallbackInfo ci) {
        if (this.c2me$metal$boundPrograms != null) {
            MetalSamplerBindingIntegrationProbe.probeInterpolationCell(
                    (ChunkNoiseSampler) (Object) this,
                    this.c2me$metal$boundPrograms
            );
        }
    }
}
