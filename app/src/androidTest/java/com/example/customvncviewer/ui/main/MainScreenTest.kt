// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [io.github.zirize.vncviewerforgames.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen(FAKE_DATA) }
  }

  @Test
  fun firstItem_exists() {
    FAKE_DATA.forEach { composeTestRule.onNodeWithText("Hello $it!").assertExists() }
  }
}

private val FAKE_DATA = listOf("Sample1", "Sample2", "Sample3")
