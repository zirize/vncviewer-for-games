/* Modified in 2026 by Bill Kang for vncviewer-for-games.
 * GPL-2.0 section 2(a) asks modified files to say so; this is that notice.
 *
 * This file was rewritten far enough that its upstream TigerVNC header went
 * missing.  Upstream carries a file of the same name, so it is treated as
 * derived from TigerVNC and the header below has been restored.  The copyright
 * line is the project-wide one from NOTICE, not a per-file record -- the exact
 * upstream revision this came from was never written down.
 */
/* Copyright (C) 2002-2005 RealVNC Ltd.  All Rights Reserved.
 * Copyright (C) 2011-2026 Brian P. Hinz
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */

package com.tigervnc.rfb;

import java.nio.ByteBuffer;

public class JpegDecompressor {

  /**
   * 🔑 A fresh byte[] per rect would mean hundreds to thousands of allocations per second.
   *    One is reused per thread (DecodeManager calls this from four).
   */
  private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<byte[]>() {
    protected byte[] initialValue() { return new byte[0]; }
  };

  private static byte[] scratch(int n) {
    byte[] a = SCRATCH.get();
    if (a.length < n) { a = new byte[n]; SCRATCH.set(a); }
    return a;
  }

  /**
   * 🔴 The old implementation **ignored** the Rect r it was given and passed the whole bitmap.
   *    The JNI side had no x/y parameters either, so everything was drawn at the origin and the
   *    screen came out in pieces (diagnosed 2026-09-15).
   */
  public void decompress(ByteBuffer jpegBuf, int jpegBufLen,
    PixelBuffer pb, Rect r, PixelFormat pf)
  {
    byte[] src = scratch(jpegBufLen);
    jpegBuf.get(src, 0, jpegBufLen);

    if (!pb.decodeJpegRect(src, jpegBufLen, r))
      throw new Exception("Tight JPEG: " + r.width() + "x" + r.height() +
                          " rect could not be decoded");
  }
}
