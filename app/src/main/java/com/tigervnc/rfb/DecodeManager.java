/* Modified in 2026 by Bill Kang for vncviewer-for-games.
 * GPL-2.0 section 2(a) asks modified files to say so; this is that notice.
 * The original copyright and license follow below and are unchanged.
 */
/* Copyright 2015 Pierre Ossman for Cendio AB
 * Copyright 2016-2026 Brian P. Hinz
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
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.tigervnc.rfb;

import java.lang.Runtime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import com.tigervnc.rdr.*;
import com.tigervnc.rdr.Exception;

import static com.tigervnc.rfb.Decoder.DecoderFlags.*;

public class DecodeManager {

  static LogWriter vlog = new LogWriter("DecodeManager");

  public DecodeManager(CConnection conn) {
    int cpuCount;

    this.conn = conn; threadException = null;
    decoders = new Decoder[Encodings.encodingMax+1];

    queueMutex = new ReentrantLock();
    producerCond = queueMutex.newCondition();
    consumerCond = queueMutex.newCondition();

    cpuCount = Runtime.getRuntime().availableProcessors();
    if (cpuCount == 0) {
      vlog.error("Unable to determine the number of CPU cores on this system");
      cpuCount = 1;
    } else {
      vlog.info("Detected "+cpuCount+" CPU core(s)");
      // No point creating more threads than this, they'll just end up
      // wasting CPU fighting for locks
      if (cpuCount > 4)
        cpuCount = 4;
      // Instrumentation-only override - at the default (0) the logic above is unchanged, and so
      //    is shipping behaviour.
      //    🔑 Set to 1 it takes the fast path that skips the queue and dispatcher entirely (see
      //       decodeRect), which is the only way to isolate "is the dispatcher eating the gains
      //       from parallelism" as a single variable.
      //    🚫 Do not ship a number tuned on one phone. It differs per device.
      if (io.github.zirize.vncviewerforgames.BuildConfig.VNC_DECODER_THREADS > 0) {
        cpuCount = io.github.zirize.vncviewerforgames.BuildConfig.VNC_DECODER_THREADS;
        vlog.info("⚠️ instrumentation: decoder threads pinned to "+cpuCount);
      }
      // The overhead of threading is small, but not small enough to
      // ignore on single CPU systems
      if (cpuCount == 1)
        vlog.info("Decoding data on main thread");
      else
        vlog.info("Creating "+cpuCount+" decoder thread(s)");
    }

    freeBuffers = new ArrayDeque<MemOutStream>(cpuCount*4);
    workQueue = new ArrayDeque<QueueEntry>(cpuCount*2);
    threads = new ArrayList<DecodeThread>(cpuCount);
    while (cpuCount-- > 0) {
      try {
        freeBuffers.addLast(new MemOutStream());
        freeBuffers.addLast(new MemOutStream());
        freeBuffers.addLast(new MemOutStream());
        freeBuffers.addLast(new MemOutStream());
        threads.add(new DecodeThread(this));
      } catch (IllegalStateException e) { }
    }
  }

  public void stop() {
    queueMutex.lock();
    try {
      if (threads != null) {
        for (DecodeThread t : threads) {
          t.stop();
        }
      }
      producerCond.signalAll();
      consumerCond.signalAll();
    } finally {
      queueMutex.unlock();
    }

    // Release any resources (e.g. native zlib Inflater state) the
    // decoders are holding onto -- they are otherwise only reachable
    // via garbage collection/finalization, which leaks native memory
    // in proportion to how many connections have been made.
    for (Decoder d : decoders) {
      if (d != null)
        d.close();
    }
  }

  public void decodeRect(Rect r, int encoding,
                         ModifiablePixelBuffer pb)
  {
    Decoder decoder;
    MemOutStream bufferStream;

    QueueEntry entry;

    assert(pb != null);

    if (!Decoder.supported(encoding)) {
      vlog.error("Unknown encoding " + encoding);
      throw new Exception("Unknown encoding");
    }

    if (decoders[encoding] == null) {
      decoders[encoding] = Decoder.createDecoder(encoding);
      if (decoders[encoding] == null) {
        vlog.error("Unknown encoding " + encoding);
        throw new Exception("Unknown encoding");
      }
    }

    decoder = decoders[encoding];

    // Fast path for single CPU machines to avoid the context
    // switching overhead
    if (threads.size() == 1) {
      bufferStream = freeBuffers.getFirst();
      bufferStream.clear();
      long t0fp = System.nanoTime();
      decoder.readRect(r, conn.getInStream(), conn.server, bufferStream);
      io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.readRect(bufferStream.length(), System.nanoTime() - t0fp);
      decoder.decodeRect(r, (Object)bufferStream.data(), bufferStream.length(),
                         conn.server, pb);
      return;
    }

    // Wait for an available memory buffer
    queueMutex.lock();

    try {
      // 🔑 When the workers fall behind, this is where it blocks. Separates waiting from working in the decode wall clock.
      long tw0 = System.nanoTime();
      while (freeBuffers.isEmpty())
        try {
        producerCond.await();
        } catch (InterruptedException e) { }
      io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.bufWait(System.nanoTime() - tw0);

      // Don't pop the buffer in case we throw an exception
      // whilst reading
      bufferStream = freeBuffers.getFirst();
    } finally {
      queueMutex.unlock();
    }

    // First check if any thread has encountered a problem
    throwThreadException();

    // Read the rect
    bufferStream.clear();
    long tr0 = System.nanoTime();
    decoder.readRect(r, conn.getInStream(), conn.server, bufferStream);
    io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.readRect(bufferStream.length(), System.nanoTime() - tr0);

    // 🔑 From here to the end of the method is dispatch - the ceiling on what adaptive decoding can save.
    long td0 = System.nanoTime();

    // Then try to put it on the queue
    entry = new QueueEntry();

    entry.active = false;
    entry.rect = r;
    entry.encoding = encoding;
    entry.decoder = decoder;
    entry.server = conn.server;
    entry.pb = pb;
    entry.bufferStream = bufferStream;

    long ta0 = System.nanoTime();
    decoder.getAffectedRegion(r, bufferStream.data(),
                              bufferStream.length(), conn.server,
                              entry.affectedRegion);
    io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.affected(System.nanoTime() - ta0);

    queueMutex.lock();

    try {
      // The workers add buffers to the end so it's safe to assume
      // the front is still the same buffer
      freeBuffers.removeFirst();

      workQueue.addLast(entry);

      consumerCond.signalAll();
    } finally {
      queueMutex.unlock();
    }

    io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.dispatch(System.nanoTime() - td0);
  }

  public void flush()
  {
    queueMutex.lock();

    try {
      while (!workQueue.isEmpty() && threadException == null)
        try {
          producerCond.await();
        } catch (InterruptedException e) { }
    } finally {
      queueMutex.unlock();
    }

    throwThreadException();
  }

  private void setThreadException(Throwable e)
  {
    queueMutex.lock();

    try {
      if (threadException != null)
        return;

      String msg = e.getMessage();
      if (msg == null)
        msg = e.getClass().getName();
      threadException =
        new Exception("Exception on worker thread: "+msg);
      producerCond.signalAll();
    } finally {
      queueMutex.unlock();
    }
  }

  private void throwThreadException()
  {
    //os::AutoMutex a(queueMutex);
    queueMutex.lock();

    try {
      if (threadException == null)
        return;

      Exception e = new Exception(threadException.getMessage());

      threadException = null;

      throw e;
    } finally {
      queueMutex.unlock();
    }
  }

  /**
   * One rect on the queue. 🔑 static and package-private so that the dispatch logic ([findEntry])
   * can be unit-tested without a device.
   */
  static class QueueEntry {

    public QueueEntry() {
      affectedRegion = new Region();
    }
    public boolean active;
    public Rect rect;
    public int encoding;
    public Decoder decoder;
    public ServerParams server;
    public ModifiablePixelBuffer pb;
    public MemOutStream bufferStream;
    public Region affectedRegion;
  }

  /**
   * Picks one rect from the queue that is safe to start now, or null.
   *
   * 🔑 It only needs the queue, so it is a pure static function and can be unit-tested
   *    ([com.tigervnc.rfb.DecodeSchedulerTest]).
   * 🔴 The caller must hold queueMutex.
   *
   * There are three reasons a rect cannot be taken:
   *   1. another worker already has it
   *   2. the decoder is order-dependent (ZRLE always, Tight when sharing a zlib stream), so an
   *      earlier rect has to finish first
   *   3. it **overlaps an earlier rect on screen** - decoded concurrently, the later one could be
   *      drawn first
   * A rejected rect's area accumulates into lockedRegion, which is what reason 3 is tested against
   * for the rects behind it.
   */
  static QueueEntry findEntry(Deque<QueueEntry> workQueue)
  {
    Iterator<QueueEntry> iter;
    Region lockedRegion = new Region();

    if (workQueue.isEmpty())
      return null;

    if (!workQueue.peek().active)
      return workQueue.peek();

    next:for (iter = workQueue.iterator(); iter.hasNext();) {
      QueueEntry entry, entry2;

      Iterator<QueueEntry> iter2;

      entry = iter.next();

      // Another thread working on this?
      if (entry.active) {
        lockedRegion.assign_union(entry.affectedRegion);
        continue next;
      }

      // If this is an ordered decoder then make sure this is the first
      // rectangle in the queue for that decoder
      if ((entry.decoder.flags & DecoderOrdered) != 0) {
        for (iter2 = workQueue.iterator(); iter2.hasNext() &&
             !(entry2 = iter2.next()).equals(entry);) {
          if (entry.encoding == entry2.encoding) {
            lockedRegion.assign_union(entry.affectedRegion);
            continue next;
          }
        }
      }

      // For a partially ordered decoder we must ask the decoder for each
      // pair of rectangles.
      if ((entry.decoder.flags & DecoderPartiallyOrdered) != 0) {
        for (iter2 = workQueue.iterator(); iter2.hasNext() &&
             !(entry2 = iter2.next()).equals(entry);) {
          if (entry.encoding != entry2.encoding)
            continue;
          // 🔴 These braces were **missing**: `continue next` ran regardless of the `if`, so any
          //    earlier rect of the same encoding on the queue caused a skip **even without a
          //    conflict**. The screen is almost entirely Tight, so the effect was "nobody can take
          //    anything except the head of the queue" - four workers, one ever working
          //    (measured 2026-09-15: maxPar = 1).
          //    🔑 Nothing failed. It was just slow, which is the hardest kind of defect to see.
          if (entry.decoder.doRectsConflict(entry.rect,
                                            entry.bufferStream.data(),
                                            entry.bufferStream.length(),
                                            entry2.rect,
                                            entry2.bufferStream.data(),
                                            entry2.bufferStream.length(),
                                            entry.server)) {
            lockedRegion.assign_union(entry.affectedRegion);
            continue next;
          }
        }
      }

      // Check overlap with earlier rectangles
      if (!lockedRegion.intersect(entry.affectedRegion).is_empty()) {
        lockedRegion.assign_union(entry.affectedRegion);
        continue next;
      }

      return entry;

    }

    return null;
  }

  private class DecodeThread implements Runnable {

    public DecodeThread(DecodeManager manager)
    {
      this.manager = manager;

      stopRequested = false;

      (thread = new Thread(this, "Decoder Thread")).start();
    }

    public void stop()
    {
      //os::AutoMutex a(manager.queueMutex);
      manager.queueMutex.lock();

      try {
        if (!thread.isAlive())
          return;

        stopRequested = true;

        // We can't wake just this thread, so wake everyone
        manager.consumerCond.signalAll();
      } finally {
        manager.queueMutex.unlock();
      }
    }

    public void run()
    {
      manager.queueMutex.lock();

      while (!stopRequested) {
        QueueEntry entry;

        // Look for an available entry in the work queue
        entry = findEntry();
        if (entry == null) {
          // Queue not empty but nothing dispatchable = the scheduler,
          // not the workload, is what is keeping this thread idle.
          if (!manager.workQueue.isEmpty())
            io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.starve();
          // Wait and try again
          try {
            manager.consumerCond.await();
          } catch (InterruptedException e) { }
          continue;
        }

        // This is ours now
        entry.active = true;
        int concurrency = ++manager.activeWorkers;

        manager.queueMutex.unlock();

        // Do the actual decoding
        long busyStart = System.nanoTime();
        try {
          entry.decoder.decodeRect(entry.rect, entry.bufferStream.data(),
                                   entry.bufferStream.length(),
                                   entry.server, entry.pb);
        } catch (Throwable e) {
          manager.setThreadException(e);
        }
        io.github.zirize.vncviewerforgames.perf.PerfStats.INSTANCE.worker(
          System.nanoTime() - busyStart, concurrency);

        manager.queueMutex.lock();
        manager.activeWorkers--;

        // Remove the entry from the queue and give back the memory buffer
        manager.freeBuffers.addLast(entry.bufferStream);
        manager.workQueue.remove(entry);
        entry = null;

        // Wake the main thread in case it is waiting for a memory buffer
        manager.producerCond.signal();
        // This rect might have been blocking multiple other rects, so
        // wake up every worker thread
        if (manager.workQueue.size() > 1)
          manager.consumerCond.signalAll();
      }

      manager.queueMutex.unlock();
    }

    protected QueueEntry findEntry()
    {
      return DecodeManager.findEntry(manager.workQueue);
    }

    private DecodeManager manager;
    private boolean stopRequested;

    private Thread thread;

  }

  private CConnection conn;
  private Decoder[] decoders;

  private ArrayDeque<MemOutStream> freeBuffers;
  private ArrayDeque<QueueEntry> workQueue;

  private ReentrantLock queueMutex;
  private Condition producerCond;
  private Condition consumerCond;

  private List<DecodeThread> threads;
  private com.tigervnc.rdr.Exception threadException;

  /** How many workers are currently inside decodeRect. Guarded by queueMutex. Instrumentation only. */
  private int activeWorkers;

}
