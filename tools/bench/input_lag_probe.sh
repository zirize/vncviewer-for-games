#!/usr/bin/env bash
# Measures "input stutters badly under screen load" on the **input** axis.
#   Reported 2026-09-16. The call was: tearing is acceptable, input lag is not.
#
# 🔴 Why the existing probes cannot answer this: idle_wake and afterload judge on **screen** axes
#    (fps, worstDec, Mpx/s). Those get worse under load by definition, so they cannot say whether
#    input got worse with them or not. Only two figures matter here:
#      inLat  event created -> onTouchEvent handles it   = how far behind the UI thread is
#      inSnd  "send this"   -> writeExecutor writes it   = send-queue delay
#    ⇒ It separates "the screen is slow" from "the finger is slow". The second is what we are fixing.
#
# 🔑 Three stretches; turning the load on and off *is* the control:
#    0-10s   quiet, no load    <- the baseline
#   10-35s   load (a full-screen stress clip)
#   35-45s   load stopped      <- also shows whether anything persists
#
# ⚠️ Injected input is not a human hand and may not wake the vendor's input booster.
#    So these numbers are **for A/B comparison**, not absolutes. Only compare like with like.
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 🔑 The adb path is not hard-coded: on a different host that silently produces an empty measurement.
. "$HERE/../../scripts/_hostenv.sh"; hostenv_resolve "$HERE/../.."
ADB="$SDK_ROOT/platform-tools/adb"
PKG=tech.doldam.remotepad

LABEL="${1:-run}"
CLIP="${INPUTLAG_CLIP:-/tmp/stress_stripes.mp4}"
DISP="${VNCDISP:-:5}"
OUT="${INPUTLAG_OUT:-/tmp}"
# 🔑 The duration is overridable, for asking whether a longer run behaves differently (heat, backlog).
QUIET_S="${INPUTLAG_QUIET:-10}"; LOAD_S="${INPUTLAG_LOAD:-25}"; AFTER_S="${INPUTLAG_AFTER:-10}"

command -v mpv >/dev/null || { echo "🔴 no mpv"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "🔴 no device attached - refusing to measure"; exit 1; }

# 🔴 Missing prerequisites stop the run. Without them the "load" stretch is identical to the quiet
#    one, and that measurement reads as "the fix worked" - the worst kind of false result.
if [ ! -f "$CLIP" ]; then
  echo "[input-lag] no stress clip at $CLIP - building one"
  "$HERE/make_stress_clip.sh" --stage "${INPUTLAG_STAGE:-stripes}" --size 2340x1080 \
      --secs 6 --out "$CLIP" || exit 1
fi

cleanup() {
  [ -n "${INPID:-}" ] && kill "$INPID" 2>/dev/null
  "$ADB" shell "pkill -f 'input swipe'" >/dev/null 2>&1
  for p in $(pgrep -f 'mpv --really-quiet'); do kill "$p" 2>/dev/null; done
  [ -n "${LOGPID:-}" ] && kill "$LOGPID" 2>/dev/null
  # 🔴 Put the device's own "stay awake while charging" setting back. `svc power stayon true`
  #    below **overwrote** it (with 7); leaving it overwritten silently changes the owner's phone.
  [ -n "${STAYON_WAS:-}" ] && \
    "$ADB" shell settings put global stay_on_while_plugged_in "$STAYON_WAS" >/dev/null 2>&1
  return 0
}
trap cleanup EXIT

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
# 🔑 Captured **before** the overwrite - read it afterwards and you only ever read back the 7
#    that `stayon true` just wrote.
STAYON_WAS="$("$ADB" shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r')"
case "$STAYON_WAS" in ''|*[!0-9]*) STAYON_WAS=7 ;; esac
"$ADB" shell svc power stayon true >/dev/null 2>&1
"$ADB" shell cmd statusbar collapse >/dev/null 2>&1

LOG="$OUT/inputlag_$LABEL.log"
"$ADB" logcat -c
"$ADB" logcat -v time -s PERF:I > "$LOG" 2>&1 &
LOGPID=$!

# 🔑 Input injection runs inside a single shell, so the ~100ms adb round trip stays out of the measurement.
# 🔴 Swipes alternate direction. Always swiping the same way parks the cursor in a corner in
#    trackpad mode, the server then has nothing to send, and the load disappears entirely.
# 🔑 x 600-1800 is the middle of the screen, clear of the overlay panels in the margins.
"$ADB" shell 'while true; do input swipe 700 400 1700 700 1500; input swipe 1700 700 700 400 1500; done' >/dev/null 2>&1 &
INPID=$!

# 🔴 Stretch markers are never written into the log file: logcat is buffering into the same file
#    and the lines interleave into garbage. They go into a sidecar file as epochs, and the reporter
#    lines them up by time.
PHASES="$LOG.phases"; : > "$PHASES"
mark() { echo "$1 $(date +%s.%N)" >> "$PHASES"; echo "[input-lag] ── $1 ──"; }

mark quiet;  sleep "$QUIET_S"

mark load
DISPLAY="$DISP" mpv --really-quiet --no-audio --loop-file=inf --fs --vo=x11 \
    --profile=sw-fast "$CLIP" >/dev/null 2>&1 &
sleep "$LOAD_S"

for p in $(pgrep -f 'mpv --really-quiet'); do kill "$p" 2>/dev/null; done
mark after; sleep "$AFTER_S"
mark end

cleanup
sleep 1
python3 "$HERE/input_lag_report.py" "$LOG" "$LABEL"
