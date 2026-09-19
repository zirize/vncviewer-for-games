// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.zirize.vncviewerforgames.theme.CustomVNCViewerTheme
import io.github.zirize.vncviewerforgames.ui.main.MainScreen

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 🔑 This is the current way to ask for an edge-to-edge window, and it does what the
    //    `setDecorFitsSystemWindows(window, false)` line below does, so the two agree rather than
    //    fight. Before 2026-09-19 the import was here and the call was not, which is no call at all.
    // ℹ It was added on a report that Play Console asks for edge-to-edge and checks for this exact
    //    call. **That turned out to be unverified** - the Console shows no such item for this app
    //    (checked 2026-09-19, four screens). The line stays because it is the right API for a
    //    window that draws behind the bars, not because a store demanded it.
    // 🔴 It must come **before** the bar-hiding calls: those are what we actually want on a
    //    gamepad overlay, and whatever style this sets applies only to the transient bars that
    //    appear on a swipe.
    enableEdgeToEdge()

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    
    val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
      CustomVNCViewerTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainScreen() } }
    }
  }
}
