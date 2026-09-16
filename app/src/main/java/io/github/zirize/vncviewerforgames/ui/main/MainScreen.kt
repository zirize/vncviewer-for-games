// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zirize.vncviewerforgames.VncSurfaceView
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.zirize.vncviewerforgames.conn.VncConnectionState
import io.github.zirize.vncviewerforgames.input.LatchState
import androidx.compose.ui.platform.LocalContext
import io.github.zirize.vncviewerforgames.BuildConfig
import io.github.zirize.vncviewerforgames.overlay.OverlayProfileLoader
import io.github.zirize.vncviewerforgames.overlay.GamepadPanel
import io.github.zirize.vncviewerforgames.overlay.OverlayPanel
import io.github.zirize.vncviewerforgames.overlay.UiTarget
import io.github.zirize.vncviewerforgames.overlay.panelOverlapPx
import io.github.zirize.vncviewerforgames.overlay.panelWidthPx
import io.github.zirize.vncviewerforgames.ui.settings.SettingsSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.zirize.vncviewerforgames.theme.CustomVNCViewerTheme

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
  var view by remember { mutableStateOf<VncSurfaceView?>(null) }
  // Remote screen width. 🔑 The panel width no longer comes from here (it comes from the screen
  // height). Two uses remain: a signal that the connection is up and the remote size is known, and
  // the overlap log below.
  var remoteWidth by remember { mutableIntStateOf(0) }
  var latch by remember { mutableStateOf<Map<Int, LatchState>>(emptyMap()) }
  var showSettings by remember { mutableStateOf(false) }
  var connState by remember { mutableStateOf<VncConnectionState>(VncConnectionState.Connecting("")) }

  Box(modifier = modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VncSurfaceView(context).apply {
                // 🔴 **Watching a remote screen involves no hand movement.** Just looking at a
                //    game reads to the system as "no interaction", so it turns the screen off, the
                //    activity stops, and the VNC connection drops. That is what the "the app dies"
                //    report on 2026-09-16 actually was: `screen_off_timeout` was 15 seconds. Not a
                //    crash, not the low-memory killer — the screen went off.
                // 🔑 It is a View property, so it is only held while this view is attached; sending
                //    the app to the background releases it by itself.
                //    ⚠️ It cannot stop thermal protection from turning the screen off; the system
                //    wins that one.
                keepScreenOn = true
                // 🔑 Focus is required to receive key input, and inside Compose it does not arrive by itself.
                isFocusableInTouchMode = true
                requestFocus()
                onDesktopSize = { w, _ -> remoteWidth = w }
                onLatchChanged = { latch = it }
                onStateChanged = { connState = it }
                onConnectionFatal = { connState = VncConnectionState.Failed(it) }
                // 🔴 If the very first connection fails, settings opens itself - hand them the
                //    screen they can fix it on.
                //    🔑 `releaseAllInput()` has already happened on the view side, in the same
                //    order as opening it by hand.
                onOpenSettings = { showSettings = true }
                view = this
            }
        },
        update = { }
    )

    val v = view
    // 🔴 **Drawn regardless of whether we are connected**, after the 2026-09-16 report that "if
    //    it fails to connect at startup, the buttons do not appear".
    //    The threshold used to be `remoteWidth > 0` (i.e. we know the remote size, i.e. we are
    //    connected).
    //    🔴 But that makes **the settings button disappear exactly when the connection failed** —
    //    the only way in to fix the address would itself require a working connection, so one
    //    wrong address left no option but to uninstall the app.
    //    (This is the real-world version of the "never lock yourself out" rule that
    //    `OverlayHitTest.hasSettingsEntry` enforces.)
    // 🔑 It is safe because the panel width now comes from the screen, so `remoteWidth` is not
    //    needed, and pressing a button while disconnected is swallowed quietly by
    //    `dispatchKeys` (`engine ?: return`).
    if (v != null) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // 🔑 48dp - the buttons are 43.4dp, short of the Material minimum. This grows what you
        //    can touch without changing what is drawn. Growing them makes them overlap, and where
        //    they do, the nearer centre wins.
        val minTouchPx = with(density) { 48.dp.toPx() }

        // 🔴 The panel width comes from the device screen, not the remote resolution. It used to
        //    use the margin (screen width minus remote width) directly, so a remote that filled the
        //    screen made **every button disappear**. The evidence and the trade-off are in the
        //    `overlay.panelWidthPx` comment.
        // 🔑 **The layout lives in JSON in the assets, not in code** (`profiles/` → assets).
        //    Which profile gets baked in is decided at build time:
        //      ./gradlew :app:assembleRelease -PvncProfile=lefty.json
        //    🔴 An unreadable profile does not crash here: the loader opens a rescue profile
        //       containing nothing but the settings button.
        val context = LocalContext.current
        val profile = remember { OverlayProfileLoader.load(context, BuildConfig.VNC_PROFILE) }

        val panelPx = panelWidthPx(heightPx, profile)
        val panelDp = with(density) { panelPx.toDp() }
        val overlapPx = panelOverlapPx(screenPx, remoteWidth.toFloat(), panelPx)

        // 🔑 **If it overlapped, log it.** By eye you cannot tell "is that where it belongs".
        //    (The rule learned from the cursor: if it cannot be told apart by looking, leave evidence.)
        LaunchedEffect(remoteWidth, panelPx, screenPx) {
          // 🔑 `remote=0` means the connection is not up yet - not the same as "does not overlap".
          val where = when {
            remoteWidth <= 0 -> " - remote size not known yet (not connected, or failed). Buttons are drawn anyway"
            overlapPx > 0f -> " - overlapping the remote picture"
            else -> " - fits inside the margin"
          }
          Log.i("VncOverlay", "panel=${panelPx.toInt()}px screen=${screenPx.toInt()}x${heightPx.toInt()}" +
              " remote=$remoteWidth overlap=${overlapPx.toInt()}px" + where)
        }

        GamepadPanel(
            view = v, panel = OverlayPanel.LEFT,
            widthPx = panelPx, heightPx = heightPx, minTouchPx = minTouchPx,
            profile = profile, latch = latch,
            onUi = { target -> if (target == UiTarget.SETTINGS) openSettings(v) { showSettings = true } },
            modifier = Modifier.align(Alignment.CenterStart).width(panelDp).fillMaxHeight(),
        )
        GamepadPanel(
            view = v, panel = OverlayPanel.RIGHT,
            widthPx = panelPx, heightPx = heightPx, minTouchPx = minTouchPx,
            profile = profile, latch = latch,
            onUi = { target -> if (target == UiTarget.SETTINGS) openSettings(v) { showSettings = true } },
            modifier = Modifier.align(Alignment.CenterEnd).width(panelDp).fillMaxHeight(),
        )
      }
    }

    // 🔴 Say so when the connection is not currently live - otherwise it is indistinguishable from an old frame.
    if (v != null) {
      ConnectionBanner(
          state = connState,
          onRetry = { v.retryConnection() },
          modifier = Modifier.align(Alignment.TopCenter),
      )
    }

    if (v != null && showSettings) {
      SettingsSheet(view = v, onDismiss = { showSettings = false })
    }
  }
}

/**
 * 🔴 Releases every held input **the moment** settings opens.
 * The bottom-left entry shares a panel with the `CTRL` latch button, so they are a finger's width
 * apart: it is easy to walk in with CTRL still armed, and then CTRL stays down in the game while
 * the user is looking at a settings sheet — **where they cannot see it**.
 */
private fun openSettings(view: VncSurfaceView, show: () -> Unit) {
    view.releaseAllInput()
    show()
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  CustomVNCViewerTheme { MainScreen() }
}
