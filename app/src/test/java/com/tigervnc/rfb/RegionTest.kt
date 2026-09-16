package com.tigervnc.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Region] is the safety interlock for parallel decoding: DecodeManager asks it whether a rect
 * overlaps an earlier one on screen. Answer "no" when they do overlap and two workers write the
 * same pixels at once.
 *
 * 🔑 Rect's four-argument constructor is (x1, y1, x2, y2) - top-left and bottom-right, not width
 * and height.
 */
class RegionTest {

    private fun rect(x1: Int, y1: Int, x2: Int, y2: Int) = Rect(x1, y1, x2, y2)

    @Test fun `a new Region is empty`() {
        assertTrue(Region().is_empty())
    }

    @Test fun `a Region built from a rectangle is not empty`() {
        assertFalse(Region(rect(0, 0, 10, 10)).is_empty())
    }

    @Test fun `a zero-area rectangle makes an empty region`() {
        assertTrue(Region(rect(5, 5, 5, 20)).is_empty())
    }

    @Test fun `overlapping regions have a non-empty intersection`() {
        val a = Region(rect(0, 0, 10, 10))
        val b = Region(rect(5, 5, 15, 15))
        assertFalse(a.intersect(b).is_empty())
    }

    @Test fun `disjoint regions intersect to nothing`() {
        val a = Region(rect(0, 0, 10, 10))
        val b = Region(rect(10, 0, 20, 10))
        assertTrue(a.intersect(b).is_empty())
    }

    @Test fun `regions that only touch at an edge do not overlap`() {
        val a = Region(rect(0, 0, 10, 10))
        val b = Region(rect(10, 10, 20, 20))
        assertTrue(a.intersect(b).is_empty())
    }

    @Test fun `intersection does not modify the originals`() {
        val a = Region(rect(0, 0, 10, 10))
        val b = Region(rect(5, 5, 15, 15))
        a.intersect(b)
        assertFalse(a.is_empty())
        assertEquals(rect(0, 0, 10, 10).area(), a.get_bounding_rect().area())
    }

    @Test fun `after a union it overlaps the added rectangle`() {
        val acc = Region()
        acc.assign_union(Region(rect(0, 0, 10, 10)))
        acc.assign_union(Region(rect(100, 100, 110, 110)))
        assertFalse(acc.intersect(Region(rect(5, 5, 6, 6))).is_empty())
        assertFalse(acc.intersect(Region(rect(105, 105, 106, 106))).is_empty())
        // The gap between the two pieces is not part of the region
        assertTrue(acc.intersect(Region(rect(50, 50, 60, 60))).is_empty())
    }

    @Test fun `reset discards what was there`() {
        val r = Region(rect(0, 0, 10, 10))
        r.reset(rect(100, 100, 110, 110))
        assertTrue(r.intersect(Region(rect(0, 0, 10, 10))).is_empty())
        assertFalse(r.intersect(Region(rect(100, 100, 110, 110))).is_empty())
    }

    @Test fun `clear leaves it empty`() {
        val r = Region(rect(0, 0, 10, 10))
        r.clear()
        assertTrue(r.is_empty())
    }

    @Test fun `the copy constructor does not stay linked to the original`() {
        val a = Region(rect(0, 0, 10, 10))
        val b = Region(a)
        b.assign_union(Region(rect(100, 100, 110, 110)))
        assertTrue(a.intersect(Region(rect(100, 100, 110, 110))).is_empty())
    }

    @Test fun `get_bounding_rect returns top-left and bottom-right`() {
        val r = Region()
        r.assign_union(Region(rect(10, 20, 30, 40)))
        r.assign_union(Region(rect(100, 5, 110, 15)))
        val b = r.get_bounding_rect()
        assertEquals(10, b.tl.x); assertEquals(5, b.tl.y)
        assertEquals(110, b.br.x); assertEquals(40, b.br.y)
    }

    @Test fun `translate moves the region`() {
        val r = Region(rect(0, 0, 10, 10))
        r.translate(Point(100, 0))
        assertTrue(r.intersect(Region(rect(0, 0, 10, 10))).is_empty())
        assertFalse(r.intersect(Region(rect(100, 0, 110, 10))).is_empty())
    }

    @Test fun `subtract removes only what was taken away`() {
        val a = Region(rect(0, 0, 20, 20))
        a.assign_subtract(Region(rect(0, 0, 10, 20)))
        assertTrue(a.intersect(Region(rect(0, 0, 10, 20))).is_empty())
        assertFalse(a.intersect(Region(rect(10, 0, 20, 20))).is_empty())
    }
}
