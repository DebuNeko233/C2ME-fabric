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

import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

final class MetalNative {

    private static final String METAL_FRAMEWORK = "/System/Library/Frameworks/Metal.framework/Metal";
    private static final String FOUNDATION_FRAMEWORK = "/System/Library/Frameworks/Foundation.framework/Foundation";

    private final SharedLibrary metalLibrary;
    @SuppressWarnings("FieldCanBeLocal")
    private final SharedLibrary foundationLibrary;
    private final long objcMsgSend;
    private final long mtlCreateSystemDefaultDevice;

    private MetalNative(SharedLibrary metalLibrary, SharedLibrary foundationLibrary) {
        this.metalLibrary = metalLibrary;
        this.foundationLibrary = foundationLibrary;
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
        // Loading Foundation explicitly makes NSString/NSAutoreleasePool available
        // even for a headless dedicated server process.
        SharedLibrary foundation = Library.loadNative(MetalNative.class, "c2me-metal", FOUNDATION_FRAMEWORK);
        SharedLibrary metal = Library.loadNative(MetalNative.class, "c2me-metal", METAL_FRAMEWORK);
        return new MetalNative(metal, foundation);
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

    private void sendVoid(long receiver, String selector) {
        JNI.invokePPV(receiver, this.selector(selector), this.objcMsgSend);
    }

}
