#!/usr/bin/env bash
# One line to build.
#   bash scripts/build.sh            # release APK - the everyday one
#   bash scripts/build.sh debug      # debug APK  🔴 noticeably slower, see below
#   bash scripts/build.sh aab        # store bundle plus a signature check
#   bash scripts/build.sh test       # unit tests only, no device needed
#   bash scripts/build.sh preview    # validate the profiles and render the SVG previews
#   bash scripts/build.sh install    # release build, then push it to a device
#
# 🔑 **Always go through this script rather than calling `./gradlew` directly.** The wrapper needs
#    JAVA_HOME, and a host where the JDK lives inside the Android toolchain (no system `java`) has
#    none - `./gradlew` then dies with "JAVA_HOME is not set". This script resolves it through
#    scripts/_hostenv.sh first. Anything after the mode is passed straight to gradle, so
#    `bash scripts/build.sh release -PvncProfile=lefty.json` works the same as the gradle form.
#
# 🔴 **Use release for everyday work.** A debug build is `debuggable`, so ART gives up
#    optimisations - measured 2026-09-15 on full-motion content: 20.8 fps debug against 33.2 fps
#    release, with more stutter as well. Use debug only when actually attaching a debugger.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"; APP="$ROOT"          # 🔑 this script's parent is the Gradle root

# 🔑 Toolchain paths live in exactly one place, scripts/_hostenv.sh.
. "$ROOT/scripts/_hostenv.sh"; hostenv_resolve "$APP"
[ -n "$JDK_HOME" ] || { echo "🔴 no JDK 17 - run: bash scripts/doctor.sh"; exit 1; }
[ -n "$SDK_ROOT" ] || { echo "🔴 no Android SDK - run: bash scripts/doctor.sh"; exit 1; }
export JAVA_HOME="$JDK_HOME"
SDK="$SDK_ROOT"; export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"

cd "$APP" || exit 1
G=./gradlew
MODE="${1:-release}"
case "$MODE" in
  test)    shift 2>/dev/null; exec "$G" :app:testDebugUnitTest "$@" ;;
  preview) shift 2>/dev/null; exec "$G" :app:previewProfiles "$@" ;;
  debug)   TASK=:app:assembleDebug;   OUT=app/build/outputs/apk/debug/app-debug.apk ;;
  release|install) TASK=:app:assembleRelease; OUT=app/build/outputs/apk/release/app-release.apk ;;
  aab)     TASK=:app:bundleRelease;   OUT=app/build/outputs/bundle/release/app-release.aab ;;
  *) echo "usage: bash scripts/build.sh [release|debug|aab|test|preview|install]"; exit 2 ;;
esac

# 🔴 **Stops development connection details being baked into a published artefact.**
#    `vnc.dev.*` in `local.properties` is **compiled into BuildConfig** (VncConnectionConfig's
#    DEV_HOST/DEV_PORT/DEV_PASSWORD). That is convenient for measurement builds, but confirmed on
#    2026-09-16: build an `aab` in that state and **the connection password ships inside the app**.
#    It is where the "no passwords in the repository" contract leaks out through the *artefact*.
#    🔑 To clear it, blank the `vnc.dev.*` entries in `local.properties`.
#    🚫 To build anyway, set `ALLOW_DEV_CREDS=1` - only when it is not going to a store.
if [ "$MODE" = "aab" ] && [ "${ALLOW_DEV_CREDS:-0}" != "1" ]; then
  LP="$APP/local.properties"
  DEV_PW=$(grep -E '^vnc\.dev\.password=' "$LP" 2>/dev/null | cut -d= -f2-)
  DEV_HOST=$(grep -E '^vnc\.dev\.host=' "$LP" 2>/dev/null | cut -d= -f2-)
  if [ -n "$DEV_PW" ] || [ -n "$DEV_HOST" ]; then
    echo "🔴 refusing to build a publishable AAB - local.properties still has dev connection details."
    [ -n "$DEV_PW" ]   && echo "   - vnc.dev.password is set  <- the password would ship inside the app"
    [ -n "$DEV_HOST" ] && echo "   · vnc.dev.host = $DEV_HOST"
    echo "   => blank the vnc.dev.* entries in $LP and build again."
    echo "   (not going to a store? ALLOW_DEV_CREDS=1 bash scripts/build.sh aab)"
    exit 1
  fi
fi

# 🔑 Anything after the first argument is passed straight to gradle, for A/B measurement.
#    e.g. bash scripts/build.sh release -PvncDecoderThreads=1
shift 2>/dev/null || true
echo "▸ $TASK ${*:+$*}"
# 🔴 A pipeline's exit code is the *last* command's (tail), so a failing gradle still gives 0.
#    2026-09-16: that is how "build failed, plus a stale APK left by another host" got reported
#    as success.
#    => gradle's own exit code is read separately via PIPESTATUS. Presence of an artefact is not
#       evidence on its own.
"$G" $TASK -q "$@" 2>&1 | grep -vE "add_subdirectory|^C/C\+\+" | tail -5
GRADLE_RC=${PIPESTATUS[0]}
[ "$GRADLE_RC" = 0 ] || { echo "🔴 build failed (gradle exit $GRADLE_RC) - any artefact below is stale."; exit 1; }
[ -f "$OUT" ] || { echo "🔴 no artefact: $OUT"; exit 1; }
# Confirm it was just built: anything older than five minutes is not from this run.
[ -z "$(find "$OUT" -newermt '-5 minutes' 2>/dev/null)" ] && \
  echo "  ⚠️  $OUT was not created just now - it may be stale."
echo "✅ $OUT  ($(du -h "$OUT" | cut -f1))"

# For an AAB, always check which key signed it: a debug-signed bundle cannot be published.
[ "$MODE" = "aab" ] && bash "$ROOT/scripts/verify-signing.sh" "$APP/$OUT"

if [ "$MODE" = "install" ]; then
  ADB="$SDK/platform-tools/adb"
  "$ADB" install -r "$OUT" | tail -1
  "$ADB" shell am force-stop tech.doldam.remotepad
  "$ADB" shell monkey -p tech.doldam.remotepad -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "✅ installed and launched"
fi
