/*
 * All Rights Reserved
 *
 * Copyright (c) 2025-2026 ishland
 *
 * All rights reserved. Do not redistribute.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.c2me.opts.accel.opencl.common.compiler;

import com.ishland.c2me.opts.dfc.common.gen.GeneratedProgramMetadata;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

import java.nio.file.Path;
import java.util.Objects;

public class GeneratedCLSource {

    private final long ordinal;
    private final String generatedSource;
    private final GeneratedProgramMetadata metadata;
    private final Path dumpedPath;

    /**
     * Compatibility constructor retained for the existing OpenCL generator and
     * runtime call sites. The shared execution metadata is now stored in a
     * backend-neutral container so other compute backends can use the same ABI.
     */
    public GeneratedCLSource(
            long ordinal,
            String generatedSource,
            byte[] constData,
            Reference2IntLinkedOpenHashMap<Object> globalDynamicDataOffsets,
            int flatCachePrefills,
            int cache2dPrefills,
            int interpolatorPrefills,
            Object2ReferenceOpenHashMap<String, String> defines,
            RegistryEntry<Biome>[] biomeMappings,
            Path dumpedPath
    ) {
        this(
                ordinal,
                generatedSource,
                new GeneratedProgramMetadata(
                        constData,
                        globalDynamicDataOffsets,
                        flatCachePrefills,
                        cache2dPrefills,
                        interpolatorPrefills,
                        defines,
                        biomeMappings
                ),
                dumpedPath
        );
    }

    public GeneratedCLSource(long ordinal, String generatedSource, GeneratedProgramMetadata metadata, Path dumpedPath) {
        this.ordinal = ordinal;
        this.generatedSource = Objects.requireNonNull(generatedSource, "generatedSource");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.dumpedPath = dumpedPath;
    }

    public long getOrdinal() {
        return this.ordinal;
    }

    public String getGeneratedSource() {
        return this.generatedSource;
    }

    public GeneratedProgramMetadata getMetadata() {
        return this.metadata;
    }

    public byte[] getConstData() {
        return this.metadata.constData();
    }

    public Reference2IntLinkedOpenHashMap<Object> getGlobalDynamicDataOffsets() {
        return this.metadata.globalDynamicDataOffsets();
    }

    public int getFlatCachePrefills() {
        return this.metadata.flatCachePrefills();
    }

    public int getInterpolatorPrefills() {
        return this.metadata.interpolatorPrefills();
    }

    public int getCache2dPrefills() {
        return this.metadata.cache2dPrefills();
    }

    public Object2ReferenceOpenHashMap<String, String> getDefines() {
        return this.metadata.defines();
    }

    public RegistryEntry<Biome>[] getBiomeMappings() {
        return this.metadata.biomeMappings();
    }

    public Path getDumpedPath() {
        return this.dumpedPath;
    }

}
