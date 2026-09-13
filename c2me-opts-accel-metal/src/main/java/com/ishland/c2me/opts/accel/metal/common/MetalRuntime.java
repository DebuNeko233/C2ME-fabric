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

import com.ishland.c2me.opts.accel.metal.common.compiler.GeneratedMetalSource;
import com.ishland.c2me.opts.accel.metal.common.compiler.MetalF32Compiler;
import com.ishland.c2me.opts.dfc.common.ast.misc.ConstantF32Node;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.system.MemoryUtil.NULL;

public final class MetalRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetalRuntime.class);
    private static final int COMPILER_PROBE_BITS = 0x3EAAAAAB; // nearest F32 representation of 1/3
    private static final int COMPILER_PROBE_BATCH_SIZE = 257;

    private static volatile State state = State.UNINITIALIZED;
    private static MetalNative nativeApi;
    private static long device = NULL;
    private static long commandQueue = NULL;
    private static MetalBatchExecutor batchExecutor;
    private static String deviceName;

    private MetalRuntime() {
    }

    public static synchronized void initialize() {
        if (state != State.UNINITIALIZED) {
            return;
        }

        if (Platform.get() != Platform.MACOSX) {
            state = State.UNSUPPORTED_PLATFORM;
            LOGGER.debug("Metal acceleration backend is only available on macOS; keeping the normal C2ME path");
            return;
        }

        try {
            nativeApi = MetalNative.load();
            device = nativeApi.createSystemDefaultDevice();
            if (device == NULL) {
                state = State.NO_DEVICE;
                LOGGER.warn("Metal framework loaded, but no default Metal device is available; keeping the normal C2ME path");
                return;
            }

            deviceName = nativeApi.getDeviceName(device);
            commandQueue = nativeApi.newCommandQueue(device);
            if (commandQueue == NULL) {
                throw new IllegalStateException("Metal device did not create a command queue");
            }
            batchExecutor = new MetalBatchExecutor(nativeApi, device, commandQueue);

            float compilerProbeValue = Float.intBitsToFloat(COMPILER_PROBE_BITS);
            GeneratedMetalSource generatedProbe = MetalF32Compiler.compile(new ConstantF32Node(compilerProbeValue));

            // First dispatch compiles the pipeline and allocates a shared buffer.
            validateProbeBatch(batchExecutor.executeF32Bits(generatedProbe, COMPILER_PROBE_BATCH_SIZE));
            int pipelinesAfterFirstDispatch = batchExecutor.cachedPipelineCount();
            int buffersAfterFirstDispatch = batchExecutor.allocatedBufferCount();

            // The second identically sized dispatch must hit both caches. This
            // validates the allocation strategy before real worldgen is allowed
            // to depend on it.
            validateProbeBatch(batchExecutor.executeF32Bits(generatedProbe, COMPILER_PROBE_BATCH_SIZE));
            if (pipelinesAfterFirstDispatch != 1 || batchExecutor.cachedPipelineCount() != 1) {
                throw new IllegalStateException("Metal compute pipeline cache did not reuse the generated probe pipeline");
            }
            if (buffersAfterFirstDispatch != 1 || batchExecutor.allocatedBufferCount() != 1) {
                throw new IllegalStateException("Metal shared-buffer pool did not reuse the probe allocation");
            }

            state = State.AVAILABLE;
            LOGGER.info(
                    "Metal backend initialized on '{}' (DFC F32 -> MSL, {}-element batch dispatch, bit-exact readback, pipeline/buffer reuse verified)",
                    deviceName,
                    COMPILER_PROBE_BATCH_SIZE
            );
        } catch (Throwable t) {
            cleanupAfterFailedInitialization();
            state = State.FAILED;
            LOGGER.warn("Failed to initialize the experimental Metal backend; keeping the normal C2ME path", t);
        }
    }

    public static boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    public static State getState() {
        return state;
    }

    public static String getDeviceName() {
        return deviceName;
    }

    private static void validateProbeBatch(int[] resultBits) {
        if (resultBits.length != COMPILER_PROBE_BATCH_SIZE) {
            throw new IllegalStateException("Metal DFC F32 probe returned an unexpected batch length");
        }
        for (int i = 0; i < resultBits.length; i++) {
            if (resultBits[i] != COMPILER_PROBE_BITS) {
                throw new IllegalStateException("Metal DFC F32 compute probe mismatch at element " + i
                        + ": expected 0x" + Integer.toHexString(COMPILER_PROBE_BITS)
                        + ", got 0x" + Integer.toHexString(resultBits[i]));
            }
        }
    }

    private static void cleanupAfterFailedInitialization() {
        if (batchExecutor != null) {
            batchExecutor.close();
            batchExecutor = null;
        }
        if (nativeApi != null && commandQueue != NULL) {
            nativeApi.releaseObject(commandQueue);
            commandQueue = NULL;
        }
    }

    public enum State {
        UNINITIALIZED,
        UNSUPPORTED_PLATFORM,
        NO_DEVICE,
        AVAILABLE,
        FAILED,
    }

}
