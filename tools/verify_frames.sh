#!/bin/bash
# A regression test for "is the VNC screen actually updating".
# It looks at two independent pieces of evidence: the app's own PERF counters (fps per second) and
# a binary comparison of two screenshots.
# 🔑 Both must hold to PASS. The screenshot comparison alone reports "identical" when the *server*
#    screen did not change, and the log alone cannot see rendering dying after a successful decode.
# 🔑 Toolchain paths and the package name each live in one place: scripts/_hostenv.sh and
#    app/build.gradle.kts.
#    2026-09-16: PATH pointed at another host's SDK, and PKG had been set to the **code namespace**
#       rather than the applicationId, so adb was matching no app at all.
#       🔴 That is what a broken instrument looks like: it quietly returns "nothing"
#       (see "rule one" in tools/README.md).
. "$(dirname "$0")/../scripts/_hostenv.sh"; hostenv_resolve "$(dirname "$0")/.."
export PATH="$SDK_ROOT/platform-tools:$PATH"
PKG=tech.doldam.remotepad          # what adb sees is the applicationId, NOT the namespace
D="$(dirname "$0")/verify"; rm -rf "$D"; mkdir -p "$D"

adb shell am force-stop $PKG >/dev/null 2>&1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
timeout 22 adb logcat -v time PERF:I VncSurfaceView:D '*:S' > "$D/log.txt" 2>&1 &
LOGPID=$!

sleep 7;  adb exec-out screencap -p > "$D/a.png" 2>/dev/null; TA=$(date +%H:%M:%S)
sleep 8;  adb exec-out screencap -p > "$D/b.png" 2>/dev/null; TB=$(date +%H:%M:%S)
wait $LOGPID 2>/dev/null

PERFN=$(grep -c "fps=" "$D/log.txt")
FPSOK=$(grep -o "fps=[0-9.]*" "$D/log.txt" | cut -d= -f2 | awk '$1>0' | wc -l)
echo "-- log evidence --"
echo "  PERF lines: $PERFN   (with fps>0: $FPSOK)"
grep -o "fps=[0-9.]* .*jpegRect=[0-9]*" "$D/log.txt" | tail -2 | sed 's/^/    /'
if grep -q "Fatal:" "$D/log.txt"; then echo "  🔴 fatal failure: $(grep -m1 'Fatal:' "$D/log.txt")"; fi
echo "-- screen evidence (a=$TA, b=$TB) --"
sha256sum "$D/a.png" "$D/b.png" | sed 's|.*/|  |'
if cmp -s "$D/a.png" "$D/b.png"; then SCREEN=SAME; else SCREEN=DIFF; fi
echo "  binary comparison: $SCREEN"
echo "──────────────"
if [ "$SCREEN" = "DIFF" ] && [ "$FPSOK" -ge 3 ]; then
  echo "RESULT: PASS - the screen is updating (fps>0 reported ${FPSOK} times, screen changed)"; exit 0
else
  echo "RESULT: FAIL - not updating (fps>0 reported ${FPSOK} times, screen $SCREEN)"; exit 1
fi
