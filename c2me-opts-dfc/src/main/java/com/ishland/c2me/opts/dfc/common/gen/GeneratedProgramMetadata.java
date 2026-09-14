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

package com.ishland.c2me.opts.dfc.common.gen;

import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

import java.util.Objects;

/**
 * Backend-neutral data contract produced alongside generated density-function
 * programs.
 *
 * <p>The shader/source text and backend-specific debug artifacts deliberately
 * do not live here. This object only describes data that a compute backend
 * needs in order to execute the generated program with C2ME's world-generation
 * runtime contract.</p>
 */
public record GeneratedProgramMetadata(
        byte[] constData,
        Reference2IntLinkedOpenHashMap<Object> globalDynamicDataOffsets,
        int flatCachePrefills,
        int cache2dPrefills,
        int interpolatorPrefills,
        Object2ReferenceOpenHashMap<String, String> defines,
        RegistryEntry<Biome>[] biomeMappings
) {

    public GeneratedProgramMetadata {
        Objects.requireNonNull(constData, "constData");
        Objects.requireNonNull(globalDynamicDataOffsets, "globalDynamicDataOffsets");
        Objects.requireNonNull(defines, "defines");
        if (flatCachePrefills < 0 || cache2dPrefills < 0 || interpolatorPrefills < 0) {
            throw new IllegalArgumentException("Cache prefill counts must be non-negative");
        }
    }

    /**
     * Empty metadata for generated programs that currently have no worldgen
     * data dependencies, such as the Metal compiler/runtime validation probe.
     */
    public static GeneratedProgramMetadata empty() {
        Reference2IntLinkedOpenHashMap<Object> dynamicOffsets = new Reference2IntLinkedOpenHashMap<>();
        dynamicOffsets.defaultReturnValue(Integer.MAX_VALUE);
        return new GeneratedProgramMetadata(
                new byte[0],
                dynamicOffsets,
                0,
                0,
                0,
                new Object2ReferenceOpenHashMap<>(),
                null
        );
    }

}
