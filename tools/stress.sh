#!/bin/bash
# 🔑 Toolchain paths and the package name each live in exactly one place: scripts/_hostenv.sh and
#    app/build.gradle.kts.
#    2026-09-16: PATH pointed at another host's SDK, and PKG had been set to the **code namespace**
#       rather than the applicationId, so adb was matching no app at all.
#       🔴 That is what a broken instrument looks like: it quietly returns "nothing"
#       (see "rule one" in tools/README.md).
. "$(dirname "$0")/../scripts/_hostenv.sh"; hostenv_resolve "$(dirname "$0")/.."
export PATH="$SDK_ROOT/platform-tools:$PATH"
PKG=tech.doldam.remotepad          # what adb sees is the applicationId, NOT the namespace
D="$(dirname "$0")/stress"; rm -rf "$D"; mkdir -p "$D"
adb shell am force-stop $PKG >/dev/null 2>&1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
echo "-- 12 seconds of continuous swiping, to keep the server screen changing --"
timeout 20 adb logcat -v time VncEngine:D '*:S' > "$D/log.txt" 2>&1 &
LOGPID=$!
T0=$(date +%s.%N)
for i in $(seq 1 12); do
  adb shell input swipe 600 300 1800 900 250 >/dev/null 2>&1
  adb shell input swipe 1800 900 600 300 250 >/dev/null 2>&1
done
T1=$(date +%s.%N)
echo "swipe window: $T0 .. $T1"
wait $LOGPID 2>/dev/null
echo "  PointerEvents sent: $(grep -c 'sendPointerEvent: wrote' "$D/log.txt")"
echo "  UpdateEnds received: $(grep -c 'framebufferUpdateEnd' "$D/log.txt")"
