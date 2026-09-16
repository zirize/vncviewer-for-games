#!/usr/bin/env python3
"""Folds the PERF log by stretch (quiet / load / load-stopped) and reports the **input** axis.

🔑 Two figures:
     inLat  how far behind the UI thread is - the lag a finger feels
     inSnd  send-queue delay
   The screen axes (fps, render) are printed for context only; the verdict comes from input.
🔴 worst matters more than avg. Stutter does not show up in an average.
"""
import re, sys, time, datetime, statistics

log, label = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "run"
phases = []
try:
    for line in open(log + ".phases"):
        n, t = line.split()
        phases.append((n, float(t)))
except FileNotFoundError:
    sys.exit("🔴 no stretch file - the run died before finishing. Not reporting.")

# 🔑 `logcat -v time` looks like "09-16 18:56:48.939 I/PERF    ( 7216): ..." - there is padding
#    and a "(pid)" after the tag, so searching for "PERF:" matches nothing at all.
LINE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d).*?PERF[^:]*:\s*(.*)$")
KV = re.compile(r"(\w+)=([-\d.]+)")
year = datetime.date.today().year
rows = []
for line in open(log, errors="replace"):
    m = LINE.match(line)
    if not m:
        continue
    mo, d, H, M, S, ms, body = m.groups()
    ts = time.mktime((year, int(mo), int(d), int(H), int(M), int(S), 0, 0, -1)) + int(ms) / 1000
    kv = dict(KV.findall(body))
    il = re.search(r"inLat=([\d.]+)/(\d+)ms\((\d+)\)", body)
    isn = re.search(r"inSnd=([\d.]+)/(\d+)ms", body)
    iw = re.search(r"inW=([\d.]+)/(\d+)ms", body)
    if not il:
        continue     # a build without the new axes is a stale APK - never mix the two
    rows.append(dict(ts=ts,
                     fps=float(kv.get("fps", 0)), render=float(kv.get("render", 0)),
                     worstDec=float(kv.get("worstDec", 0)), mpx=float(kv.get("Mpx", 0) or 0),
                     inLatAvg=float(il.group(1)), inLatWorst=int(il.group(2)), inN=int(il.group(3)),
                     inSndAvg=float(isn.group(1)) if isn else 0.0,
                     inSndWorst=int(isn.group(2)) if isn else 0,
                     inWrWorst=int(iw.group(2)) if iw else 0))

if not rows:
    sys.exit("🔴 no PERF lines at all (or a stale APK). Not reporting.")

print(f"\n== input-lag [{label}] ==  ({len(rows)} PERF lines)")
print(f"{'stretch':<10}{'s':>4}{'inLat avg':>11}{'inLat worst':>13}{'inSnd worst':>13}{'inW worst':>11}"
      f"{'input/s':>10}{'fps':>7}{'render ms/s':>12}")
verdict = {}
for i, (name, t0) in enumerate(phases[:-1]):
    t1 = phases[i + 1][1]
    seg = [r for r in rows if t0 <= r["ts"] < t1]
    if not seg:
        print(f"{name:<10}{t1-t0:>4.0f}   -- no PERF lines --")
        continue
    lat = statistics.mean(r["inLatAvg"] for r in seg)
    worst = max(r["inLatWorst"] for r in seg)
    sworst = max(r["inSndWorst"] for r in seg)
    wworst = max(r["inWrWorst"] for r in seg)
    n = sum(r["inN"] for r in seg) / (t1 - t0)
    print(f"{name:<8}{t1-t0:>4.0f}{lat:>11.0f}{worst:>13}{sworst:>13}{wworst:>11}"
          f"{n:>10.0f}{statistics.mean(r['fps'] for r in seg):>7.1f}"
          f"{statistics.mean(r['render'] for r in seg):>11.0f}")
    verdict[name] = (lat, worst, n)

if "quiet" in verdict and "load" in verdict:
    q, l = verdict["quiet"], verdict["load"]
    print(f"\n🔑 load / quiet - avg {l[0]/max(q[0],1e-9):.1f}x, worst {l[1]/max(q[1],1):.1f}x")
    if l[2] < q[2] * 0.5:
        print("⚠️ the load stretch saw under half the input events of the quiet one, so events were")
        print("   not even reaching the app. inLat then only covers what arrived, so it reads optimistic.")
