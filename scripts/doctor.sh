#!/usr/bin/env bash
# Answers "can this host build the project" before you try.
# 🔑 It changes nothing. It only names what is missing.
#    Run it first on a new host - cheaper than decoding a build error.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
ROOT="$PWD"
APP="$ROOT"
ok(){ printf "  ✅ %s\n" "$*"; }
no(){ printf "  🔴 %s\n" "$*"; FAIL=1; }
warn(){ printf "  ⚠️  %s\n" "$*"; }
hd(){ printf "\n\033[1m%s\033[0m\n" "$*"; }
FAIL=0

# 🔑 Where the toolchain lives is scripts/_hostenv.sh's business; this only reports.
. "$ROOT/scripts/_hostenv.sh"; hostenv_resolve "$APP"

hd "1. JDK 17"
JH="$JDK_HOME"
if [ -n "$JH" ] && [ -x "$JH/bin/javac" ]; then
  ok "$("$JH/bin/javac" -version 2>&1) ($JH)"
  case "$("$JH/bin/javac" -version 2>&1)" in *" 17."*) ;; *) warn "not 17 - the Gradle config requires 17";; esac
else no "JDK 17 not found - add this host's path to JDK_CANDIDATES in scripts/_hostenv.sh"; fi

hd "2. Android SDK"
SDK="$SDK_ROOT"
if [ -n "$SDK" ] && [ -d "$SDK/platforms" ]; then
  ok "SDK: $SDK"
  [ -d "$SDK/platforms/android-36" ] && ok "platform android-36" || no "platform android-36 missing (compileSdk=36)"
  [ -x "$SDK/platform-tools/adb" ] && ok "adb" || warn "no adb - it will build, but nothing can be checked on a device"
  BT=$(ls -1 "$SDK"/build-tools 2>/dev/null | sort -V | tail -1)
  [ -n "$BT" ] && ok "build-tools $BT" || no "no build-tools"
else no "Android SDK not found (ANDROID_SDK_ROOT, sdk.dir in local.properties, or SDK_CANDIDATES in _hostenv.sh)"; fi

hd "3. NDK 28.2.13676358 and CMake  🔑 these build the native side (libjpeg-turbo)"
NDK=$(sed -n 's/^ndk\.dir=//p' "$APP/local.properties" 2>/dev/null | head -1)
if [ -n "${NDK:-}" ] && [ -d "$NDK" ]; then ok "NDK: $NDK"
elif [ -n "${SDK:-}" ] && [ -d "$SDK/ndk/28.2.13676358" ]; then ok "NDK: $SDK/ndk/28.2.13676358"
else no "NDK 28.2.13676358 missing (sdkmanager 'ndk;28.2.13676358')"; fi
[ -n "${SDK:-}" ] && ls -d "$SDK"/cmake/3.22.* >/dev/null 2>&1 && ok "cmake 3.22.x" \
  || warn "cmake 3.22.x not in the SDK (sdkmanager 'cmake;3.22.1')"

hd "4. libjpeg-turbo submodule  🔴 the usual reason a fresh clone does not build"
LJT="$APP/app/src/main/cpp/libjpeg-turbo"
# 🔑 It is a git submodule pinned to a pristine upstream commit, and it carries NO patch.
#    The build integrates it with ExternalProject_Add() precisely so that none is needed
#    (see app/src/main/cpp/CMakeLists.txt). So the only question here is "was it fetched".
if [ -f "$LJT/CMakeLists.txt" ]; then
  ok "libjpeg-turbo sources present"
  if grep -q "Bypassing add_subdirectory error" "$LJT/CMakeLists.txt" 2>/dev/null; then
    warn "this copy carries the old add_subdirectory patch - it should be pristine upstream now"
  fi
else
  no "libjpeg-turbo sources missing - the native library cannot be built"
  echo "     => git submodule update --init --recursive"
fi

hd "5. Signing (release and store upload)"
if [ -f "$APP/keystore.properties" ]; then
  ok "keystore.properties present - releases are signed with the upload key"
  KS=$(sed -n 's/^storeFile=//p' "$APP/keystore.properties" | head -1)
  if [ -f "$KS" ]; then ok "keystore present: $KS"
  else
    no "keystore.properties points at a key that is not there: $KS"
    echo "     => It may be a path from another host. Move the key here and fix storeFile, or"
    echo "        move keystore.properties aside to build with the debug key instead."
  fi
else
  warn "no keystore.properties - releases are signed with the debug key (fine for installing on a device)"
  echo "     🔴 A debug-signed build cannot be published. See scripts/keystore.properties.example."
fi

hd "Verdict"
[ "$FAIL" = 0 ] && echo "  ✅ ready to build - bash scripts/build.sh" \
                || echo "  🔴 fix the items marked 🔴 above first."
exit $FAIL
