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

`MetalExactBoundaryBatch` now closes the previously missing execution link. It registers each planned F64 producer as a root in `BytecodeGen.Context`, finalizes the normal JVM DFC generated class, and evaluates the resulting `SubCompiledDensityFunction` roots with `EachApplierVanillaInterface`. `AbstractCompiledDensityFunction.fill` therefore reaches the existing generated `IMultiMethod.evalMulti(double[], int[], int[], int[], ...)` path for the entire coordinate batch. Only after that F64 bulk evaluation is complete are values explicitly converted to F32 in slot-major order.

The exact boundary roots intentionally have no blending fallback. A future real chunk-generation integration must preserve the existing no-blending/offload eligibility gate before using this path.

The startup probes still verify the generic explicit F64 -> F32 transport path, and the nested spline probe now uses two real generated F64 boundary producers based on batched coordinates and non-binary scales. Their DFC-JIT results are checked before the Java reference and Metal result are required to match at raw binary32 level for all 257 samples. This validates the implemented spline-island contract without claiming that arbitrary F32 DensityFunction arithmetic is already portable to Metal.

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

The first nontrivial F32 island is now implemented internally, but is not yet connected to chunk generation:

1. `MetalF32SplinePlanner` accepts audited `ConstantF32Node` / `SplineNormalNode` structure and extracts each F64 location producer as an explicit host boundary input.
2. `MetalExactBoundaryBatch` compiles those producer ASTs through the existing JVM DFC generator and evaluates each slot over the complete x/y/z coordinate batch using the generated F64 multi method.
3. The host performs the explicit `(float)` conversion only after exact bulk evaluation and writes the values in slot-major order.
4. `MetalF32SplineReference` evaluates the plan on Java using the same binary range search, outside-range sampling and interpolation expression ordering as the current OpenCL spline runtime/emitter.
5. `MetalF32SplineCompiler` emits the same planned tree as MSL, including nested spline values and raw-bit encoded locations/derivatives.
6. `MetalSplineRuntimeProbe` validates the DFC-produced boundaries, then compares Java-reference and Metal results bit-for-bit across 257 samples while also verifying pipeline/buffer reuse.

The planner does **not** make `SplineNormalNode` generally safe for direct recursive Metal AST compilation. Its F64 location producers remain deliberately outside Metal.

## World-generation integration status

This milestone deliberately does **not** redirect chunk generation yet. The existing OpenCL accelerator contains a substantial DensityFunction-to-OpenCL compiler, cache-prefill kernels, device scheduling and chunk-system integration. Those pieces need a Metal-aware implementation rather than a blind OpenCL-C-to-MSL text conversion.

The largest compatibility issue remains mixed floating-point semantics: the existing accelerator emits both F32 and F64 density-function work, while Metal Shading Language does not provide a straightforward native F64 shader path comparable to the OpenCL implementation. The current direction is therefore hybrid: preserve exact/F64 islands and their explicit F64 -> F32 conversion points, then offload only audited, sufficiently large F32 batches.

The exact F64 bulk execution mechanism is no longer an open question: C2ME's generated `IMultiMethod.evalMulti` path is now reused through `MetalExactBoundaryBatch`. The remaining integration problem is to attach that evaluator to the real 2x2 / 4x4-style world-generation batch, reuse its coordinate/cache context correctly, and feed the resulting slot-major F32 data into a sufficiently large Metal island without introducing extra synchronization.

## CI

`.github/workflows/metal-build.yml` can run on pull requests, pushes and manual dispatch. It checks the shared DFC/OpenCL/Metal Java ABI on Ubuntu and compiles the Metal module on both `macos-15` (Apple Silicon) and `macos-15-intel` using JDK 25.

These CI jobs validate the Java/Gradle build on both macOS architectures; they do **not** by themselves prove that the runtime MSL probes executed on a physical Metal device. Real native runtime evidence is still required before the PR can leave Draft status.

## Next implementation stages

1. Identify the exact existing world-generation batch call site and coordinate/cache context corresponding to the OpenCL 2x2 / 4x4 chunk execution boundary.
2. Bind real world-generation spline plans to `MetalExactBoundaryBatch`, cache/reuse the compiled boundary evaluator at the correct lifetime, and preserve no-blending/offload eligibility checks.
3. Add integration-level CPU/OpenCL-vs-Metal differential tests using real world-generation spline graphs rather than only the synthetic nested startup probe.
4. Benchmark a real chunk/region batch and reject offloads where transfer/synchronization overhead outweighs compute savings.
5. Integrate the first validated real worldgen path behind capability checks and the default-off configuration.
6. Only enable Metal world-generation dispatch by default after output compatibility and performance are proven on real Intel and Apple Silicon Macs.

The design goal is to make Metal a first-class macOS backend without reducing world-generation determinism or destabilizing the existing OpenCL accelerator.
