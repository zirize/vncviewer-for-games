#!/bin/bash
# Measures the reported symptom: leave it idle, then touch it, and it is fine for 2-3 seconds,
#   stutters for 5-10, then settles. Hypothesis: power management (frequency scaling).
#
# === 🔴 Why this file exists: the first run was read wrong ===
#   It reported "input is going out (input=50-87/s) but no screen updates arrive (rect/s=2)".
#   Plausible, and **wrong**. The injected swipes accumulated in trackpad mode and had **pushed the
#   remote cursor into the corner of the screen (2339,1079) and pinned it there**. With the cursor
#   not moving, nothing on screen changed, so there were no updates. A side effect of the
#   instrument was wearing the symptom's face.
#   ⇒ So it now **also measures whether the remote cursor actually moved.** Without that axis,
#      "no updates arrive" and "there was nothing to send" can never be told apart.
#
# 🔑 Five axes; drop any one and the hypotheses stop separating:
#   1 CPU frequency   if it is power management, this drops while decode and worstDec rise for the same work
#   2 decode ms/s     how much the CPU actually did
#   3 MB/s, worstGap  is the network blocked
#   4 input=          did the client even send the input
#   5 remote cursor   did that input change anything on the server  <- missing in run one, hence the misreading
#
# Usage:
#   bash idle_wake_probe.sh 40            # a human supplies the touches
#   bash idle_wake_probe.sh 40 --drive    # the script injects input too (reproducible without a person)
#   bash idle_wake_probe.sh 40 --busy     # a control run with the cores kept awake
#   bash idle_wake_probe.sh 40 --restart --drive   # 🔑 restarts the app, so the full-screen update is measured
#
# Further report: **"launching the app updates the whole screen, and it stutters for about ten
#    seconds from then"** ⇒ the trigger is not idleness, it is the **full-screen update**. That is
#    what `--restart` measures.
#    🔑 The first frame after connecting is a single 2340x1080 rect - a different scale of work
#    from the small rects that follow.
#
# Measuring by hand: stay off it for 10 seconds, then keep a finger moving until the end.
#
# 🔴 The limit of `--drive`: `input` goes through InputManager, not the kernel input driver. The
#    vendor's input booster hangs off the driver, so **injected input may not wake it.**
#    ⇒ The "fine for the first 2-3 seconds" part then disappears entirely, and reading that as
#    "no symptom" is wrong. The self-check section watches the frequency response and **decides for
#    itself whether to withhold a verdict.**
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../../scripts/_hostenv.sh"; hostenv_resolve "$HERE/../.."
export PATH="$SDK_ROOT/platform-tools:$PATH"
PKG=tech.doldam.remotepad
SECS="${1:-40}"; IDLE=10
MODE=""; DRIVE=0; RESTART=0; NOLAUNCH=0
for a in "$@"; do case "$a" in --busy) MODE=--busy;; --drive) DRIVE=1;; --restart) RESTART=1;; --no-launch) NOLAUNCH=1;; esac; done
# 🔑 In restart mode the first stretch is not idle - it is connect plus full-screen update. The
#    boundary moves to 3 seconds.
[ $RESTART = 1 ] && IDLE=3
OUT="$HERE/probe"; rm -rf "$OUT"; mkdir -p "$OUT"

adb get-state >/dev/null 2>&1 || { echo "🔴 no device attached - check adb devices"; exit 1; }

# 🔴 With the screen off the app stops drawing - it reads as fps 0.5 and worstGap 5900ms.
#    Those numbers were once misread as "a light load", and the conclusion had to be withdrawn.
#    Nothing in the instrument shows it, because it is the *device* that went to sleep
#    ⇒ keep it awake for the duration.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
adb shell svc power stayon true >/dev/null 2>&1
# 🔴 Waking it is not enough: after a sleep the notification shade holds focus, the app goes to the
#    background and **draws nothing**, so the PERF log comes back empty.
#    One 45-second run was wasted exactly that way.
adb shell cmd statusbar collapse >/dev/null 2>&1
# 🔴 `svc power stayon false` does not "turn it off" - it **overwrites** stay_on_while_plugged_in
#    with 0. On a device set to "never sleep while charging" (=7), every measurement destroyed that
#    setting. Half of "my phone keeps locking itself" was this.
#    ⇒ The original value is recorded and restored.
STAYON_WAS="$(adb shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')"
case "$STAYON_WAS" in ''|*[!0-9]*) STAYON_WAS=7 ;; esac
trap 'adb shell settings put global stay_on_while_plugged_in '"$STAYON_WAS"' >/dev/null 2>&1' EXIT
if [ $RESTART = 0 ]; then
  PID=$(adb shell pidof $PKG | tr -d '\r')
  [ -n "$PID" ] || { echo "🔴 $PKG is not running. Launch it, connect to VNC, and try again."; exit 1; }
else
  # 🔑 It has to be launched *during* the measurement for the first frame to land in the log, so
  #    it is fully stopped first.
  adb shell am force-stop $PKG >/dev/null 2>&1
  PID="(will restart)"
fi

# The logical size in the *current* orientation - the app is sensorLandscape, so it differs from
# the portrait physical size.
read -r SW SH < <(adb exec-out screencap -p 2>/dev/null | head -c 33 | od -An -tu1 -j16 -N8 \
  | awk '{printf "%d %d\n", $1*16777216+$2*65536+$3*256+$4, $5*16777216+$6*65536+$7*256+$8}')
[ "${SW:-0}" -gt 0 ] 2>/dev/null || { SW=2400; SH=1080; }
echo "▸ pid=$PID, ${SECS}s, control=${MODE:-none}, input=$([ $DRIVE = 1 ] && echo injected || echo human), screen=${SW}x${SH}"

# ── Put the remote cursor back in the middle ───────────────────────────
# 🔑 Starting with it pinned in a corner reproduces the run-one mistake exactly. Reset every time.
VNCDISP="${VNCDISP:-:5}"
CURSOR_OK=0
if DISPLAY=$VNCDISP xdotool getmouselocation >/dev/null 2>&1; then
  read -r RW RH < <(DISPLAY=$VNCDISP xdotool getdisplaygeometry)
  DISPLAY=$VNCDISP xdotool mousemove $((RW/2)) $((RH/2))
  CURSOR_OK=1; echo "▸ remote screen ${RW}x${RH}, cursor recentred"
else
  echo "⚠️  cannot see the remote cursor (DISPLAY=$VNCDISP) - running without axis 5. 🔴 Withhold any \"no updates\" verdict."
fi

# ── Launch the app mid-measurement, so the full-screen update falls inside the window ──
# 🔑 With --no-launch a human launches it instead, which wakes the hardware booster properly.
if [ $RESTART = 1 ] && [ $NOLAUNCH = 0 ]; then
  ( sleep 1; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 ) &
elif [ $RESTART = 1 ]; then
  echo "▸ 🖐 launch the app NOW and keep touching it - measuring for ${SECS}s."
fi

BUSYPIDS=""
if [ "$MODE" = "--busy" ]; then
  echo "▸ control run: keeping 4 cores awake (nothing changes except frequency)"
  for i in 1 2 3 4; do adb shell "toybox timeout $((SECS+3)) sh -c 'while :; do :; done'" >/dev/null 2>&1 & BUSYPIDS="$BUSYPIDS $!"; done
  sleep 2
fi

# ── Injected input, left and right well inside the screen.
#    🔴 Go past the edges and it clamps, straight back into the run-one trap. ──
if [ $DRIVE = 1 ]; then
  X1=$((SW/4)); X2=$((SW*3/4)); Y=$((SH/2))
  ( sleep $([ $RESTART = 1 ] && echo 6 || echo $IDLE)   # on restart, wait for it to connect
    END=$(( $(date +%s) + SECS - IDLE - 1 ))
    while [ "$(date +%s)" -lt "$END" ]; do
      adb shell input swipe $X1 $Y $X2 $Y 2500 >/dev/null 2>&1
      adb shell input swipe $X2 $Y $X1 $Y 2500 >/dev/null 2>&1
      # 🔑 Stops accumulated drift pinning the cursor in a corner - how run one broke.
      [ $CURSOR_OK = 1 ] && DISPLAY=$VNCDISP xdotool mousemove $((RW/2)) $((RH/2)) 2>/dev/null
    done ) & DRIVEPID=$!
fi

# 🚫 Never `logcat -c`: it wipes the whole buffer, including somebody else's evidence. `-T 1` starts from now.
adb logcat -T 1 -s PERF:I -v epoch > "$OUT/perf.log" 2>&1 & LOGPID=$!

# Axis 5 - sample the remote cursor from the host, which touches nothing on the device
if [ $CURSOR_OK = 1 ]; then
  ( END=$(( $(date +%s) + SECS ))
    while [ "$(date +%s)" -lt "$END" ]; do
      echo "$(date +%s.%N) $(DISPLAY=$VNCDISP xdotool getmouselocation --shell 2>/dev/null | tr '\n' ' ')"
      sleep 0.2
    done ) > "$OUT/cursor.log" 2>&1 & CURPID=$!
fi

# ── Axis 6, temperature. "Is it slowing down because it is hot?" cannot be answered without
#    recording temperature during the run. ──
# Reported behaviour: under a sudden heavy load the device kills the app and turns the screen off.
# 🔑 Zone numbers differ per device, so they are located by `type` up front - searching inside the
#    loop would itself be load.
TZ_CPU="$(adb shell 'for z in /sys/class/thermal/thermal_zone*; do [ "$(cat $z/type 2>/dev/null)" = "cpu-1-7-usr" ] && { echo $z/temp; break; }; done' 2>/dev/null | tr -d '\r')"
TZ_GPU="$(adb shell 'for z in /sys/class/thermal/thermal_zone*; do [ "$(cat $z/type 2>/dev/null)" = "gpuss-0-usr" ] && { echo $z/temp; break; }; done' 2>/dev/null | tr -d '\r')"
[ -n "$TZ_CPU" ] || TZ_CPU=/dev/null
[ -n "$TZ_GPU" ] || TZ_GPU=/dev/null
# 🔑 Temperature is appended at the END of each freq.log line, so the first four columns keep
# their old format and older logs still parse.
adb shell "toybox timeout $SECS sh -c 'while :; do
  printf \"%s \" \$(date +%s.%N)
  cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq \
      /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq \
      /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq \
      $TZ_CPU $TZ_GPU /sys/class/power_supply/battery/temp | tr \"\n\" \" \"
  echo
  sleep 0.2
done'" > "$OUT/freq.log" 2>&1

sleep 1
# 🔴 `kill 0` kills **the whole process group**. Without --drive, DRIVEPID is empty, so
#    `${DRIVEPID:-0}` becomes 0 and the script killed its own shell right before printing the
#    report. It bit for real: the measurement was complete and no verdict ever appeared.
for p in $LOGPID ${CURPID:-} ${DRIVEPID:-}; do kill "$p" 2>/dev/null; done
for p in $BUSYPIDS; do kill $p 2>/dev/null; done
adb shell "pkill -f 'while :'" >/dev/null 2>&1

python3 "$HERE/idle_wake_report.py" "$OUT" "$IDLE" "$DRIVE"
echo
echo "  raw: $OUT/{perf.log,freq.log,cursor.log}"
