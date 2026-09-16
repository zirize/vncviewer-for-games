#!/bin/bash
# Does stutter **persist after the load stops**?
#   0-12s   quiet: only a small window (320x180) flashing -> updates continue, but small
#  12-32s   load:  full-screen stripes played on top
#  32-75s   load **stops** (only the full-screen part; the small window keeps going) <- the point
# 🔑 Why the quiet stretch still has small updates: worstGap grows when there is simply nothing to
#    do, so with no updates at all you cannot tell "the stutter persisted" from "there was nothing
#    to send".
set -u
ADB=/mnt/sdb1/android_tools/platform-tools/adb
SC="${AFTERLOAD_OUT:-/tmp}"
LABEL="${1:-run1}"

for p in $(pgrep -f 'mpv --really-quiet'); do kill "$p" 2>/dev/null; done
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
"$ADB" shell cmd statusbar collapse >/dev/null 2>&1
"$ADB" shell svc power stayon true >/dev/null 2>&1

# Background (the small window) - the small updates during the quiet stretch
setsid env DISPLAY=:5 mpv --really-quiet --no-audio --loop-file=inf --no-border \
  --geometry=+40+40 --vo=x11 --profile=sw-fast /tmp/bg_small.mp4 >/dev/null 2>&1 &
"$ADB" shell sleep 2
BGPID=$(pgrep -f 'bg_small' | head -1)

"$ADB" shell am force-stop tech.doldam.remotepad >/dev/null 2>&1
"$ADB" shell monkey -p tech.doldam.remotepad -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
"$ADB" shell sleep 8

"$ADB" logcat -T 1 -s PERF:I -v epoch > "$SC/after_$LABEL.log" 2>&1 &
LOGPID=$!
T0=$(date +%s)
"$ADB" shell sleep 12
setsid env DISPLAY=:5 mpv --really-quiet --no-audio --loop-file=inf --fs \
  --vo=x11 --profile=sw-fast /tmp/stress_stripes.mp4 >/dev/null 2>&1 &
"$ADB" shell sleep 1
LOADPID=$(pgrep -f 'stress_stripes' | head -1)
echo "  [12s] load started (pid $LOADPID)"
"$ADB" shell sleep 19
kill "$LOADPID" 2>/dev/null
TSTOP=$(( $(date +%s) - T0 ))
echo "  [${TSTOP}s] load stopped <- this is the part that matters"
"$ADB" shell sleep 43
kill $LOGPID 2>/dev/null
kill "$BGPID" 2>/dev/null
echo "$T0 $TSTOP" > "$SC/after_${LABEL}.t0"
echo "  done - $(grep -c 'fps=' "$SC/after_$LABEL.log") seconds collected"
