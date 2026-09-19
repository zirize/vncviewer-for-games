# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 Bill Kang
#
# R8 rules for the release build.
#
# 🔑 **Why this file is short.** Almost nothing here needs a rule: the JSON profile parser maps
#    strings to enums with an explicit `when` on literals (not `valueOf`/`name`), there is no
#    `Resources.getIdentifier`, no serialization library, and the only `Class.forName` calls name
#    **JDK** classes (`java.net.UnixDomainSocketAddress` in com/tigervnc/network/UnixSocket.java),
#    which R8 never renames. What is listed below is what a scan actually turned up.
#
# 🔴 **Unit tests cannot check any of this.** They run on the JVM, on unminified classes. A broken
#    rule here shows up only on a device, as a crash or as a setting that silently resets.
#    ⇒ After changing this file, install the release APK on a device and actually connect.

# ── JNI: the native symbol is built from the class and method name ──────────────────────────
# 🔴 libvnc_jni.so exports `Java_io_github_zirize_vncviewerforgames_JpegDecoder_
#    decodeJpegToFramebuffer` (app/src/main/cpp/vnc_jni.cpp). That is **static registration**:
#    the runtime finds the function by spelling the class and method name out. Rename either and
#    the lookup fails - and it fails at the first JPEG rectangle, i.e. *after* a successful
#    connection, which looks like "decoding is broken", not "the build is broken".
# 🔑 `proguard-android-optimize.txt` already keeps native methods; this states it for this class so
#    that a future change to the defaults cannot quietly take it away.
-keep class io.github.zirize.vncviewerforgames.JpegDecoder {
    native <methods>;
}

# ── Enum constants that are persisted by name ───────────────────────────────────────────────
# 🔴 SettingsCodec writes `pointer.mode.name` into the store and reads it back by comparing
#    `it.name == name` (settings/SettingsCodec.kt). Those strings are already sitting in users'
#    stores, so the constant names are **file format**, not an implementation detail.
#    Let R8 rename or unbox them and the saved pointer mode silently falls back to the default.
-keepclassmembers enum io.github.zirize.vncviewerforgames.input.PointerMode {
    <fields>;
}

# ── Keep the edge-to-edge call **findable**, not just effective ─────────────────────────────
# 🔴 Without this, R8 inlines `enableEdgeToEdge()` straight into MainActivity.onCreate. The
#    behaviour is identical - the same window calls happen - but the name
#    `androidx.activity.EdgeToEdge.enable` **disappears from the bytecode**, and a static scan
#    looking for that call finds nothing. Measured: 5 references with this rule, 0 without.
# ℹ Cost: **nothing measurable.** Both APKs came out at exactly 5,453,117 bytes (different
#    md5, so the two builds really did differ - 5 references vs 0).
# ❓ Whether Play's edge-to-edge check really is such a scan is second-hand (from the mobile-fd
#    session, working on a Flutter app). It is cheap insurance either way, so it stays until
#    someone can check the Console panel itself.
-keep class androidx.activity.EdgeToEdge { *; }
-keep class androidx.activity.EdgeToEdgeApi* { *; }
