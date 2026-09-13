# c2me-opts-accel-metal

Experimental native Metal compute backend for C2ME on macOS.

## Current milestone

The module currently provides the runtime and compiler foundation required by a real Metal world-generation backend:

- macOS-only activation with clean fallback on every other platform;
- direct loading of the system Metal, Foundation and CoreGraphics frameworks;
- `MTLCreateSystemDefaultDevice` device discovery;
- Metal command-queue creation;
- runtime Metal Shading Language compilation;
- Metal compilation with fast math disabled so generated F32 kernels use the safe/precise path;
- compute-pipeline creation and process-lifetime pipeline caching;
- reusable power-of-two `MTLStorageModeShared` buffer pooling;
- real 1D batched compute dispatch through `MTLComputeCommandEncoder`;
- shared-buffer GPU write + CPU raw-bit readback validation;
- Intel macOS and Apple Silicon LWJGL native packaging;
- a conservative DensityFunction-AST Metal compatibility gate;
- the first direct DFC F32-to-MSL code-generation path (`ConstantF32Node`);
- a backend-neutral `GeneratedProgramMetadata` container shared with the existing OpenCL generated-program result;
- an explicit host/exact F64 -> F32 boundary path that prevents Metal from recursively lowering F64 density functions to float;
- a backend-internal hybrid `MetalF32SplinePlan` / `MetalF32SplinePlanner` that keeps F64 spline location producers outside the shader and represents only audited F32 spline work;
- `MetalExactBoundaryBatch`, which compiles those F64 boundary AST producers through C2ME's existing JVM DFC generator and evaluates all samples through the generated bulk path before the explicit host `(float)` conversion;
- DFC visitor rebinding for exact boundary roots so future ChunkNoiseSampler integrations can install the same runtime cache/interpolator arguments used by normal compiled DensityFunctions;
- `MetalWorldgenSplineDiscovery`, which scans the same optimized original NoiseRouter graphs used by the OpenCL compiler and finds maximal currently-supported F32 spline islands without descending into their F64 location producers;
- `MetalWorldgenSplinePrograms`, which compiles discovery results once per world into immutable MSL + exact-boundary templates and keeps ChunkNoiseSampler-specific visitor binding as a separate lifetime step;
- `MetalF32SplineReference`, a Java reference implementation matching the existing OpenCL spline range search, extrapolation and interpolation operation ordering;
- `MetalF32SplineCompiler`, which emits nested planned F32 spline trees as MSL without placing any F64 producer work in the shader;
- startup validation that exercises exact DFC bulk JIT -> explicit F64/F32 boundary -> MSL -> Metal, 257-element batching, pipeline reuse, buffer reuse, and a nested spline raw-bit differential probe.

The runtime bridge uses LWJGL's Objective-C/JNI helpers and its bundled LibFFI support for Objective-C calls that pass `MTLSize` structures by value. It does not require an extra JNI dylib and does not require Java FFM native-access flags.

The backend is marked available only after generated probes are compiled, dispatched on the GPU, completed and read back with the expected IEEE-754 bits. A failure at any stage leaves the normal C2ME path active.

`metalAccel.enabled` is intentionally **disabled by default** at this stage. Enabling it currently validates the Metal compiler/runtime/compute path only; it does not redirect chunk generation yet.

The Metal module is packaged independently from the main C2ME jar, matching the optional-backend boundary used by the OpenCL accelerator. It currently depends only on the C2ME base and DFC modules; chunk-system integration is intentionally not declared until a real world-generation dispatch path exists.

## F32 capability and boundary policy

Metal does not silently reinterpret C2ME's F64 DensityFunction graph as F32. `MetalCompatibility` requires every directly compiled AST node to be explicitly F32 in DFC and to have an audited MSL emitter. Any F64 or unsupported subtree is rejected before direct Metal code generation.

The initial direct AST emitter intentionally supports only `ConstantF32Node`. Float constants are emitted from their raw IEEE-754 bit pattern with MSL `as_type<float>` instead of decimal literals, avoiding an extra text-parsing rounding step and preserving values such as signed zero exactly.

C2ME's existing OpenCL spline path demonstrates an important mixed-precision boundary: the spline location function is evaluated as F64, then explicitly cast to float, and the subsequent spline math is F32. `ToF32Node` therefore must not be retyped just to make it easier for Metal to consume. The Metal backend instead keeps exact/F64 work on the host/exact path, performs the required Java/IEEE-754 float rounding there, and hands only already-rounded raw F32 values to Metal.

`MetalF32SplinePlanner` applies this policy to `SplineNormalNode`: every location function remains an F64 boundary producer, repeated producer objects share a boundary slot, and nested spline values are planned recursively only when their result is already F32. This is intentionally separate from the conservative direct-AST compatibility gate.

`MetalExactBoundaryBatch` closes the exact execution link. It registers each planned F64 producer as a root in `BytecodeGen.Context`, finalizes the normal JVM DFC generated class, and evaluates the resulting `SubCompiledDensityFunction` roots with `EachApplierVanillaInterface`. `AbstractCompiledDensityFunction.fill` therefore reaches the existing generated `IMultiMethod.evalMulti(double[], int[], int[], int[], ...)` path for the entire coordinate batch. Only after that F64 bulk evaluation is complete are values explicitly converted to F32 in slot-major order.

The boundary batch retains its `CompiledEntry` so it can be rebound with a `DensityFunctionVisitor` before runtime evaluation. That rebinding follows the same `ICompiledCachingAwareVisitor` / `CompiledEntry.newInstance(...)` mechanism used by `CompiledDensityFunction.apply`, allowing wrapper arguments in a real ChunkNoiseSampler to become its runtime cache/interpolator objects instead of silently remaining uncached original NoiseRouter wrappers.

This distinction is required for correctness. Original `DensityFunctionTypes.Wrapping` objects implement the DFC fast-cache interface as cache misses with no-op cache writes. Evaluating an original-router boundary graph directly is therefore an exact uncached graph, but it is not automatically equivalent to a ChunkNoiseSampler graph after its interpolators and cache wrappers have been installed. Real world-generation dispatch must bind boundary templates during sampler setup, before the interpolation loop begins.

The exact boundary roots intentionally have no blending fallback. A real chunk-generation integration must preserve the existing no-blending/offload eligibility gate before using this path.

The startup probes still verify the generic explicit F64 -> F32 transport path, and the nested spline probe uses two real generated F64 boundary producers based on batched coordinates and non-binary scales. Their DFC-JIT results are checked before the Java reference and Metal result are required to match at raw binary32 level for all 257 samples. This validates the implemented spline-island contract without claiming that arbitrary F32 DensityFunction arithmetic is already portable to Metal.

## Real world-generation graph discovery

`MetalWorldgenSplineDiscovery` consumes the original `NoiseRouter` retained by DFC rather than trying to reverse-engineer already-compiled DensityFunctions. It converts the same world-generation bindings used by OpenCL through `McToAst.toAst(...)` and `OptoPasses.optimizeOCL(...)`, so cache elimination, tree normalization, constant folding and branch elimination produce the same accelerator-facing AST shape.

The discovery pass examines the standard NoiseRouter bindings plus `final_final_density`. When a `SplineNormalNode` can be planned as one Metal F32 island, the whole maximal spline tree is recorded and nested spline children are not emitted as separate candidates. If an outer spline is not yet supported, discovery may continue through its F32 value children, but it does not descend into the outer spline's F64 location producer. That prevents a future execution plan from accidentally creating a CPU -> GPU -> CPU dependency inside what should remain one exact boundary.

`MetalWorldgenSplinePrograms` turns those discoveries into world-lifetime templates: each candidate owns its `MetalF32SplinePlan`, generated MSL source and compiled exact DFC boundary template. A later ChunkNoiseSampler binding step re-instantiates only the DFC arguments through that sampler's visitor. This keeps expensive AST discovery, JVM bytecode generation and MSL source generation out of the chunk hot path while preserving sampler-specific cache semantics.

## Floating-point compilation policy

Metal library compilation uses `MTLCompileOptions` with `fastMathEnabled = false`. On current Metal versions this selects safe math mode and precise FP32 math functions; it also provides a broadly compatible way to disable the older fast-math path on previous macOS releases.

This is necessary but not considered sufficient proof of world-generation determinism. The nested spline runtime probe is therefore a hard availability gate: if either the exact DFC boundary path or Metal changes the audited operation sequence in a way that changes the expected raw F32 bits, initialization fails and C2ME continues on its normal path.

Every additional arithmetic-heavy workload still needs its own CPU/OpenCL-vs-Metal differential coverage before it can be used for real chunk generation.

## Shared generated-program ABI

The OpenCL result object stores its backend-independent execution data in `GeneratedProgramMetadata` while retaining the existing OpenCL constructor and getters. The shared metadata currently covers:

- constant data;
- global dynamic-data offsets;
- flat-cache, cache2d and interpolator prefill counts;
- generated defines;
- biome mappings.

Shader source, backend-specific debug artifacts and native pipeline/device objects remain backend-specific. `GeneratedMetalSource` carries the same metadata type so Metal can adopt the existing world-generation ABI incrementally instead of cloning it.

## Runtime batching and reuse

`MetalPipelineCache` retains compiled compute pipelines by generated source and entry point. `MetalSharedBufferPool` reuses CPU-visible shared buffers in bounded power-of-two buckets. `MetalBatchExecutor` uses both for generated F32 batches, explicit boundary batches and planned spline batches.

The startup validation intentionally dispatches repeated 257-element workloads and verifies pipeline/shared-buffer reuse. The spline path stores boundary inputs slot-major at the front of one shared buffer and writes the result region after them, avoiding a per-boundary Objective-C buffer-binding expansion. This keeps MSL compilation and repeated Objective-C allocation out of the eventual hot world-generation loop.

The F64 side is batched as well: all samples for one boundary producer are evaluated by one generated DFC bulk call before moving to the next boundary slot. There is no spline-by-spline GPU submission or CPU/GPU wait in this design.

## Hybrid spline implementation

The first nontrivial F32 island is implemented internally, but is not yet connected to chunk generation:

1. `MetalWorldgenSplineDiscovery` finds maximal candidate spline islands in the OpenCL-optimized original NoiseRouter ASTs.
2. `MetalF32SplinePlanner` accepts audited `ConstantF32Node` / `SplineNormalNode` structure and extracts each F64 location producer as an explicit host boundary input.
3. `MetalWorldgenSplinePrograms` compiles the resulting plans once per world into MSL and exact JVM-DFC boundary templates.
4. A sampler-scoped `bind(visitor)` step re-instantiates boundary arguments through the normal DFC visitor mechanism so runtime wrappers can preserve ChunkNoiseSampler cache/interpolation semantics.
5. `MetalExactBoundaryBatch` evaluates each boundary slot over the complete x/y/z coordinate batch using the generated F64 multi method.
6. The host performs the explicit `(float)` conversion only after exact bulk evaluation and writes the values in slot-major order.
7. `MetalF32SplineReference` evaluates the plan on Java using the same binary range search, outside-range sampling and interpolation expression ordering as the current OpenCL spline runtime/emitter.
8. `MetalF32SplineCompiler` emits the same planned tree as MSL, including nested spline values and raw-bit encoded locations/derivatives.
9. `MetalSplineRuntimeProbe` validates the DFC-produced boundaries, then compares Java-reference and Metal results bit-for-bit across 257 samples while also verifying pipeline/buffer reuse.

The planner does **not** make `SplineNormalNode` generally safe for direct recursive Metal AST compilation. Its F64 location producers remain deliberately outside Metal.

## World-generation integration status

This milestone deliberately does **not** redirect chunk generation yet. The existing OpenCL accelerator contains the real batching, prefill, device scheduling and chunk-system integration path; Metal should attach at the same region-level boundary rather than submit one GPU command per DensityFunction or spline.

The real OpenCL batching point is now identified: `BatchingBiomeNoiseStatus` creates an aligned `BATCH_SIZE x BATCH_SIZE` region and hands it to `CLServerBatchedBiomeNoiseContext`. The batch size is 2 or 4 chunks. The context constructs one `worldgen_data_root`, then uses the same read/write buffer through interpolator prefill, aquifer prefill, cache2d prefill and the final noise kernel before one batched readback and chunk writeback. `BatchingBiomeNoiseStatus` also verifies `Blender.getNoBlending()` across the whole region before offload, matching the current exact-boundary requirement.

The OpenCL worldgen data layout gives the relevant batch geometry directly: cell start/count, block-space cache2d start/size, biome area and other dynamic offsets are built once for the region. Interpolator prefill samples exact cell-corner block coordinates, while cache2d prefill samples the whole region in block X/Z. Metal should reuse this region geometry instead of independently walking every chunk.

The exact F64 bulk execution mechanism is therefore no longer an open question, and neither is the 2x2/4x4 scheduling boundary. The remaining correctness boundary is sampler-specific cache/interpolator rebinding: Metal must bind world-scoped exact boundary templates through the actual ChunkNoiseSampler visitor during sampler setup, before interpolation starts. Only after that is proven equivalent should the first real region dispatch be wired into chunk-system scheduling.

`c2me-rewrites-chunk-system` remains intentionally absent from the Metal module until that dispatch integration is actually implemented. Discovery, world-lifetime compilation and sampler-level DFC binding need only the existing base + DFC dependencies.

## CI

`.github/workflows/metal-build.yml` can run on pull requests, pushes and manual dispatch. It checks the shared DFC/OpenCL/Metal Java ABI on Ubuntu and compiles the Metal module on both `macos-15` (Apple Silicon) and `macos-15-intel` using JDK 25.

These CI jobs validate the Java/Gradle build on both macOS architectures; they do **not** by themselves prove that the runtime MSL probes executed on a physical Metal device. Real native runtime evidence is still required before the PR can leave Draft status.

## Next implementation stages

1. Capture or otherwise expose the actual ChunkNoiseSampler construction visitor early enough to bind `MetalWorldgenSplinePrograms` before the interpolation loop, without creating a second independent set of cache/interpolator wrappers.
2. Add integration-level differential coverage that evaluates discovered real-world spline islands through the bound exact DFC roots and compares their slot-major F32 boundaries and Metal outputs against the normal CPU/OpenCL behavior.
3. Reuse the existing 2x2 / 4x4 batch geometry to build one sufficiently large Metal workload, preserving the no-blending gate and avoiding per-spline synchronization.
4. Only when the real dispatch is ready, add the required chunk-system dependency/integration and route the first validated region workload behind the default-off capability flag.
5. Benchmark real chunk/region batches and reject offloads where transfer/synchronization overhead outweighs compute savings.
6. Only enable Metal world-generation dispatch by default after output compatibility and performance are proven on real Intel and Apple Silicon Macs.

The design goal is to make Metal a first-class macOS backend without reducing world-generation determinism or destabilizing the existing OpenCL accelerator.
