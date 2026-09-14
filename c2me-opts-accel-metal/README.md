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
- `MetalFastCacheView`, a non-mutating view over live DFC cache/interpolator wrappers that shares cache state without replacing the delegate owned by normal world generation;
- sampler-scoped exact-boundary rebinding through `MetalChunkNoiseSamplerBinding`, guarded against blending and interpolation-loop binding;
- binding-time guards that require both live fast-cache delegate identity and the sampler's `actualDensityFunctionCache` size to remain unchanged;
- a cross-platform JUnit contract test for non-mutating cache views;
- a development-server sampler-binding integration probe that runs during the repository's existing chunk pre-generation test without requiring a Metal GPU;
- `MetalWorldgenSplineDiscovery`, which scans the same optimized original NoiseRouter graphs used by the OpenCL compiler and finds maximal currently-supported F32 spline islands without descending into their F64 location producers;
- `MetalWorldgenSplinePrograms`, which compiles discovery results once per world into MSL + exact-boundary templates and can bind those templates to a sampler through non-mutating cache views;
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

### Sampler cache/interpolator ownership

Directly rebinding a second generated entry to an already-live `ChunkNoiseSampler` wrapper is unsafe with the normal DFC wrapper contract. `DensityInterpolator`, `FlatCache`, `Cache2D`, `CacheOnce` and `CellCache` implement `c2me$withDelegate(...)` by changing their delegate field in place. Giving those live objects directly to a Metal generated entry could therefore replace the delegate used by normal world generation.

`MetalFastCacheView` removes that ownership conflict. A sampler visitor still resolves each original NoiseRouter wrapper to the real runtime cache/interpolator object, but the result is immediately wrapped before `CompiledEntry.newInstance(...)` initializes generated fields. The view forwards `c2me$getCached(...)` and `c2me$cache(...)` to the live wrapper while storing its generated delegate separately. Its `c2me$withDelegate(...)` creates another view rather than mutating the backing object.

`MetalExactBoundaryBatch.bindWithCacheViews(...)` snapshots every resolved live `IFastCacheLike` delegate by object identity before generated-entry construction and verifies that identity afterwards. `MetalChunkNoiseSamplerBinding` also snapshots the sampler's `actualDensityFunctionCache` size and requires it to remain unchanged after binding. Therefore the sampler-bound path rejects both forms of structural mutation currently identified: replacing an existing live delegate or causing post-construction creation of additional actual-density wrappers.

`MetalFastCacheViewTest` separately exercises delegate isolation and cache-state forwarding without requiring a Metal device. The development server probe then exercises the full world-scoped compile -> real `ChunkNoiseSampler` bind path across the repository's normal pre-generation workload.

This means the Metal exact graph can observe and update the same cache state while normal world-generation delegate ownership remains unchanged. Cache-state sharing itself is intentional, but it still needs numerical integration-level differential testing against normal CPU/OpenCL behavior before any region dispatch is enabled.

Original `DensityFunctionTypes.Wrapping` objects are not sufficient for real sampler equivalence: their DFC fast-cache implementation behaves as cache misses with no-op writes. The exact boundary compiler can evaluate such graphs correctly as uncached exact functions, as the startup probe does, while real sampler-bound evaluation uses the actual runtime wrappers behind `MetalFastCacheView`.

The exact boundary roots intentionally have no blending fallback. `MetalChunkNoiseSamplerBinding` rejects blended samplers, and any future region scheduler must preserve the existing region-wide no-blending eligibility gate as well.

The startup probes verify the generic explicit F64 -> F32 transport path, and the nested spline probe uses two real generated F64 boundary producers based on batched coordinates and non-binary scales. Their DFC-JIT results are checked before the Java reference and Metal result are required to match at raw binary32 level for all 257 samples. This validates the implemented spline-island contract without claiming that arbitrary F32 DensityFunction arithmetic is already portable to Metal.

## Real world-generation graph discovery

`MetalWorldgenSplineDiscovery` consumes the original `NoiseRouter` retained by DFC rather than trying to reverse-engineer already-compiled DensityFunctions. It converts the same world-generation bindings used by OpenCL through `McToAst.toAst(...)` and `OptoPasses.optimizeOCL(...)`, so cache elimination, tree normalization, constant folding and branch elimination produce the same accelerator-facing AST shape.

The discovery pass examines the standard NoiseRouter bindings plus `final_final_density`. When a `SplineNormalNode` can be planned as one Metal F32 island, the whole maximal spline tree is recorded and nested spline children are not emitted as separate candidates. If an outer spline is not yet supported, discovery may continue through its F32 value children, but it does not descend into the outer spline's F64 location producer. That prevents a future execution plan from accidentally creating a CPU -> GPU -> CPU dependency inside what should remain one exact boundary.

`MetalWorldgenSplinePrograms` turns those discoveries into world-lifetime templates: each candidate owns its `MetalF32SplinePlan`, generated MSL source and compiled exact DFC boundary template. Expensive AST discovery, JVM bytecode generation and MSL source generation therefore stay out of the chunk hot path. `MetalChunkNoiseSamplerBinding` later re-instantiates only the DFC arguments for one sampler and isolates live cache/interpolator objects behind non-mutating views.

## Development-server integration probe

The repository already has a pre-generation test path in `tests:test-mod`: on the first server tick it pre-generates chunks for every loaded world, and the server exits on the following tick. The development `runTestC2MEServer` task now adds `-Dc2me.metal.testSamplerBinding=true`.

When that property is present, the Metal module's dedicated test mixin is enabled even though the native Metal backend itself remains disabled on Linux. The mixin captures the `NoiseRouter` used by each real `ChunkNoiseSampler`. At constructor return, `MetalSamplerBindingIntegrationProbe` resolves the original router retained by DFC, compiles `MetalWorldgenSplinePrograms` once per router identity, and binds the programs to the constructed sampler.

This probe deliberately does not call Metal native APIs and does not redirect density evaluation. Its purpose is to exercise the exact ownership boundary on Linux CI across many real samplers. Any live delegate mutation, unexpected creation of additional actual-density wrappers, blending misuse or interpolation-loop misuse fails the server test. The production server test does not enable the property and its production mod set does not include the optional Metal module.

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
4. `MetalChunkNoiseSamplerBinding` resolves original wrapper arguments through one sampler's actual-density-function visitor before the interpolation loop starts.
5. `MetalFastCacheView` shares the sampler's cache/interpolator state while keeping the Metal generated delegate separate from the live wrapper's normal worldgen delegate.
6. Binding verifies by identity that no live fast-cache delegate changed and verifies that no new actual-density wrapper was inserted.
7. `MetalExactBoundaryBatch` evaluates each bound boundary slot over the complete x/y/z coordinate batch through the generated F64 multi method.
8. The host performs the explicit `(float)` conversion only after exact bulk evaluation and writes the values in slot-major order.
9. `MetalF32SplineReference` evaluates the plan on Java using the same binary range search, outside-range sampling and interpolation expression ordering as the current OpenCL spline runtime/emitter.
10. `MetalF32SplineCompiler` emits the same planned tree as MSL, including nested spline values and raw-bit encoded locations/derivatives.
11. `MetalSplineRuntimeProbe` validates the synthetic DFC-produced boundaries, then compares Java-reference and Metal results bit-for-bit across 257 samples while also verifying pipeline/buffer reuse.
12. `runTestC2MEServer` exercises world-scoped discovery/compile and sampler-scoped binding on real pre-generated chunks without issuing native Metal work.

The sampler-bound path now has an ownership-safe bridge and a real lifecycle integration probe, but it is deliberately not connected to chunk generation until numerical differential tests prove that shared cache/interpolator state and evaluation timing remain equivalent.

The planner does **not** make `SplineNormalNode` generally safe for direct recursive Metal AST compilation. Its F64 location producers remain deliberately outside Metal.

## World-generation integration status

This milestone deliberately does **not** redirect chunk generation yet. The existing OpenCL accelerator contains the real batching, prefill, device scheduling and chunk-system integration path; Metal should attach at the same region-level boundary rather than submit one GPU command per DensityFunction or spline.

The real OpenCL batching point is identified: `BatchingBiomeNoiseStatus` creates an aligned `BATCH_SIZE x BATCH_SIZE` region and hands it to `CLServerBatchedBiomeNoiseContext`. The batch size is 2 or 4 chunks. The context constructs one `worldgen_data_root`, then uses the same read/write buffer through interpolator prefill, aquifer prefill, cache2d prefill and the final noise kernel before one batched readback and chunk writeback. `BatchingBiomeNoiseStatus` also verifies `Blender.getNoBlending()` across the whole region before offload, matching the current exact-boundary requirement.

The OpenCL worldgen data layout gives the relevant batch geometry directly: cell start/count, block-space cache2d start/size, biome area and other dynamic offsets are built once for the region. Interpolator prefill samples exact cell-corner block coordinates, while cache2d prefill samples the whole region in block X/Z. Metal should reuse this region geometry instead of independently walking every chunk.

The exact F64 bulk execution mechanism, sampler delegate ownership model, real sampler construction lifecycle and 2x2/4x4 scheduling boundary are now identified. The next correctness gate is numerical integration-level equivalence: evaluate discovered real spline islands through the sampler-bound roots at valid cache/interpolation phases, compare their slot-major F32 boundaries with the normal CPU/OpenCL behavior, and then compare the corresponding Metal output.

`c2me-rewrites-chunk-system` remains intentionally absent from the Metal module until the dispatch integration is actually implemented. Discovery, world-lifetime compilation and sampler-bound exact evaluation still require only the existing base + DFC dependencies.

## CI

`.github/workflows/metal-build.yml` can run on pull requests, pushes and manual dispatch. It checks the shared DFC/OpenCL/Metal Java ABI on Ubuntu and compiles the Metal module on both `macos-15` (Apple Silicon) and `macos-15-intel` using JDK 25.

The normal Gradle test lifecycle also runs the Metal module's cross-platform JUnit ownership contract. In addition, `runTestC2MEServer` enables the sampler-binding integration probe during real chunk pre-generation on Linux. Neither test requires a physical Metal device.

These CI jobs validate Java/Gradle compilation, the structural cache-ownership contract and real sampler binding. They do **not** by themselves prove that runtime MSL probes executed on a physical Metal device or that a sampler-bound spline result is numerically identical at every valid evaluation phase. Those remain required before the PR can leave Draft status.

## Next implementation stages

1. Add sampler-bound numerical differential coverage using discovered real-world spline islands, including valid cache/interpolator phases and raw F32 boundary equality against the normal CPU/OpenCL path.
2. Extend that real-world differential to the emitted Metal spline output on physical Intel and Apple Silicon Macs.
3. Reuse the existing 2x2 / 4x4 batch geometry to build one sufficiently large Metal workload, preserving the no-blending gate and avoiding per-spline synchronization.
4. Only when that real dispatch is ready, add the required chunk-system dependency/integration and route the first validated region workload behind the default-off capability flag.
5. Benchmark real chunk/region batches and reject offloads where transfer/synchronization overhead outweighs compute savings.
6. Only enable Metal world-generation dispatch by default after output compatibility and performance are proven on real Intel and Apple Silicon Macs.

The design goal is to make Metal a first-class macOS backend without reducing world-generation determinism or destabilizing the existing OpenCL accelerator.
