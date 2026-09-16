package com.tigervnc.rfb

import com.tigervnc.rdr.MemOutStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.ArrayDeque

/**
 * [DecodeManager.findEntry] - the dispatcher that picks a rect which is safe to start now.
 *
 * 🔴 **Why this test exists** (2026-09-15): there were four decoder threads and only one of them
 * was ever working (`maxPar=1`). The cause was here. A blocked dispatcher throws no error and
 * simply runs **slow**, so this is the test that catches it without a device.
 */
class DecodeSchedulerTest {

    private val server = ServerParams()

    /** Tight's compression-control byte. 0x90 = JPEG, which uses no zlib stream, so they never conflict. */
    private val TIGHT_JPEG = byteArrayOf(0x90.toByte())
    /** 0x00 = basic using zlib stream 0, so two of these are order-dependent. */
    private val TIGHT_ZLIB0 = byteArrayOf(0x00)
    /** 0x10 = basic using zlib stream 1. */
    private val TIGHT_ZLIB1 = byteArrayOf(0x10)

    private fun entry(
        decoder: Decoder,
        encoding: Int,
        rect: Rect,
        payload: ByteArray = byteArrayOf(0),
        active: Boolean = false,
    ): DecodeManager.QueueEntry {
        val e = DecodeManager.QueueEntry()
        e.active = active
        e.rect = rect
        e.encoding = encoding
        e.decoder = decoder
        e.server = server
        e.pb = null
        e.bufferStream = MemOutStream().apply { writeBytes(payload, 0, payload.size) }
        decoder.getAffectedRegion(rect, e.bufferStream.data(), e.bufferStream.length(), server, e.affectedRegion)
        return e
    }

    private fun queue(vararg e: DecodeManager.QueueEntry) =
        ArrayDeque<DecodeManager.QueueEntry>().apply { e.forEach { addLast(it) } }

    @Test fun `an empty queue offers nothing`() {
        assertNull(DecodeManager.findEntry(queue()))
    }

    @Test fun `it takes the head of the queue when nobody has it`() {
        val a = entry(TightDecoder(), Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_JPEG)
        assertSame(a, DecodeManager.findEntry(queue(a)))
    }

    /** 🔴 The regression this project actually had: return null here and only one of four workers works. */
    @Test fun `it takes a later non-conflicting Tight rect even while an earlier one is busy`() {
        val d = TightDecoder()
        val busy = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_JPEG, active = true)
        val free = entry(d, Encodings.encodingTight, Rect(64, 0, 128, 64), TIGHT_JPEG)
        assertSame(free, DecodeManager.findEntry(queue(busy, free)))
    }

    @Test fun `JPEG rects never conflict, so even the third can be taken`() {
        val d = TightDecoder()
        val a = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_JPEG, active = true)
        val b = entry(d, Encodings.encodingTight, Rect(64, 0, 128, 64), TIGHT_JPEG, active = true)
        val c = entry(d, Encodings.encodingTight, Rect(128, 0, 192, 64), TIGHT_JPEG)
        assertSame(c, DecodeManager.findEntry(queue(a, b, c)))
    }

    @Test fun `Tight rects sharing a zlib stream wait for the earlier one`() {
        val d = TightDecoder()
        val busy = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_ZLIB0, active = true)
        val same = entry(d, Encodings.encodingTight, Rect(64, 0, 128, 64), TIGHT_ZLIB0)
        assertNull(DecodeManager.findEntry(queue(busy, same)))
    }

    @Test fun `Tight rects on different zlib streams can decode at the same time`() {
        val d = TightDecoder()
        val busy = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_ZLIB0, active = true)
        val other = entry(d, Encodings.encodingTight, Rect(64, 0, 128, 64), TIGHT_ZLIB1)
        assertSame(other, DecodeManager.findEntry(queue(busy, other)))
    }

    @Test fun `rects that overlap on screen wait for the earlier one to finish`() {
        val d = TightDecoder()
        val busy = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_JPEG, active = true)
        val overlapping = entry(d, Encodings.encodingTight, Rect(32, 32, 96, 96), TIGHT_JPEG)
        assertNull(DecodeManager.findEntry(queue(busy, overlapping)))
    }

    @Test fun `an order-dependent decoder waits for an earlier rect of the same encoding`() {
        val d = ZRLEDecoder()
        val busy = entry(d, Encodings.encodingZRLE, Rect(0, 0, 64, 64), active = true)
        val next = entry(d, Encodings.encodingZRLE, Rect(64, 0, 128, 64))
        assertNull(DecodeManager.findEntry(queue(busy, next)))
    }

    @Test fun `an order-independent decoder is taken at once when nothing overlaps`() {
        val raw = RawDecoder()
        val busy = entry(raw, Encodings.encodingRaw, Rect(0, 0, 64, 64), active = true)
        val free = entry(raw, Encodings.encodingRaw, Rect(64, 0, 128, 64))
        assertSame(free, DecodeManager.findEntry(queue(busy, free)))
    }

    @Test fun `a blocked rect's area still counts against the rects behind it`() {
        val d = TightDecoder()
        // 1 is in progress; 2 shares its stream so it is blocked; 3 overlaps 2, so 3 must block too
        val busy = entry(d, Encodings.encodingTight, Rect(0, 0, 64, 64), TIGHT_ZLIB0, active = true)
        val blocked = entry(d, Encodings.encodingTight, Rect(200, 200, 264, 264), TIGHT_ZLIB0)
        val overlapsBlocked = entry(d, Encodings.encodingTight, Rect(230, 230, 300, 300), TIGHT_JPEG)
        assertNull(DecodeManager.findEntry(queue(busy, blocked, overlapsBlocked)))
    }
}
