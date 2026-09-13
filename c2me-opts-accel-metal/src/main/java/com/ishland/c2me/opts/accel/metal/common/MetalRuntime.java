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

import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.system.MemoryUtil.NULL;

public final class MetalRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetalRuntime.class);

    private static volatile State state = State.UNINITIALIZED;
    private static MetalNative nativeApi;
    private static long device = NULL;
    private static long commandQueue = NULL;
    private static long probePipeline = NULL;
    private static String deviceName;

    private MetalRuntime() {
    }

    public static synchronized void initialize() {
        if (state != State.UNINITIALIZED) {
            return;
        }

        if (Platform.get() != Platform.MACOSX) {
            state = State.UNSUPPORTED_PLATFORM;
            LOGGER.info("Metal acceleration backend is only available on macOS; keeping the normal C2ME path");
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

            probePipeline = nativeApi.compileProbePipeline(device);
            if (probePipeline == NULL) {
                throw new IllegalStateException("Metal failed to compile the C2ME compute probe pipeline");
            }

            state = State.AVAILABLE;
            LOGGER.info("Metal backend initialized on '{}' (MSL compute pipeline verified)", deviceName);
        } catch (Throwable t) {
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

    public enum State {
        UNINITIALIZED,
        UNSUPPORTED_PLATFORM,
        NO_DEVICE,
        AVAILABLE,
        FAILED,
    }

}
