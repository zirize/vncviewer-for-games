#!/usr/bin/env python3
"""Overlays the three logs idle_wake_probe.sh collects, second by second.

🔑 No single axis settles any hypothesis (see "five axes" in that script's header).
   The remote-cursor column especially: without it, "no screen updates are arriving" and "there was
   nothing to send" are indistinguishable - which is exactly how the first run was misread.
"""
import sys, re, collections

out, idle, drive = sys.argv[1], int(sys.argv[2]), sys.argv[3] == "1"

freq = collections.defaultdict(lambda: [0, 0, 0])
# Axis 6, temperature - read from the end of a freq.log line **if present** (older logs have only
# four columns, in which case it stays empty).
#   🔑 The units differ: thermal_zone is millidegrees (36600 = 36.6C), battery is tenths (312 = 31.2C).
temp = collections.defaultdict(lambda: [0.0, 0.0, 0.0])
for ln in open(f"{out}/freq.log", errors="replace"):
    f = ln.split()
    if len(f) < 4: continue
    try: t = int(float(f[0])); v = [int(x) // 1000 for x in f[1:4]]
    except ValueError: continue
    freq[t] = [max(a, b) for a, b in zip(freq[t], v)]
    if len(f) >= 7:
        try: c = [int(f[4]) / 1000.0, int(f[5]) / 1000.0, int(f[6]) / 10.0]
        except ValueError: continue
        temp[t] = [max(a, b) for a, b in zip(temp[t], c)]

# Remote cursor - converted to distance moved. Movement answers the question; position does not.
move, pos = collections.defaultdict(int), {}
prev = None
for ln in open(f"{out}/cursor.log", errors="replace") if __import__("os").path.exists(f"{out}/cursor.log") else []:
    m = re.match(r"(\d+\.?\d*)\s+X=(\d+)\s+Y=(\d+)", ln)
    if not m: continue
    t, x, y = int(float(m.group(1))), int(m.group(2)), int(m.group(3))
    if prev: move[t] += abs(x - prev[0]) + abs(y - prev[1])
    prev = (x, y); pos[t] = (x, y)

FIELDS = ["fps", "rect/s", "MB/s", "decode", "worstDec", "worstGap", "input"]
rows = []
for ln in open(f"{out}/perf.log", errors="replace"):
    if "fps=" not in ln: continue
    try: t = int(float(ln.split()[0]))
    except (ValueError, IndexError): continue
    d = dict(re.findall(r"([A-Za-z/]+)=([0-9.]+)", ln))
    rows.append((t, d))

if not rows:
    print("  🔴 the PERF log is empty - check that the app is connected."); sys.exit(1)

t0 = rows[0][0]
print("\n======== per-second table ========")
print("  🔑 Five axes on the same second. A zero cursor column with no screen updates is NORMAL.")
HAVE_T = bool(temp)
print(f"  {'s':>3} {'fps':>6} {'rect/s':>7} {'MB/s':>6} {'decode':>8} {'wDec':>6} {'wGap':>7} "
      f"{'input':>6} {'cursor':>8}   {'little':>6} {'big':>5} {'prime':>6}"
      + (f" {'CPU C':>6} {'GPU C':>6} {'batt C':>7}" if HAVE_T else ""))
for t, d in rows:
    r = t - t0
    lo, bg, pr = freq.get(t, [0, 0, 0])
    mark = "·" if r < idle else " "
    tc, tg, tb = temp.get(t, [0.0, 0.0, 0.0])
    print(f" {mark}{r:>3} {d.get('fps','-'):>6} {d.get('rect/s','-'):>7} {d.get('MB/s','-'):>6} "
          f"{d.get('decode','-')+'ms':>8} {d.get('worstDec','-')+'ms':>6} {d.get('worstGap','-')+'ms':>7} "
          f"{d.get('input','-'):>6} {move.get(t,0):>8}   {lo:>5} {bg:>5} {pr:>6}"
          + (f" {tc:>6.1f} {tg:>6.1f} {tb:>6.1f}" if HAVE_T else ""))

def split(pred):
    return [(t, d) for t, d in rows if pred(t - t0)]
pre, post = split(lambda r: r < idle), split(lambda r: r >= idle)

print("\n======== self-check of the instrument ========")
# 🔴 With the device screen off the app stops drawing - fps 0.5, worstGap 5900ms.
#    Those numbers were once misread as "a light load". Now the instrument catches it first.
_gaps = [float(d["worstGap"]) for _, d in rows if "worstGap" in d]
if _gaps and max(_gaps) > 2000:
    print(f"  🔴 worstGap reached {max(_gaps):.0f}ms - most likely the device screen went off and")
    print("     the app stopped drawing. 🚫 Do not use this run for a load comparison.")
# Axis 6 - "is heat the trigger?" 🔑 The question is not "did it rise" but "did it slow down as it
#    rose". Reported behaviour: under a sudden heavy load the device kills the app and turns the
#    screen off (thermal protection).
if temp:
    ts = sorted(temp.items())
    c0, cN = ts[0][1][0], ts[-1][1][0]
    b0, bN = ts[0][1][2], ts[-1][1][2]
    cmax = max(v[0] for _, v in ts)
    print(f"  6 temperature  CPU {c0:.1f} -> {cN:.1f}C (peak {cmax:.1f}), battery {b0:.1f} -> {bN:.1f}C")
    if cmax >= 70 or bN >= 42:
        print("  🔴 **measured while hot** - this device stops the app and turns the screen off when it heats up.")
        print("     => 🚫 Do not compare this directly with anything. Let it cool for 5 minutes and measure again.")
    elif cN - c0 >= 8:
        print(f"  ⚠️  the CPU rose {cN-c0:.1f}C during the run - later seconds are handicapped against earlier ones.")

def peak(rs, i): return max((freq.get(t, [0,0,0])[i] for t, _ in rs), default=0)
if not pre or not post:
    print("  ⚠️  the stretches did not separate - no verdict."); sys.exit(0)

mv_post = sum(move.get(t, 0) for t, _ in post)
print(f"  5 remote cursor movement (sum over the input stretch): {mv_post} px")
if move:
    if mv_post < 200:
        print("  🔴 the cursor barely moved - the input changed nothing on the server.")
        print("     => 🚫 Do not read this as \"no screen updates\". There was nothing to send.")
    else:
        print("  ✅ the cursor really moved - this run is entitled to judge \"no screen updates\".")
else:
    print("  ⚠️  the cursor could not be measured - hold off on any \"no screen updates\" verdict.")

ib, ab = peak(pre, 1), peak(post, 1)
ip, ap = peak(pre, 2), peak(post, 2)
print(f"  1 big core   idle {ib} -> input {ab} MHz  ·  prime idle {ip} -> input {ap} MHz")
if ab > ib * 1.15 or ap > ip * 1.15:
    print("  ✅ frequency responded to input - this run can speak to the power-management axis.")
elif drive:
    print("  🔴 frequency did not move. Injected input may not have woken the hardware booster.")
    print("     => 🚫 Do not read this as \"no symptom\". Measure again with a real hand.")
else:
    print("  ⚠️  it did not move even with a real hand - the booster is off, or it was already at maximum.")

def avg(rs, k):
    v = [float(d[k]) for _, d in rs if k in d]
    return sum(v) / len(v) if v else 0.0
act = [(t, d) for t, d in post if float(d.get("input", 0)) > 0]
if act:
    n = len(act); third = max(1, n // 3)
    print("\n======== does \"fine, then stutters, then fine again\" show up? ========")
    for name, seg in (("first", act[:third]), ("middle", act[third:2*third]), ("last", act[2*third:])):
        print(f"  {name:>4}: fps {avg(seg,'fps'):5.1f} · decode {avg(seg,'decode'):5.0f}㎳/s · "
              f"worstDec {avg(seg,'worstDec'):4.0f}㎳ · worstGap {avg(seg,'worstGap'):5.0f}㎳ · "
              f"big {max((freq.get(t,[0,0,0])[1] for t,_ in seg), default=0)}MHz")
