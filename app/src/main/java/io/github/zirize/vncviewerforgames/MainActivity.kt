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
