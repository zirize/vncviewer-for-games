// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames

import android.graphics.Bitmap
import io.github.zirize.vncviewerforgames.perf.PerfStats
import com.tigervnc.rfb.ModifiablePixelBuffer
import com.tigervnc.rfb.PixelFormat
import com.tigervnc.rfb.Point
import com.tigervnc.rfb.Rect
import awtx.image.WritableRaster
import awtx.image.Raster
import java.nio.ByteBuffer

/**
 * Collects what the decoders write and uploads it to the Android Bitmap **once per frame**.
 *
 * 🔴 **Why it works this way** (measured 2026-09-15)
 * It used to call `Bitmap.setPixels` once per rect. With video, where small rects pour in, that
 * was 4400 JNI round trips and bitmap locks per second — and the cost was **per rect**, not per
 * pixel (conversion 86ms/Mpx versus setPixels 1108ms/Mpx, i.e. pure overhead).
 * ⇒ Rects are written into the [fb] array below, and the Bitmap upload happens once at the end of
 * the frame, covering only the region that changed.
 */
class CustomPixelBuffer(
    pf: PixelFormat,
    private val fbWidth: Int,
    private val fbHeight: Int,
    private val bitmap: Bitmap?
) : ModifiablePixelBuffer(pf, fbWidth, fbHeight) {

    /** The ARGB_8888 framebuffer. Decoder threads write into rects that never overlap each other. */
    private val fb = IntArray(fbWidth * fbHeight)

    // Union of everything that changed this frame
    private var dl = Int.MAX_VALUE
    private var dt = Int.MAX_VALUE
    private var dr = -1
    private var db = -1

    /**
     * Scratch buffer for converting a rect. 🔑 ThreadLocal because DecodeManager calls imageRect
     * from four threads at once; a shared one would have them overwrite each other.
     */
    private val scratch = ThreadLocal.withInitial { ByteArray(0) }
    private val scratchPix = ThreadLocal.withInitial { IntArray(0) }

    private fun scratchOf(n: Int): ByteArray {
        var a = scratch.get() ?: ByteArray(0)
        if (a.size < n) { a = ByteArray(n); scratch.set(a) }
        return a
    }

    private fun scratchPixOf(n: Int): IntArray {
        var a = scratchPix.get() ?: IntArray(0)
        if (a.size < n) { a = IntArray(n); scratchPix.set(a) }
        return a
    }

    /** Is the source format one that already reads as 0x00RRGGBB? */
    private fun isFastBgrx32(f: PixelFormat): Boolean =
        f.bpp == 32 && !f.bigEndian && f.trueColour &&
            f.redMax == 255 && f.greenMax == 255 && f.blueMax == 255 &&
            f.redShift == 16 && f.greenShift == 8 && f.blueShift == 0

    @Synchronized private fun markDirty(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 < dl) dl = x0
        if (y0 < dt) dt = y0
        if (x1 > dr) dr = x1
        if (y1 > db) db = y1
    }

    /**
     * Called **once** at the end of a frame; uploads only the changed region to the Bitmap.
     * 🔴 Must be called after every decoder has finished (CConnection calls decoder.flush() first).
     */
    fun commitFrame() {
        val bmp = bitmap ?: return
        val x: Int; val y: Int; val w: Int; val h: Int
        synchronized(this) {
            if (dr < 0 || db < 0) return
            x = dl.coerceIn(0, fbWidth)
            y = dt.coerceIn(0, fbHeight)
            w = dr.coerceIn(0, fbWidth) - x
            h = db.coerceIn(0, fbHeight) - y
            dl = Int.MAX_VALUE; dt = Int.MAX_VALUE; dr = -1; db = -1
        }
        if (w <= 0 || h <= 0) return
        val t0 = System.nanoTime()
        synchronized(bmp) {
            bmp.setPixels(fb, y * fbWidth + x, fbWidth, x, y, w, h)
        }
        PerfStats.commit(w * h, System.nanoTime() - t0)
    }

    private val jpegDecoder = JpegDecoder()

    /** ⚠️ JPEG now lands in [fb]. No path should touch this Bitmap directly. */
    override fun getAndroidBitmap(): Bitmap? = bitmap

    /** Decodes a JPEG rect straight into its place in the framebuffer (libjpeg-turbo, NEON). */
    override fun decodeJpegRect(src: ByteArray?, len: Int, r: Rect?): Boolean {
        if (src == null || r == null) return false
        val w = r.width()
        val h = r.height()
        if (w <= 0 || h <= 0) return false
        val t0 = System.nanoTime()
        val ok = jpegDecoder.decodeJpegToFramebuffer(
            src, len, fb, fbWidth, fbHeight, r.tl.x, r.tl.y, w, h
        )
        if (ok) {
            markDirty(r.tl.x, r.tl.y, r.br.x, r.br.y)
            PerfStats.pixelConv(w * h, System.nanoTime() - t0)
        }
        return ok
    }

    override fun getBuffer(r: Rect?): Raster? = null
    override fun getBufferRW(r: Rect?): WritableRaster? = null
    override fun commitBufferRW(r: Rect?) {}
    override fun maskRect(r: Rect?, pixels: Any?, mask_: ByteArray?) {}
    override fun maskRect(r: Rect?, pixel: Int, mask: ByteArray?) {}
    override fun imageRect(pf: PixelFormat?, dest: Rect?, pixels: Raster?) {}

    override fun fillRect(pf: PixelFormat?, dest: Rect?, pix: ByteArray?) {
        if (dest == null || pix == null) return
        val format = pf ?: getPF()
        val bpp = format.bpp / 8
        if (bpp <= 0 || pix.size < bpp) return

        val rgb = ByteArray(3)
        format.rgbFromBuffer(ByteBuffer.wrap(rgb), ByteBuffer.wrap(pix, 0, bpp), 1)
        val color = (0xFF shl 24) or
            ((rgb[0].toInt() and 0xFF) shl 16) or
            ((rgb[1].toInt() and 0xFF) shl 8) or
            (rgb[2].toInt() and 0xFF)

        val x0 = dest.tl.x.coerceIn(0, fbWidth)
        val y0 = dest.tl.y.coerceIn(0, fbHeight)
        val x1 = dest.br.x.coerceIn(0, fbWidth)
        val y1 = dest.br.y.coerceIn(0, fbHeight)
        if (x1 <= x0 || y1 <= y0) return

        for (row in y0 until y1) {
            val base = row * fbWidth
            java.util.Arrays.fill(fb, base + x0, base + x1, color)
        }
        markDirty(x0, y0, x1, y1)
    }

    override fun imageRect(pf: PixelFormat?, dest: Rect?, pixels: ByteArray?) {
        if (dest == null || pixels == null) return
        val format = pf ?: getPF()
        val w = dest.width()
        val h = dest.height()
        if (w <= 0 || h <= 0) return
        val bpp = format.bpp / 8
        val need = w * h * bpp
        if (bpp <= 0 || pixels.size < need) return

        val x0 = dest.tl.x
        val y0 = dest.tl.y
        if (x0 < 0 || y0 < 0 || x0 + w > fbWidth || y0 + h > fbHeight) return

        val t0 = System.nanoTime()

        // ── Fast path ──────────────────────────────────────────────────
        // 🔑 With the 32bpp little-endian truecolour format we negotiate (shifts 16/8/0), source
        //    pixels are already 0x00RRGGBB. Only alpha has to be added, which makes both
        //    rgbFromBuffer's general conversion and its intermediate byte array unnecessary.
        if (isFastBgrx32(format)) {
            var s = 0
            for (row in 0 until h) {
                var di = (y0 + row) * fbWidth + x0
                val end = di + w
                while (di < end) {
                    fb[di] = (0xFF shl 24) or
                        ((pixels[s + 2].toInt() and 0xFF) shl 16) or
                        ((pixels[s + 1].toInt() and 0xFF) shl 8) or
                        (pixels[s].toInt() and 0xFF)
                    s += 4; di++
                }
            }
            markDirty(x0, y0, dest.br.x, dest.br.y)
            PerfStats.pixelConv(w * h, System.nanoTime() - t0)
            return
        }

        val rgb = scratchOf(w * h * 3)
        format.rgbFromBuffer(ByteBuffer.wrap(rgb), ByteBuffer.wrap(pixels, 0, need), w, w, h)

        // 🔑 Convert into a dense scratch array first.
        // Writing straight into the framebuffer, skipping between rows, wrecks cache locality and
        // is slower (measured 2026-09-15: 55ms/Mpx became 78ms/Mpx).
        // So: convert densely, then move it row by row with arraycopy.
        val tmp = scratchPixOf(w * h)
        var src = 0
        val n = w * h
        var i = 0
        while (i < n) {
            tmp[i] = (0xFF shl 24) or
                ((rgb[src].toInt() and 0xFF) shl 16) or
                ((rgb[src + 1].toInt() and 0xFF) shl 8) or
                (rgb[src + 2].toInt() and 0xFF)
            src += 3; i++
        }
        for (row in 0 until h)
            System.arraycopy(tmp, row * w, fb, (y0 + row) * fbWidth + x0, w)
        markDirty(x0, y0, dest.br.x, dest.br.y)
        PerfStats.pixelConv(w * h, System.nanoTime() - t0)
    }

    override fun fillRect(r: Rect?, pix: ByteArray?) = fillRect(getPF(), r, pix)
    override fun imageRect(r: Rect?, pixels: ByteArray?) = imageRect(getPF(), r, pixels)

    override fun copyRect(rect: Rect?, move_by_delta: Point?) {
        if (rect == null || move_by_delta == null) return
        val dx = move_by_delta.x
        val dy = move_by_delta.y
        val x0 = rect.tl.x.coerceIn(0, fbWidth)
        val y0 = rect.tl.y.coerceIn(0, fbHeight)
        val x1 = rect.br.x.coerceIn(0, fbWidth)
        val y1 = rect.br.y.coerceIn(0, fbHeight)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return
        val sx = x0 - dx
        val sy = y0 - dy
        if (sx < 0 || sy < 0 || sx + w > fbWidth || sy + h > fbHeight) return

        // 🔑 The regions can overlap, so the copy direction decides the order.
        if (sy < y0) {
            for (row in h - 1 downTo 0)
                System.arraycopy(fb, (sy + row) * fbWidth + sx, fb, (y0 + row) * fbWidth + x0, w)
        } else {
            for (row in 0 until h)
                System.arraycopy(fb, (sy + row) * fbWidth + sx, fb, (y0 + row) * fbWidth + x0, w)
        }
        markDirty(x0, y0, x1, y1)
    }
}
