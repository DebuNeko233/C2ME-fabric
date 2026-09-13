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
import java.util.Objects;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.APIUtil.apiCreateStruct;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.POINTER_SIZE;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_ulong;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;

final class MetalNative {

    private static final String METAL_FRAMEWORK = "/System/Library/Frameworks/Metal.framework/Metal";
    private static final String FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/Foundation.framework/Foundation";
    private static final String CORE_GRAPHICS_FRAMEWORK = "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics";
    private static final long MTL_COMMAND_BUFFER_STATUS_COMPLETED = 4L;

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
    private static final FFICIF DISPATCH_GRID_CIF = apiCreateCIF(
            ffi_type_void,
            ffi_type_pointer,
            ffi_type_pointer,
            MTL_SIZE_TYPE,
            MTL_SIZE_TYPE
    );
    private static final FFICIF RETURN_NSUINTEGER_CIF = apiCreateCIF(
            ffi_type_ulong,
            ffi_type_pointer,
            ffi_type_pointer
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

    long compilePipeline(long device, String sourceCode, String entryPoint) {
        Objects.requireNonNull(sourceCode, "sourceCode");
        Objects.requireNonNull(entryPoint, "entryPoint");

        long pool = this.newAutoreleasePool();
        long library = NULL;
        long function = NULL;
        try {
            long source = this.newNSString(sourceCode);
            library = this.sendPointer(device, "newLibraryWithSource:options:error:", source, NULL, NULL);
            if (library == NULL) {
                return NULL;
            }

            long functionName = this.newNSString(entryPoint);
            function = this.sendPointer(library, "newFunctionWithName:", functionName);
            if (function == NULL) {
                return NULL;
            }

            return this.sendPointer(device, "newComputePipelineStateWithFunction:error:", function, NULL);
        } finally {
            if (function != NULL) {
                this.releaseObject(function);
            }
            if (library != NULL) {
                this.releaseObject(library);
            }
            if (pool != NULL) {
                this.sendVoid(pool, "drain");
            }
        }
    }

    long newSharedBuffer(long device, long length) {
        if (length <= 0L) {
            throw new IllegalArgumentException("Metal buffer length must be positive");
        }
        // MTLResourceStorageModeShared is encoded as zero in MTLResourceOptions.
        return this.sendPointerNUIntNUInt(device, "newBufferWithLength:options:", length, 0L);
    }

    long getBufferContents(long buffer) {
        return this.sendPointer(buffer, "contents");
    }

    boolean execute1DBatch(long commandQueue, long pipeline, long outputBuffer, long elementCount) {
        if (elementCount < 0L) {
            throw new IllegalArgumentException("Metal dispatch element count must be non-negative");
        }
        if (elementCount == 0L) {
            return true;
        }

        long pool = this.newAutoreleasePool();
        try {
            long commandBuffer = this.sendPointer(commandQueue, "commandBuffer");
            if (commandBuffer == NULL) {
                return false;
            }
            long encoder = this.sendPointer(commandBuffer, "computeCommandEncoder");
            if (encoder == NULL) {
                return false;
            }

            this.sendVoidPointer(encoder, "setComputePipelineState:", pipeline);
            this.sendVoidPointerNUIntNUInt(encoder, "setBuffer:offset:atIndex:", outputBuffer, 0L, 0L);

            long executionWidth = Math.max(1L, this.sendNUInteger(pipeline, "threadExecutionWidth"));
            long maxThreads = Math.max(1L, this.sendNUInteger(pipeline, "maxTotalThreadsPerThreadgroup"));
            long threadsPerGroup = Math.min(elementCount, Math.min(executionWidth, maxThreads));
            this.dispatchThreads(encoder, elementCount, 1L, 1L, threadsPerGroup, 1L, 1L);

            this.sendVoid(encoder, "endEncoding");
            this.sendVoid(commandBuffer, "commit");
            this.sendVoid(commandBuffer, "waitUntilCompleted");

            return this.sendNUInteger(commandBuffer, "status") == MTL_COMMAND_BUFFER_STATUS_COMPLETED;
        } finally {
            if (pool != NULL) {
                this.sendVoid(pool, "drain");
            }
        }
    }

    void releaseObject(long object) {
        if (object != NULL) {
            this.sendVoid(object, "release");
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

    private long sendNUInteger(long receiver, String selector) {
        try (MemoryStack stack = stackPush()) {
            long receiverStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long selectorStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            memPutAddress(receiverStorage, receiver);
            memPutAddress(selectorStorage, this.selector(selector));

            PointerBuffer arguments = stack.mallocPointer(2);
            arguments.put(0, receiverStorage);
            arguments.put(1, selectorStorage);

            ByteBuffer result = stack.malloc(Long.BYTES);
            LibFFI.ffi_call(RETURN_NSUINTEGER_CIF, this.objcMsgSend, result, arguments);
            return MemoryUtil.memGetLong(MemoryUtil.memAddress(result));
        }
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

    private void dispatchThreads(
            long encoder,
            long gridX,
            long gridY,
            long gridZ,
            long threadsX,
            long threadsY,
            long threadsZ
    ) {
        try (MemoryStack stack = stackPush()) {
            long receiverStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long selectorStorage = stack.nmalloc(POINTER_SIZE, POINTER_SIZE);
            long gridStorage = stack.nmalloc(Long.BYTES, Long.BYTES * 3);
            long threadsStorage = stack.nmalloc(Long.BYTES, Long.BYTES * 3);

            memPutAddress(receiverStorage, encoder);
            memPutAddress(selectorStorage, this.selector("dispatchThreads:threadsPerThreadgroup:"));
            memPutLong(gridStorage, gridX);
            memPutLong(gridStorage + Long.BYTES, gridY);
            memPutLong(gridStorage + Long.BYTES * 2L, gridZ);
            memPutLong(threadsStorage, threadsX);
            memPutLong(threadsStorage + Long.BYTES, threadsY);
            memPutLong(threadsStorage + Long.BYTES * 2L, threadsZ);

            PointerBuffer arguments = stack.mallocPointer(4);
            arguments.put(0, receiverStorage);
            arguments.put(1, selectorStorage);
            arguments.put(2, gridStorage);
            arguments.put(3, threadsStorage);

            LibFFI.ffi_call(DISPATCH_GRID_CIF, this.objcMsgSend, null, arguments);
        }
    }

}
