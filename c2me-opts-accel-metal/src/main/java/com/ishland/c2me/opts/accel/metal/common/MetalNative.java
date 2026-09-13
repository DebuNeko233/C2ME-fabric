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

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.APIUtil.apiCreateStruct;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.POINTER_SIZE;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_ulong;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;

final class MetalNative {

    private static final String METAL_FRAMEWORK = "/System/Library/Frameworks/Metal.framework/Metal";
    private static final String FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/Foundation.framework/Foundation";
    private static final String CORE_GRAPHICS_FRAMEWORK = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";

    // Objective-C methods that take MTLSize by value cannot be expressed by the
    // fixed JNI call signatures exposed by LWJGL. LibFFI provides the exact ABI
    // required by objc_msgSend without adding a custom JNI library.
    private static final FFIType MTL_SIZE_TYPE = apiCreateStruct(ffi_type_ulong, ffi_type_ulong, ffi_type_ulong);
    private static final FFICIF SET_BUFFER_CIF = apiCreateCIF(
            ffi_type_void,
            ffi_type_pointer,
            ffi_type_pointer,
            ffi_type_pointer,
            ffi_type_ulong,
            ffi_type_ulong
    );
    private static final FFICIF DISPATCH_THREADGROUPS_CIF = apiCreateCIF(
            ffi_type_void,
            ffi_type_pointer,
            ffi_type_pointer,
            MTL_SIZE_TYPE,
            MTL_SIZE_TYPE
    );

    private final SharedLibrary metalLibrary;
    @SuppressWarnings("FieldCanBeLocal")
    private final SharedLibrary foundationLibrary;
    @SuppressWarnings("FieldCanBeLocal")
    private final SharedLibrary coreGraphicsLibrary;
    private final long objcMsgSend;
    private final long mtlCreateSystemDefaultDevice;

    private MetalNative(SharedLibrary metalLibrary, SharedLibrary foundationLibrary, SharedLibrary coreGraphicsLibrary) {
        this.metalLibrary = metalLibrary;
        this.foundationLibrary = foundationLibrary;
        this.coreGraphicsLibrary = coreGraphicsLibrary;
        this.objcMsgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        this.mtlCreateSystemDefaultDevice = this.metalLibrary.getFunctionAddress("MTLCreateSystemDefaultDevice");

        if (this.objcMsgSend == NULL) {
            throw new UnsatisfiedLinkError("objc_msgSend is unavailable");
        }
        if (this.mtlCreateSystemDefaultDevice == NULL) {
            throw new UnsatisfiedLinkError("MTLCreateSystemDefaultDevice is unavailable");
        }
    }

    static MetalNative load() {
        // Foundation provides NSString/NSAutoreleasePool. Apple documents that
        // CoreGraphics must be linked explicitly for command-line/headless macOS
        // processes before MTLCreateSystemDefaultDevice can return a default GPU.
        SharedLibrary foundation = Library.loadNative(MetalNative.class, "c2me-metal", FOUNDATION_FRAMEWORK);
        SharedLibrary coreGraphics = Library.loadNative(MetalNative.class, "c2me-metal", CORE_GRAPHICS_FRAMEWORK);
        SharedLibrary metal = Library.loadNative(MetalNative.class, "c2me-metal", METAL_FRAMEWORK);
        return new MetalNative(metal, foundation, coreGraphics);
    }

    long createSystemDefaultDevice() {
        return JNI.invokeP(this.mtlCreateSystemDefaultDevice);
    }

    long newCommandQueue(long device) {
        return this.sendPointer(device, "newCommandQueue");
    }

    String getDeviceName(long device) {
        long nsName = this.sendPointer(device, "name");
        if (nsName == NULL) {
            return "Unknown Metal device";
        }
        long utf8 = this.sendPointer(nsName, "UTF8String");
        String name = MemoryUtil.memUTF8Safe(utf8);
        return name != null ? name : "Unknown Metal device";
    }

    long compileProbePipeline(long device) {
        long pool = this.newAutoreleasePool();
        long library = NULL;
        long function = NULL;
        try {
            long source = this.newNSString("""
                    #include <metal_stdlib>
                    using namespace metal;

                    kernel void c2me_metal_probe(device uint *output [[buffer(0)]],
                                                 uint gid [[thread_position_in_grid]]) {
                        if (gid == 0) {
                            output[0] = 0xC2u;
                        }
                    }
                    """);
            library = this.sendPointer(device, "newLibraryWithSource:options:error:", source, NULL, NULL);
            if (library == NULL) {
                return NULL;
            }

            long functionName = this.newNSString("c2me_metal_probe");
            function = this.sendPointer(library, "newFunctionWithName:", functionName);
            if (function == NULL) {
                return NULL;
            }

            return this.sendPointer(device, "newComputePipelineStateWithFunction:error:", function, NULL);
        } finally {
            if (function != NULL) {
                this.release(function);
            }
            if (library != NULL) {
                this.release(library);
            }
            if (pool != NULL) {
                this.sendVoid(pool, "drain");
            }
        }
    }

    boolean executeProbe(long device, long commandQueue, long pipeline) {
        long pool = this.newAutoreleasePool();
        long output = NULL;
        try {
            // MTLResourceStorageModeShared is encoded as zero in MTLResourceOptions.
            // Waiting for command completion provides the required CPU/GPU ordering
            // before the shared buffer is read back below.
            output = this.sendPointerNUIntNUInt(device, "newBufferWithLength:options:", Integer.BYTES, 0L);
            if (output == NULL) {
                return false;
            }

            long contents = this.sendPointer(output, "contents");
            if (contents == NULL) {
                return false;
            }
            memPutInt(contents, 0);

            long commandBuffer = this.sendPointer(commandQueue, "commandBuffer");
            if (commandBuffer == NULL) {
                return false;
            }
            long encoder = this.sendPointer(commandBuffer, "computeCommandEncoder");
            if (encoder == NULL) {
                return false;
            }

            this.sendVoidPointer(encoder, "setComputePipelineState:", pipeline);
            this.sendVoidPointerNUIntNUInt(encoder, "setBuffer:offset:atIndex:", output, 0L, 0L);
            this.dispatchThreadgroups(encoder, 1L, 1L, 1L, 1L, 1L, 1L);
            this.sendVoid(encoder, "endEncoding");
            this.sendVoid(commandBuffer, "commit");
            this.sendVoid(commandBuffer, "waitUntilCompleted");

            return MemoryUtil.memGetInt(contents) == 0xC2;
        } finally {
            if (output != NULL) {
                this.release(output);
            }
            if (pool != NULL) {
                this.sendVoid(pool, "drain");
            }
        }
    }

    private long newAutoreleasePool() {
        long clazz = ObjCRuntime.objc_getClass("NSAutoreleasePool");
        if (clazz == NULL) {
            return NULL;
        }
        long allocated = this.sendPointer(clazz, "alloc");
        return allocated != NULL ? this.sendPointer(allocated, "init") : NULL;
    }

    private long newNSString(String value) {
        long clazz = ObjCRuntime.objc_getClass("NSString");
        if (clazz == NULL) {
            throw new IllegalStateException("NSString class is unavailable");
        }

        ByteBuffer utf8 = MemoryUtil.memUTF8(value);
        try {
            return this.sendPointer(clazz, "stringWithUTF8String:", MemoryUtil.memAddress(utf8));
        } finally {
            MemoryUtil.memFree(utf8);
        }
    }

    private void release(long object) {
        this.sendVoid(object, "release");
    }

    private long selector(String name) {
        return ObjCRuntime.sel_registerName(name);
    }

    private long sendPointer(long receiver, String selector) {
        return JNI.invokePPP(receiver, this.selector(selector), this.objcMsgSend);
    }

    private long sendPointer(long receiver, String selector, long arg0) {
        return JNI.invokePPPP(receiver, this.selector(selector), arg0, this.objcMsgSend);
    }

    private long sendPointer(long receiver, String selector, long arg0, long arg1) {
        return JNI.invokePPPPP(receiver, this.selector(selector), arg0, arg1, this.objcMsgSend);
    }

    private long sendPointer(long receiver, String selector, long arg0, long arg1, long arg2) {
        return JNI.invokePPPPPP(receiver, this.selector(selector), arg0, arg1, arg2, this.objcMsgSend);
    }

    private long sendPointerNUIntNUInt(long receiver, String selector, long arg0, long arg1) {
        return JNI.invokePPNNP(receiver, this.selector(selector), arg0, arg1, this.objcMsgSend);
    }

    private void sendVoid(long receiver, String selector) {
        JNI.invokePPV(receiver, this.selector(selector), this.objcMsgSend);
    }

    private void sendVoidPointer(long receiver, String selector, long arg0) {
        JNI.invokePPPV(receiver, this.selector(selector), arg0, this.objcMsgSend);
    }

    private void sendVoidPointerNUIntNUInt(long receiver, String selector, long pointer, long value0, long value1) {
        try (MemoryStack stack = stackPush()) {
            long receiverStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long selectorStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long pointerStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long value0Storage = stack.nmalloc(Long.BYTES, Long.BYTES);
            long value1Storage = stack.nmalloc(Long.BYTES, Long.BYTES);

            memPutAddress(receiverStorage, receiver);
            memPutAddress(selectorStorage, this.selector(selector));
            memPutAddress(pointerStorage, pointer);
            memPutLong(value0Storage, value0);
            memPutLong(value1Storage, value1);

            PointerBuffer arguments = stack.mallocPointer(5);
            arguments.put(0, receiverStorage);
            arguments.put(1, selectorStorage);
            arguments.put(2, pointerStorage);
            arguments.put(3, value0Storage);
            arguments.put(4, value1Storage);

            LibFFI.ffi_call(SET_BUFFER_CIF, this.objcMsgSend, null, arguments);
        }
    }

    private void dispatchThreadgroups(
            long encoder,
            long groupsX,
            long groupsY,
            long groupsZ,
            long threadsX,
            long threadsY,
            long threadsZ
    ) {
        try (MemoryStack stack = stackPush()) {
            long receiverStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long selectorStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long groupsStorage = stack.nmalloc(Long.BYTES, Long.BYTES * 3);
            long threadsStorage = stack.nmalloc(Long.BYTES, Long.BYTES * 3);

            memPutAddress(receiverStorage, encoder);
            memPutAddress(selectorStorage, this.selector("dispatchThreadgroups:threadsPerThreadgroup:"));
            memPutLong(groupsStorage, groupsX);
            memPutLong(groupsStorage + Long.BYTES, groupsY);
            memPutLong(groupsStorage + Long.BYTES * 2L, groupsZ);
            memPutLong(threadsStorage, threadsX);
            memPutLong(threadsStorage + Long.BYTES, threadsY);
            memPutLong(threadsStorage + Long.BYTES * 2L, threadsZ);

            PointerBuffer arguments = stack.mallocPointer(4);
            arguments.put(0, receiverStorage);
            arguments.put(1, selectorStorage);
            arguments.put(2, groupsStorage);
            arguments.put(3, threadsStorage);

            LibFFI.ffi_call(DISPATCH_THREADGROUPS_CIF, this.objcMsgSend, null, arguments);
        }
    }

}
