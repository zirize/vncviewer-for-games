// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zirize.vncviewerforgames.BuildConfig
import io.github.zirize.vncviewerforgames.R
import io.github.zirize.vncviewerforgames.VncSurfaceView
import io.github.zirize.vncviewerforgames.input.PointerMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The settings sheet. The way in is the **bottom-left `⋯` button**.
 *
 * 🔴 **Opening it calls [VncSurfaceView.releaseAllInput].** That entry point shares a panel with
 * the `CTRL` latch button, so they are a finger's width apart: it is easy to walk in here with
 * CTRL still armed, and then CTRL stays down in the game while the user is looking at this screen —
 * **nothing anywhere says so.**
 *
 * 🔑 **What you need in a hurry is at the top.** This opens mid-game; twenty-seven controls in a
 * flat list would be unusable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(view: VncSurfaceView, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 🔑 The sheet has to show the *current* values, so they are read once on open and held in Compose state.
    var tick by remember { mutableStateOf(0) }
    // 🔴 **Every row goes through here, and that is the only reason settings survive a
    //    restart.** The rows assign to `view.connectionConfig` / `pointerConfig` / `keyConfig`
    //    directly, so there is nowhere else that can see a change happen. Add a row that skips
    //    `changed()` and it will look like it works and be gone at the next launch - which is the
    //    bug this was written to fix (the address was not being kept).
    fun changed() { tick++; view.saveSettings() }

    var confirming by remember { mutableStateOf<RiskyOption?>(null) }

    // 🔴 **Sending closes the sheet** (asked for on the device, 2026-09-17). The text lands on a
    //    screen this sheet is covering, so staying open means typing into something you cannot
    //    see - and the second press of Send would be a blind repeat. Closing *is* the confirmation.
    // 🔑 `clearFocus()` first: it puts the phone's keyboard away. Without it the keyboard can
    //    outlive the sheet and sit over the game with nothing to type into.
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    fun closeSheet() {
        focus.clearFocus()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            // 🔑 `imePadding` because this sheet now has a text field people actually type into.
            //    Without it the phone's keyboard covers the bottom half of the sheet and there is
            //    no way to scroll what it covers into view.
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            @Suppress("UNUSED_EXPRESSION") tick   // the hook that redraws when a value changes

            AppTitleRow()

            PendingBar(view, onReconnect = { view.retryConnection(); changed() })

            // 🔴 **The keyboard is first, above everything.** It is not a setting at all - it is a
            //    thing you came here to *do*, in the middle of a game, when the remote side asked
            //    for a name or a password and the on-screen panel has no letters on it. Anything
            //    below the fold would be missed at the moment it is needed.
            // 🔑 Typing happens in the phone's own keyboard, which is what makes Korean (and every
            //    other IME language) work without this app knowing anything about it.
            SectionTitle(stringResource(R.string.settings_section_keyboard),
                stringResource(R.string.settings_sends_now))
            SendTextRow { text, withReturn -> view.sendText(text, withReturn); closeSheet() }

            // 🔑 **There is deliberately no disconnect or reconnect button here.**
            //    (a) An ordinary failure retries by itself once a second, so there is nothing to press.
            //    (b) To really disconnect, close the app.
            //    (c) Changing a setting and reconnecting is already covered by the pending bar above.
            //    🔴 Manual retry is only needed when automatic retry is *suppressed* (auth failure),
            //       and that belongs where it happens, not in settings → the connection banner.
            SectionTitle(stringResource(R.string.settings_section_quick),
                stringResource(R.string.settings_applies_now))
            SwitchRow(
                stringResource(R.string.settings_trackpad_mode),
                view.pointerConfig.mode == PointerMode.TRACKPAD,
            ) { on ->
                view.pointerConfig = view.pointerConfig.copy(
                    mode = if (on) PointerMode.TRACKPAD else PointerMode.ABSOLUTE)
                changed()
            }
            SwitchRow(stringResource(R.string.settings_two_finger_scroll), view.pointerConfig.twoFingerScroll) {
                view.pointerConfig = view.pointerConfig.copy(twoFingerScroll = it); changed()
            }
            SwitchRow(stringResource(R.string.settings_long_press_right_click), view.pointerConfig.longPressRightClick) {
                view.pointerConfig = view.pointerConfig.copy(longPressRightClick = it); changed()
            }
            // 🔑 Rarely used, so it sits at the bottom of the quick section.
            SwitchRow(stringResource(R.string.settings_view_only), view.viewOnly) {
                // Turning it on releases what is held first (inside the setter). Otherwise it stays down on the server forever.
                view.viewOnly = it; changed()
            }

            SectionTitle(stringResource(R.string.settings_section_input),
                stringResource(R.string.settings_applies_now))
            SliderRow(stringResource(R.string.settings_cursor_sensitivity), view.pointerConfig.sensitivity, 0.25f, 5f) {
                view.pointerConfig = view.pointerConfig.copy(sensitivity = it); changed()
            }
            SliderRow(stringResource(R.string.settings_scroll_sensitivity), view.pointerConfig.scrollSensitivity, 0.25f, 8f) {
                view.pointerConfig = view.pointerConfig.copy(scrollSensitivity = it); changed()
            }
            SwitchRow(stringResource(R.string.settings_natural_scroll), view.pointerConfig.naturalScroll) {
                view.pointerConfig = view.pointerConfig.copy(naturalScroll = it); changed()
            }
            // 🔑 Says what turning it off does, rather than using the word "latching".
            SwitchRow(stringResource(R.string.settings_latch_ctrl),
                view.keyConfig.latchEnabled) {
                view.keyConfig = view.keyConfig.copy(latchEnabled = it); changed()
            }
            SliderRow(stringResource(R.string.settings_double_tap_ms), view.keyConfig.doubleTapMs.toFloat(), 150f, 600f, 0) {
                view.keyConfig = view.keyConfig.copy(doubleTapMs = it.roundToInt().toLong()); changed()
            }
            SwitchRow(stringResource(R.string.settings_click_consumes_latch), view.keyConfig.mouseClickConsumesOneshot) {
                view.keyConfig = view.keyConfig.copy(mouseClickConsumesOneshot = it); changed()
            }

            SectionTitle(stringResource(R.string.settings_section_connection),
                stringResource(R.string.settings_applies_next_connection))
            TextRow(stringResource(R.string.settings_host), view.connectionConfig.host) {
                view.connectionConfig.host = it; changed()
            }
            TextRow(stringResource(R.string.settings_port), view.connectionConfig.port.toString()) {
                it.toIntOrNull()?.let { p -> view.connectionConfig.port = p }; changed()
            }
            SwitchRow(stringResource(R.string.settings_shared), view.connectionConfig.shared) { on ->
                if (!on) confirming = RiskyOption.SHARED_OFF
                else { view.connectionConfig.shared = true; changed() }
            }

            SectionTitle(stringResource(R.string.settings_section_display),
                stringResource(R.string.settings_applies_next_connection))
            SliderRow(stringResource(R.string.settings_jpeg_quality, valueLabel(qualityLabelRes(view.connectionConfig.qualityLevel), view.connectionConfig.qualityLevel)),
                view.connectionConfig.qualityLevel.toFloat(), -1f, 9f, 0) {
                view.connectionConfig.qualityLevel = it.roundToInt(); changed()
            }
            // 🔑 Directly under quality, because the server ties the two together (q8 = 92 with
            //    4:4:4). Seen apart, "I lowered the quality, why did the colour go too?" is
            //    unanswerable.
            ChoiceRow(
                stringResource(R.string.settings_chroma, valueLabel(subsamplingLabelRes(view.connectionConfig.subsampling), view.connectionConfig.subsampling)),
                SUBSAMPLING_CHOICES, view.connectionConfig.subsampling,
            ) { view.connectionConfig.subsampling = it; changed() }
            SliderRow(stringResource(R.string.settings_compression, valueLabel(compressLabelRes(view.connectionConfig.compressLevel), view.connectionConfig.compressLevel)),
                view.connectionConfig.compressLevel.toFloat(), -1f, 9f, 0) {
                view.connectionConfig.compressLevel = it.roundToInt(); changed()
            }
            SwitchRow(stringResource(R.string.settings_clipboard), view.connectionConfig.clipboard) { on ->
                if (on) confirming = RiskyOption.CLIPBOARD_ON
                else { view.connectionConfig.clipboard = false; changed() }
            }
            // 🔑 No longer a dangerous option: the app draws the cursor itself (2026-09-16).
            //    Turn it off and the server composites it into the framebuffer, so the cursor
            //    waits for frames again.
            SwitchRow(stringResource(R.string.settings_cursor_shape),
                view.connectionConfig.cursorShape) { on ->
                view.connectionConfig.cursorShape = on; changed()
            }
        }
    }

    confirming?.let { opt ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(opt.title)) },
            text = { Text(stringResource(opt.why)) },
            confirmButton = {
                TextButton(onClick = {
                    when (opt) {
                        RiskyOption.SHARED_OFF -> view.connectionConfig.shared = false
                        RiskyOption.CLIPBOARD_ON -> view.connectionConfig.clipboard = true
                    }
                    confirming = null; changed()
                }) { Text(stringResource(R.string.settings_enable_anyway)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/**
 * 🔴 **A count, not a sentence** — with nothing pending it is not drawn at all.
 * Showing "applies from the next connection" permanently does not work: people read it and still
 * believe the change took effect now.
 */
@Composable
private fun PendingBar(view: VncSurfaceView, onReconnect: () -> Unit) {
    val pending = PendingChanges.of(view.connectionConfig, view.connectedConfig)
    if (pending.isEmpty) return
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_pending, pending.count),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                // 🔑 `stringResource` is a @Composable call and cannot be used inside an
                //    ordinary lambda, so the names are resolved through the context instead.
                val res = LocalContext.current.resources
                Text(pending.keys.joinToString(" · ") { key ->
                    SettingLabels.of(key)?.let { res.getString(it) } ?: key
                }, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onReconnect) { Text(stringResource(R.string.settings_reconnect_now)) }
        }
    }
}

/**
 * Type a line here, press Send, and it is **typed** on the remote screen.
 *
 * 🔴 **Keys, not a paste.** A clipboard paste needs the remote side to cooperate, and the extended
 * clipboard is the option that has hung x11vnc-family servers before. Key presses land anywhere a
 * keyboard lands - including a game that has never heard of Ctrl+V.
 *
 * 🔑 **One row, not a block.** The field holds a line only until it is sent and is then cleared -
 * it is a doorway, not a document. It sat above its own paragraph of explanation at first and took
 * up a third of the sheet for something you look at for two seconds. The screen is always
 * landscape, so the buttons fit beside it.
 *
 * 🔑 **Sending closes the sheet**, so what was typed is visible the moment it is sent. The field
 * is cleared too, which only matters for the case where something re-opens the sheet at once.
 */
@Composable
private fun SendTextRow(onSend: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun send(withReturn: Boolean) { onSend(text, withReturn); text = "" }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.settings_send_text)) },
            singleLine = true,
            // 🔑 The phone keyboard's own action key sends, so a line can be typed and sent
            //    without looking away from the keyboard. It sends **with** Enter, because that is
            //    what the key means everywhere else; "Send" without it is the button beside it.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send(true) }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { send(false) }, enabled = text.isNotEmpty()) {
            Text(stringResource(R.string.settings_send))
        }
        Button(onClick = { send(true) }, enabled = text.isNotEmpty()) {
            Text(stringResource(R.string.settings_send_enter))
        }
        // 🔑 Enter on its own, with the field empty: the remote is showing a dialog that only
        //    needs confirming, and the panel's ↵ button is behind this sheet.
        TextButton(onClick = { onSend("", true) }) {
            Text(stringResource(R.string.settings_enter_only))
        }
    }
}

/**
 * The app's name and version, at the top of the sheet.
 *
 * 🔑 **Why it is here and not on a banner.** There is no banner: the app opens straight into the
 * remote screen, because anything permanently on top of it would cover the game. This sheet is
 * the only surface that is *not* the game, so it is the only place identity can live.
 *
 * 🔑 **Why it is worth the vertical space** - the name is not the same everywhere. The
 * `applicationId` and the visible name both follow whether an upload key is present
 * (`RemotePad` when signed for the store, `VNC for Games` otherwise), so a build can be one of two
 * apps and look identical. Seeing the name and the version together is how you tell which build a
 * device is actually running - which is exactly the question that costs an afternoon when a
 * measurement disagrees with the code.
 *
 * 🔴 It is **one line**. The keyboard below it is what people come here for mid-game, and
 * every row above it pushes that further from the thumb.
 */
@Composable
private fun AppTitleRow() {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // ℹ versionName is what a user reads; versionCode is what the store counts, and it is the
        //   one that tells two builds of the same "1.0" apart.
        Text(stringResource(R.string.settings_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String, badge: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // 🔑 When things apply is stated once per section; per row, nobody reads it.
        Text(badge, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 🔑 **A slider's range must match what the view actually accepts.** If it does not, the value
 * gets silently clamped and the user has no idea why it did not take.
 */
@Composable
private fun SliderRow(
    label: String, value: Float, min: Float, max: Float,
    decimals: Int = 2, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(if (decimals == 0) label else "$label — ${"%.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}

/**
 * A row for picking a value that has **no natural ordering**.
 *
 * 🔴 **Subsampling must not be a slider.** The constants are 0 = 4:4:4, 1 = 4:2:0, 2 = 4:2:2, so
 * numeric order and quality order **disagree**. As a slider it would read as "further right is
 * better", which is false.
 */
@Composable
private fun ChoiceRow(
    label: String, choices: List<Triple<Int, Int, String>>, selected: Int, onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { (value, labelRes, literal) ->
                val short = if (labelRes != 0) stringResource(labelRes) else literal
                if (value == selected)
                    Button(onClick = { onChange(value) }) { Text(short) }
                else
                    TextButton(onClick = { onChange(value) }) { Text(short) }
            }
        }
    }
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/**
 * Resolves a value label: a resource id when there is one, otherwise the bare number.
 *
 * 🔑 Some values have no wording worth translating — a JPEG quality of 5 is just "5". Returning 0
 * from the label functions means exactly that, and this is the one place that decides what to do
 * about it.
 */
@Composable
private fun valueLabel(labelRes: Int, value: Int): String =
    if (labelRes != 0) stringResource(labelRes, value) else value.toString()
