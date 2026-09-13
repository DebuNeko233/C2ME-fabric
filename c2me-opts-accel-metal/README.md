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
- the first DFC F32-to-MSL code-generation path (`ConstantF32Node`);
- a backend-neutral `GeneratedProgramMetadata` container shared with the existing OpenCL generated-program result;
- an explicit host/exact F64 -> F32 boundary path that prevents Metal from recursively lowering F64 density functions to float;
- startup validation that exercises DFC -> MSL -> Metal, 257-element batching, pipeline reuse, buffer reuse, and the explicit F32 boundary.

The runtime bridge uses LWJGL's Objective-C/JNI helpers and its bundled LibFFI support for Objective-C calls that pass `MTLSize` structures by value. It does not require an extra JNI dylib and does not require Java FFM native-access flags.

The backend is marked available only after generated probes are compiled, dispatched on the GPU, completed and read back with the expected IEEE-754 bits. A failure at any stage leaves the normal C2ME path active.

`metalAccel.enabled` is intentionally **disabled by default** at this stage. Enabling it currently validates the Metal compiler/runtime/compute path only; it does not redirect chunk generation yet.

## F32 capability and boundary policy

Metal does not silently reinterpret C2ME's F64 DensityFunction graph as F32. `MetalCompatibility` requires every directly compiled AST node to be explicitly F32 in DFC and to have an audited MSL emitter. Any F64 or unsupported subtree is rejected before direct Metal code generation.

The initial AST emitter intentionally supports only `ConstantF32Node`. Float constants are emitted from their raw IEEE-754 bit pattern with MSL `as_type<float>` instead of decimal literals, avoiding an extra text-parsing rounding step and preserving values such as signed zero exactly.

C2ME's existing OpenCL spline path demonstrates an important mixed-precision boundary: the spline location function is evaluated as F64, then explicitly cast to float, and the subsequent spline math is F32. `ToF32Node` therefore must not be retyped just to make it easier for Metal to consume. The Metal backend instead has an explicit boundary program: exact/F64 work stays on the host/exact path, the host performs the required Java/IEEE-754 float rounding, and only those already-rounded raw F32 values are handed to Metal.

The startup probe currently verifies 257 host-rounded values across that boundary and requires bit-preserving GPU readback. This establishes the transport/conversion contract needed by a future hybrid spline backend without claiming that arbitrary F32 arithmetic is already output-identical across CPU, OpenCL and Metal.

## Floating-point compilation policy

Metal library compilation uses `MTLCompileOptions` with `fastMathEnabled = false`. On current Metal versions this selects safe math mode and precise FP32 math functions; it also provides a broadly compatible way to disable the older fast-math path on previous macOS releases.

This is necessary but not considered sufficient proof of world-generation determinism. Any arithmetic-heavy workload added later still needs CPU/OpenCL-vs-Metal differential testing before it can be used for real chunk generation.

## Shared generated-program ABI

The OpenCL result object now stores its backend-independent execution data in `GeneratedProgramMetadata` while retaining the existing OpenCL constructor and getters. The shared metadata currently covers:

- constant data;
- global dynamic-data offsets;
- flat-cache, cache2d and interpolator prefill counts;
- generated defines;
- biome mappings.

Shader source, backend-specific debug artifacts and native pipeline/device objects remain backend-specific. `GeneratedMetalSource` carries the same metadata type so Metal can adopt the existing world-generation ABI incrementally instead of cloning it.

## Runtime batching and reuse

`MetalPipelineCache` retains compiled compute pipelines by generated source and entry point. `MetalSharedBufferPool` reuses CPU-visible shared buffers in bounded power-of-two buckets. `MetalBatchExecutor` uses both for generated F32 batches and explicit boundary batches.

The startup validation intentionally dispatches the same 257-element workload twice and requires the second execution to reuse both the compiled pipeline and the existing shared-buffer allocation. This keeps MSL compilation and Objective-C allocation out of the eventual hot world-generation loop.

## World-generation integration status

This milestone deliberately does **not** redirect chunk generation yet. The existing OpenCL accelerator contains a substantial DensityFunction-to-OpenCL compiler, cache-prefill kernels, device scheduling and chunk-system integration. Those pieces need a Metal-aware implementation rather than a blind OpenCL-C-to-MSL text conversion.

The largest compatibility issue remains mixed floating-point semantics: the existing accelerator emits both F32 and F64 density-function work, while Metal Shading Language does not provide a straightforward native F64 shader path comparable to the OpenCL implementation. The current direction is therefore hybrid: preserve exact/F64 islands and their explicit F64 -> F32 conversion points, then offload only audited F32 work.

## CI

`.github/workflows/metal-build.yml` can run on pull requests, pushes and manual dispatch. It checks the shared DFC/OpenCL/Metal Java ABI on Ubuntu and compiles the Metal module on both `macos-15` (Apple Silicon) and `macos-15-intel` using JDK 25.

The PR should remain Draft until these jobs pass and a real macOS runtime test verifies the native probe path.

## Next implementation stages

1. Model the hybrid F32 spline plan with explicit host-computed boundary inputs instead of recursively compiling F64 location functions.
2. Port the audited F32 spline math only after matching the current OpenCL operation ordering and control-flow semantics.
3. Add CPU/OpenCL-vs-Metal differential tests for spline interpolation and other candidate F32 workloads.
4. Identify and benchmark a profitable real world-generation F32 island; reject workloads where host/GPU transfer and synchronization cost outweigh compute savings.
5. Integrate the first validated workload behind capability checks and the default-off configuration.
6. Only enable Metal world-generation dispatch by default after output compatibility and performance are proven on real Intel and Apple Silicon Macs.

The design goal is to make Metal a first-class macOS backend without reducing world-generation determinism or destabilizing the existing OpenCL accelerator.
