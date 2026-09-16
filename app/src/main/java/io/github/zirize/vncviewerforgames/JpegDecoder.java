// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames;

public class JpegDecoder {
    static {
        System.loadLibrary("vnc_jni");
    }

    /**
     * Decodes a JPEG straight into the framebuffer at (x,y).
     * fb is an ARGB_8888 int array, fbWidth ints per row.
     */
    public native boolean decodeJpegToFramebuffer(
            byte[] jpegData, int length,
            int[] fb, int fbWidth, int fbHeight,
            int x, int y, int w, int h);
}
