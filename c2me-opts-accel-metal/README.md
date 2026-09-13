# c2me-opts-accel-metal

Experimental native Metal compute backend for C2ME on macOS.

## Current milestone

The module currently provides the runtime foundation required by a real Metal world-generation backend:

- macOS-only activation with clean fallback on every other platform;
- direct loading of the system Metal, Foundation and CoreGraphics frameworks;
- `MTLCreateSystemDefaultDevice` device discovery;
- Metal command-queue creation;
- runtime Metal Shading Language compilation;
- compute-pipeline creation;
- real compute dispatch through `MTLComputeCommandEncoder`;
- shared-buffer GPU write + CPU readback validation;
- Intel macOS and Apple Silicon LWJGL native packaging;
- a conservative DensityFunction-AST Metal compatibility gate;
- the first DFC F32-to-MSL code-generation path (`ConstantF32Node`);
- startup validation that runs a real DFC node through AST -> MSL -> Metal -> raw-bit readback.

The runtime bridge uses LWJGL's Objective-C/JNI helpers and its bundled LibFFI support for Objective-C calls that pass `MTLSize` structures by value. It does not require an extra JNI dylib and does not require Java FFM native-access flags.

The backend is marked available only after a generated DFC F32 probe is compiled, dispatched on the GPU, completed and read back with the exact expected IEEE-754 bits. A failure at any stage leaves the normal C2ME path active.

`metalAccel.enabled` is intentionally **disabled by default** at this stage. Enabling it currently validates the Metal compiler/runtime/compute path only; it does not redirect chunk generation yet.

## F32 capability policy

Metal does not silently reinterpret C2ME's F64 DensityFunction graph as F32. `MetalCompatibility` requires every accepted node to be explicitly F32 in DFC and to have an audited MSL emitter. Any F64 or unsupported subtree is rejected before Metal code generation and remains on the existing exact path.

The initial emitter intentionally supports only `ConstantF32Node`. Float constants are emitted from their raw IEEE-754 bit pattern with MSL `as_type<float>` instead of decimal literals, avoiding an extra text-parsing rounding step and preserving values such as signed zero exactly.

## World-generation integration status

This milestone deliberately does **not** redirect chunk generation yet. The existing OpenCL accelerator contains a substantial DensityFunction-to-OpenCL compiler, generated constant/dynamic data ABI, cache-prefill kernels, device scheduling and chunk-system integration. Those pieces need a Metal-aware implementation rather than a blind OpenCL-C-to-MSL text conversion.

The largest compatibility issue is floating-point semantics: the existing accelerator emits both F32 and F64 density-function code, while Metal Shading Language does not provide a straightforward native F64 shader path comparable to the existing OpenCL implementation. The Metal backend must therefore keep exactness-sensitive F64 work on an exact path (or implement a validated representation) instead of silently converting world generation to F32 and changing terrain.

## Next implementation stages

1. Extract/share backend-neutral generated-data metadata from the OpenCL compiler.
2. Expand the audited MSL emitter set only for DFC nodes whose actual return type is F32.
3. Add reusable Metal buffers, pipeline caching and batched dispatch.
4. Integrate an initial F32-safe world-generation workload behind capability checks.
5. Add CPU/OpenCL-vs-Metal output-difference tests before widening kernel coverage.
6. Only enable Metal world-generation dispatch when the selected kernel is proven output-compatible.

The design goal is to make Metal a first-class macOS backend without reducing world-generation determinism or destabilizing the existing OpenCL accelerator.
