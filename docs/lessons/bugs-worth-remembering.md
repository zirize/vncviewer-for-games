# Four defects worth recognising again

None of these threw an error. Each compiled, ran, and looked fine.

## 1. A missing pair of braces made four worker threads into one

`DecodeManager.findEntry()`, in the partially-ordered branch:

```java
if (entry.decoder.doRectsConflict(...))
  lockedRegion.assign_union(entry.affectedRegion);
  continue next;          // runs regardless of the if
```

So if *any* earlier rect of the same encoding was queued, the worker skipped — conflict or not.
The screen is essentially all Tight, so the effect was "nobody can take anything except the head of
the queue": four workers, one ever working.

This came from the TigerVNC Java port; it was there from the first commit. **Nothing failed. It was
just slow**, which is the hardest kind of defect to see.

Fixing it: fps 12.6 → 17.7, `worker` 694 → 1094 ms/s, `maxPar` 1 → 4, `starve`/s 5013 → 1392.

## 2. …and the thing that would have broken when you fixed it

The dispatcher asks `Region` whether two rects overlap on screen. `Region` inherited from a
hand-written `awtx.geom.Area` that was **a hollow shell**: `isEmpty()` always returned true, and the
`Rectangle` constructor did not even assign its fields. So the answer was always "they do not
overlap".

🔑 **The brace bug is the only reason this never caused damage** — nothing ran in parallel, so
nothing ever overlapped. Fix the braces alone and two workers would have written the same pixels at
once and torn the screen. `Region` had to be implemented for real (a list of disjoint rectangles)
in the same change.

The lesson is about shape: when you remove a bottleneck, ask what was being protected by it.

## 3. An inverted condition stopped the screen updating

`CConnection.fence()`:

```java
if ((flags & fenceFlagRequest) != 0) return;   // upstream is == 0
```

`fenceFlagRequest` is the server asking *"send this fence back"*. The code returned at exactly the
moment it was supposed to reply. With no reply, the server's flow control never reopened its window
and updates stopped.

Evidence was a TCP proxy capture: server sent `f8 000000 80000000 01 00`, client replied **zero
times**, server then sent **0 bytes for 20 seconds**. After the fix, the same window carried
10.8 MB in 637 chunks.

## 4. One `synchronized` Selector deadlocked reads against writes

`SocketDescriptor.select()` was `synchronized` and there was a **single Selector**, so reads and
writes shared one monitor. The message loop sat inside `select()` waiting for data while another
thread's `flush()` could never enter — requests never went out, so the server had nothing to send,
so the read never woke. A circular wait.

Found with a JDWP thread dump (`pool-4-thread-1` waiting on a monitor at `SocketDescriptor.select:72`,
`Thread-3` holding it at `:80`). Fixed by giving reads and writes their own Selector and monitor.

🔑 This one also froze **touch input**, because pointer events are written from a different thread.
A "the screen is frozen" report and an "input does nothing" report were the same defect.

---

## The pattern

All four are invisible to inspection and invisible to the user as anything except "it feels wrong".
What found them: a thread dump, a wire capture, and a counter (`maxPar`) added specifically to
measure a suspicion. **When something feels wrong and the code looks fine, add the instrument.**
