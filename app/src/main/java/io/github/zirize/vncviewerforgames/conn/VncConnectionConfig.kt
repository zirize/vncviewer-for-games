// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.conn

import io.github.zirize.vncviewerforgames.BuildConfig
import com.tigervnc.rfb.Encodings
import com.tigervnc.rfb.Security

/** Supported security types. The values are the RFB protocol numbers. */
enum class VncSecurity(val id: Int) {
    NONE(Security.secTypeNone),
    VNC_AUTH(Security.secTypeVncAuth),
}

/** Real (non-pseudo) encodings. */
enum class VncEncoding(val id: Int) {
    TIGHT(Encodings.encodingTight),
    ZRLE(Encodings.encodingZRLE),
    HEXTILE(Encodings.encodingHextile),
    RRE(Encodings.encodingRRE),
    COPY_RECT(Encodings.encodingCopyRect),
    RAW(Encodings.encodingRaw),
}

/** The pixel format to ask the server for. */
enum class VncPixelFormatPreset {
    /** 32bpp, depth 24. Best quality (the current default). */
    BGRX_8888_32BPP,
    /** 16bpp. Half the bandwidth, coarser colour. */
    RGB_565_16BPP,
    /** Take whatever the server offers (no SetPixelFormat is sent). */
    SERVER_NATIVE,
}

/**
 * Everything negotiated up front for a VNC connection, in one place.
 *
 * 🔑 **When changes apply** — most of this is negotiated *at connect time*, so changing a value
 *    takes effect **from the next connection**. The exception is [viewOnly], which the send gate
 *    consults every time and therefore applies immediately.
 */
data class VncConnectionConfig(
    // ── Where to connect ──
    var host: String = DEV_HOST,
    var port: Int = DEV_PORT,
    /**
     * true = share the screen with other clients.
     * 🔴 false makes the server disconnect whoever is already there. This used to be hard-coded false.
     */
    var shared: Boolean = true,

    // ── Authentication ──
    /** Acceptable security types, in preference order; the first of the server's offers that matches wins. */
    var securityTypes: List<VncSecurity> = listOf(VncSecurity.NONE, VncSecurity.VNC_AUTH),
    /**
     * A password supplied in advance. ⚠️ VNC authentication uses **at most 8 bytes** by protocol;
     * anything longer is truncated. null calls [onPasswordRequired] instead.
     */
    var password: String? = DEV_PASSWORD,
    /** Called the moment the server asks for a password; where the UI puts up a dialog and returns the answer. */
    var onPasswordRequired: (() -> String?)? = null,

    // ── Negotiation: encodings ──
    /** Sent to the server in this order; earlier means preferred. */
    var encodings: List<VncEncoding> = listOf(
        VncEncoding.TIGHT, VncEncoding.ZRLE, VncEncoding.HEXTILE,
        VncEncoding.RRE, VncEncoding.COPY_RECT, VncEncoding.RAW,
    ),
    /** 0 (fast, large) to 9 (slow, small). -1 means do not request it. */
    var compressLevel: Int = 2,
    /**
     * 0 (low quality) to 9 (high). -1 means do not request it.
     * 🔑 **At -1 the server does not use JPEG at all**, which is very costly on full-motion content.
     * Measured 2026-09-15 with video playing: going from off to 8 took bandwidth from 9.61 to
     *    3.04 MB/s (3.2x less) and throughput from 4.39 to 4.96 Mpx/s, with no visible artefacts.
     * ℹ️ The server (Tight) looks at each rect and picks JPEG for photographic content, lossless
     *    palette for dots and text. 2D pixel art goes to the lossless path by itself — which is
     *    why leaving this on is safe.
     */
    var qualityLevel: Int = 8,
    /**
     * JPEG chroma subsampling. 🔑 This saves bytes by reducing **colour** information only,
     * leaving luma — and therefore sharpness — untouched.
     *
     * [SUBSAMP_ADAPTIVE] (−2) = **the app chooses from the measured load** ([AutoSubsampling]).
     * −1 = do not request it (the server decides from qualityLevel).
     * 0 = 4:4:4, 1 = 4:2:0, 2 = 4:2:2, 3 = greyscale (`SUBSAMP_*` in
     * [com.tigervnc.rfb.JpegCompressor]).
     *
     * The default became **automatic** on 2026-09-16 (first changed to fixed 4:2:0, then raised
     * to automatic). The evidence: on noise at 2340×1080 the ceiling went from 62.0 to
     * **92.2 Mpx/s (+49%)**, and bytes per pixel from 1.353 to 0.885.
     * 🔴 Sharpness (luma) is **identical** to 4:4:4. What softens is colour edges, nothing else.
     * 🔑 Automatic **starts at 4:2:0 and only climbs when things are idle**, so the first few
     *    seconds are never worse than fixed 4:2:0 (see [AutoSubsampling] and the starting-rung
     *    note in `VncEngine`).
     */
    var subsampling: Int = SUBSAMP_ADAPTIVE,

    // ── Negotiation: pixel format ──
    var pixelFormat: VncPixelFormatPreset = VncPixelFormatPreset.BGRX_8888_32BPP,

    // ── Negotiation: pseudo-encodings ──
    // Under trial since 2026-09-16, defaulting to on. The reasoning: at the load ceiling
    //    **nothing was saturated** — server CPU 28%, 576 of 1056 Mbps on the wire, the client's
    //    main thread idle two-thirds of the time, and 1.1 of 4 workers busy. And yet it stopped at
    //    22 fps, which says the limit is the request/response round trip, not throughput.
    //    Continuous updates remove that round trip: the server keeps sending without waiting to be
    //    asked.
    var continuousUpdates: Boolean = true,
    var desktopResize: Boolean = true,
    /** ⚠️ Historic warning: with this on the server stops drawing the cursor into the framebuffer. */
    /**
     * 🔑 **On by default.** With it on the server sends the cursor *shape* instead of compositing
     *    it into the framebuffer, so the app draws the cursor where the finger is — **a late frame
     *    no longer means a late cursor**.
     *    It was off before 2026-09-16, because `setCursor` was a no-op and turning it on made the
     *    cursor vanish entirely.
     */
    var cursorShape: Boolean = true,
    /** ⚠️ Opens the extended clipboard path. Has been known to hang x11vnc-family servers. */
    var clipboard: Boolean = false,

    // ── Input ──
    /**
     * View only. When true, **not one** pointer, key or clipboard event is sent.
     * 🔑 Blocking happens in exactly one place, VncEngine's send gate, so new input paths cannot leak.
     */
    var viewOnly: Boolean = false,
) {
    fun encodingIds(): IntArray = encodings.map { it.id }.toIntArray()

    /**
     * Which of the "next connection" settings now differ from [connected], the snapshot taken at
     * connect time.
     *
     * 🔑 This is what the settings screen draws its "pending" badge from. Writing "applies from
     * the next connection" in words does not work — people read it and still believe it changed
     * now. So the screen shows **the difference itself** rather than an explanation.
     * 🚫 [viewOnly] is excluded because it applies immediately, and so are callbacks
     * ([onPasswordRequired]).
     */
    fun diffRequiringReconnect(connected: VncConnectionConfig): List<String> = buildList {
        if (host != connected.host) add("host")
        if (port != connected.port) add("port")
        if (shared != connected.shared) add("shared")
        if (securityTypes != connected.securityTypes) add("securityTypes")
        if (password != connected.password) add("password")
        if (encodings != connected.encodings) add("encodings")
        if (compressLevel != connected.compressLevel) add("compressLevel")
        if (qualityLevel != connected.qualityLevel) add("qualityLevel")
        if (subsampling != connected.subsampling) add("subsampling")
        if (pixelFormat != connected.pixelFormat) add("pixelFormat")
        if (continuousUpdates != connected.continuousUpdates) add("continuousUpdates")
        if (desktopResize != connected.desktopResize) add("desktopResize")
        if (cursorShape != connected.cursorShape) add("cursorShape")
        if (clipboard != connected.clipboard) add("clipboard")
    }

    companion object {
        /** [subsampling] = "the app chooses from the load". 🔑 **Not** the same as −1, which defers to the server. */
        const val SUBSAMP_ADAPTIVE = -2

        /**
         * The **development** default target. Overridden by `vnc.dev.host` / `vnc.dev.port` /
         * `vnc.dev.password` in `local.properties`, which is untracked — so that pointing at a
         * different server does not mean editing source, and above all so that **no password is
         * ever written into the repository**.
         *
         * 🔴 **This must default to an empty string. Never put a real address here.**
         *    An address written here ends up in **the public repository and in every published
         *    build**, and anyone who installs the app then **tries to connect to someone else's
         *    machine.** That is not hypothetical: it was in exactly that state and had to be
         *    removed while preparing this repository for release.
         * 🔑 Empty is not a dead end — [StartupFailureWatcher] opens settings when the first
         *    connection fails, so **a fresh install lands on the settings screen by itself**.
         */
        val DEV_HOST: String = BuildConfig.VNC_DEV_HOST
        val DEV_PORT: Int =
            BuildConfig.VNC_DEV_PORT.toIntOrNull() ?: 5900
        val DEV_PASSWORD: String? =
            BuildConfig.VNC_DEV_PASSWORD.ifBlank { null }
    }
}
