/* Modified in 2026 by Bill Kang for vncviewer-for-games.
 * GPL-2.0 section 2(a) asks modified files to say so; this is that notice.
 * The original copyright and license follow below and are unchanged.
 */
/* Copyright (C) 2016 Brian P. Hinz.  All Rights Reserved.
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

import java.util.ArrayList;
import java.util.List;

/**
 * An area of the screen, held as a list of mutually disjoint rectangles.
 *
 * 🔴 **Before 2026-09-15 every method here was a no-op.** It originally extended
 * java.awt.geom.Area; Android has no AWT, so a hand-written replacement layer (awtx) was put
 * underneath — and that awtx.geom.Area was **methods with no bodies**. In particular
 * {@code isEmpty()} always returned true.
 * ⇒ Whenever DecodeManager asked "does this rect overlap an earlier one", the answer was always no.
 * Enable parallel decoding on top of that answer and two workers write **the same pixels** at once,
 * tearing the screen.
 * 🔑 The only reason the no-op was never dangerous is that a separate dispatcher defect meant
 * nothing ran in parallel at all. Fixing that one alone would have broken this.
 *
 * 🔑 The pieces are kept disjoint at all times, which is what makes subtract well defined.
 */
public class Region {

  private final List<Rect> rects = new ArrayList<Rect>();

  // Create an empty region
  public Region() {
  }

  // Create a rectangular region
  public Region(Rect r) {
    if (r != null && !r.is_empty())
      rects.add(copy(r));
  }

  public Region(Region r) {
    for (Rect x : r.rects)
      rects.add(copy(x));
  }

  public void clear() { rects.clear(); }

  public void reset(Rect r) {
    clear();
    if (r != null && !r.is_empty())
      rects.add(copy(r));
  }

  public void translate(Point delta) {
    for (int i = 0; i < rects.size(); i++)
      rects.set(i, rects.get(i).translate(delta));
  }

  public void assign_intersect(Region r) {
    List<Rect> out = new ArrayList<Rect>();
    for (Rect a : rects)
      for (Rect b : r.rects) {
        Rect i = a.intersect(b);
        if (!i.is_empty())
          out.add(i);
      }
    rects.clear();
    rects.addAll(out);
  }

  public void assign_union(Region r) {
    // To keep the pieces disjoint, subtract what is already covered from the incoming rectangle first.
    for (Rect b : r.rects) {
      List<Rect> pieces = new ArrayList<Rect>();
      pieces.add(copy(b));
      for (Rect a : rects)
        pieces = subtractFrom(pieces, a);
      rects.addAll(pieces);
    }
  }

  public void assign_subtract(Region r) {
    List<Rect> pieces = new ArrayList<Rect>(rects);
    for (Rect b : r.rects)
      pieces = subtractFrom(pieces, b);
    rects.clear();
    rects.addAll(pieces);
  }

  public Region intersect(Region r) {
    Region reg = new Region(this);
    reg.assign_intersect(r);
    return reg;
  }

  public Region union(Region r) {
    Region reg = new Region(this);
    reg.assign_union(r);
    return reg;
  }

  public Region subtract(Region r) {
    Region reg = new Region(this);
    reg.assign_subtract(r);
    return reg;
  }

  public boolean is_empty() { return rects.isEmpty(); }

  public Rect get_bounding_rect() {
    Rect b = new Rect();
    for (Rect r : rects)
      b = b.union_boundary(r);
    return b;
  }

  private static Rect copy(Rect r) {
    return new Rect(r.tl.x, r.tl.y, r.br.x, r.br.y);
  }

  /** What is left of each rectangle in [pieces] after removing [cut]. One piece splits into at most four. */
  private static List<Rect> subtractFrom(List<Rect> pieces, Rect cut) {
    List<Rect> out = new ArrayList<Rect>();
    for (Rect p : pieces) {
      if (!p.overlaps(cut)) { out.add(p); continue; }
      // Split into top / bottom / left / right bands; the middle is what gets cut away.
      if (cut.tl.y > p.tl.y)
        out.add(new Rect(p.tl.x, p.tl.y, p.br.x, cut.tl.y));
      if (cut.br.y < p.br.y)
        out.add(new Rect(p.tl.x, cut.br.y, p.br.x, p.br.y));
      int top = Math.max(p.tl.y, cut.tl.y);
      int bottom = Math.min(p.br.y, cut.br.y);
      if (top < bottom) {
        if (cut.tl.x > p.tl.x)
          out.add(new Rect(p.tl.x, top, cut.tl.x, bottom));
        if (cut.br.x < p.br.x)
          out.add(new Rect(cut.br.x, top, p.br.x, bottom));
      }
    }
    return out;
  }
}
