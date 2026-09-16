// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.overlay

import io.github.zirize.vncviewerforgames.input.VncButton
import io.github.zirize.vncviewerforgames.input.VncKeySym
import org.json.JSONArray
import org.json.JSONObject

/**
 * One button layout. **The JSON files under `profiles/` are the source of truth**; this is just
 * what they are read into.
 *
 * 🔑 **Why the layout is data** — different people want different layouts, and the thing producing
 * those layouts is usually an agent rather than a person. If the layout were a Kotlin constant the
 * agent would have to edit code, and checking the result would need a device. As JSON, editing and
 * checking both work without hardware.
 *
 * 🔴 Coordinates are **authored pixels**, not dp. They were drawn for a panel [authoredMarginPx]
 * wide; a real panel of a different width scales the whole thing by one factor ([layoutPanel]).
 */
data class OverlayProfile(
    val id: String,
    val name: String,
    val authoredMarginPx: Int,
    val authoredHeightPx: Int,
    val cornerRadiusPx: Float,
    val buttons: List<OverlayButton>,
    /** Things that were read leniently. 🔑 Swallow them silently and "why doesn't it work" has no answer. */
    val warnings: List<String> = emptyList(),
)

/**
 * The profile could not be read at all.
 * 🔴 Do not swallow this — a swallowed one leaves an empty screen. The caller must handle it.
 */
class OverlayProfileException(message: String) : Exception(message)

object OverlayProfileParser {

    /** The schema version this code understands. 🔴 Anything *larger* is refused: it may carry rules we do not know. */
    const val SUPPORTED_SCHEMA = 1

    /**
     * 🔴 **Last resort.** Used when a profile could not be read at all.
     *
     * 🔑 **Why it has exactly one button, and why that button is settings** — if the overlay fails
     * to appear, the way into settings disappears with it and there is no way to fix anything from
     * inside the app. Whatever else breaks, the door you fix things through stays. That failure
     * happened for real on 2026-09-16 and had to be reverted.
     */
    val RESCUE = OverlayProfile(
        id = "rescue",
        name = "Rescue",
        authoredMarginPx = 240,
        authoredHeightPx = 1080,
        cornerRadiusPx = 20f,
        buttons = listOf(
            OverlayButton(
                id = "settings", label = null, shape = ButtonShape.ICON,
                panel = OverlayPanel.LEFT, anchor = OverlayAnchor.BOTTOM,
                x = 5, y = 5, w = 107, h = 96,
                action = OverlayAction.Ui(UiTarget.SETTINGS),
            )
        ),
    )

    /**
     * Reads one blob of JSON into a profile.
     *
     * 🔑 **Two grades of broken**, because "the overlay did not appear" is the worst outcome:
     * - **The whole file is unusable** — bad syntax, an unknown larger `schema`, no buttons.
     *   ⇒ [OverlayProfileException]. The caller falls back to [RESCUE].
     * - **One button is wrong** — unknown shape, unreadable keysym.
     *   ⇒ only that button gets `enabled = false`, and a line goes into [OverlayProfile.warnings].
     *     🔴 **It is not hidden.** A button that vanishes reads as "I deleted it by accident".
     */
    fun parse(text: String): OverlayProfile {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw OverlayProfileException("cannot read JSON: ${e.message}")
        }

        val schema = root.optInt("schema", -1)
        if (schema < 0) throw OverlayProfileException("no `schema` field")
        if (schema > SUPPORTED_SCHEMA) {
            throw OverlayProfileException(
                "unknown schema $schema (this build understands up to $SUPPORTED_SCHEMA)")
        }

        val warnings = mutableListOf<String>()
        val arr: JSONArray = root.optJSONArray("buttons")
            ?: throw OverlayProfileException("no `buttons` array")

        val buttons = mutableListOf<OverlayButton>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { warnings += "buttons[$i] is not an object - skipped"; continue }
            val id = o.optString("id").ifBlank { null }
            if (id == null) { warnings += "buttons[$i] has no `id` - skipped"; continue }
            val shape = parseShape(o.optString("shape"))
            if (shape == null) {
                warnings += "$id: unknown shape `${o.optString("shape")}` - skipped"; continue
            }
            val panel = when (o.optString("panel")) {
                "left" -> OverlayPanel.LEFT
                "right" -> OverlayPanel.RIGHT
                else -> { warnings += "$id: `panel` must be left or right - skipped"; continue }
            }
            val anchor = when (o.optString("anchor")) {
                "top" -> OverlayAnchor.TOP
                "bottom" -> OverlayAnchor.BOTTOM
                else -> { warnings += "$id: `anchor` must be top or bottom - skipped"; continue }
            }
            val action = parseAction(id, o.optJSONObject("action"), warnings)
            buttons += OverlayButton(
                id = id,
                label = if (o.isNull("label")) null else o.optString("label").ifBlank { null },
                shape = shape,
                panel = panel,
                anchor = anchor,
                x = o.optInt("x"), y = o.optInt("y"),
                w = o.optInt("w"), h = o.optInt("h"),
                action = action ?: OverlayAction.Ui(UiTarget.SETTINGS),
                enabled = action != null,
            )
        }
        if (buttons.isEmpty()) throw OverlayProfileException("no buttons at all")

        return OverlayProfile(
            id = root.optString("id").ifBlank { "unnamed" },
            name = root.optString("name").ifBlank { "unnamed" },
            authoredMarginPx = root.optInt("authoredForMarginPx", 240),
            authoredHeightPx = root.optInt("authoredForHeightPx", 1080),
            cornerRadiusPx = root.optDouble("cornerRadiusPx", 20.0).toFloat(),
            buttons = buttons,
            warnings = warnings,
        )
    }

    private fun parseShape(s: String): ButtonShape? = when (s) {
        "rounded" -> ButtonShape.ROUND_RECT
        "circle" -> ButtonShape.CIRCLE
        "mouse" -> ButtonShape.MOUSE
        "dpad" -> ButtonShape.DPAD
        "icon" -> ButtonShape.ICON
        else -> null
    }

    /** Returns null if unreadable — the caller then marks that one button disabled. */
    private fun parseAction(id: String, o: JSONObject?, warnings: MutableList<String>): OverlayAction? {
        if (o == null) { warnings += "$id: no `action`"; return null }
        return when (val type = o.optString("type")) {
            "key" -> {
                val sym = VncKeySym.resolve(o.optString("keysym"))
                if (sym == null) { warnings += "$id: unknown keysym `${o.optString("keysym")}`"; return null }
                var behavior = when (o.optString("behavior", "tap")) {
                    "tap" -> KeyBehavior.TAP
                    "hold" -> KeyBehavior.HOLD
                    "latch" -> KeyBehavior.LATCH
                    else -> { warnings += "$id: unknown behavior - treated as tap"; KeyBehavior.TAP }
                }
                // 🔴 `latch` on something that is not a modifier is downgraded rather than refused.
                //    🔑 One less clever button beats a profile that will not open at all.
                if (behavior == KeyBehavior.LATCH && sym !in VncKeySym.MODIFIERS) {
                    warnings += "$id: latch only means anything on a modifier - downgraded to tap"
                    behavior = KeyBehavior.TAP
                }
                OverlayAction.Key(sym, behavior)
            }
            // 🔑 The D-pad is ONE button. Four separate ones would each get expanded to a 48dp
            //    touch target, those targets would overlap, and up and left would fire together.
            //    [dpadDirection] resolves the direction inside it.
            "dpad" -> OverlayAction.Key(0, KeyBehavior.HOLD)
            "wheel" -> when (o.optString("direction")) {
                "up" -> OverlayAction.Wheel(VncButton.WHEEL_UP, o.optInt("clicks", 1))
                "down" -> OverlayAction.Wheel(VncButton.WHEEL_DOWN, o.optInt("clicks", 1))
                else -> { warnings += "$id: `direction` must be up or down"; null }
            }
            "mouse" -> when (o.optString("button")) {
                "left" -> OverlayAction.Mouse(VncButton.LEFT)
                "right" -> OverlayAction.Mouse(VncButton.RIGHT)
                "middle" -> OverlayAction.Mouse(VncButton.MIDDLE)
                else -> { warnings += "$id: unknown mouse button"; null }
            }
            "ui" -> when (o.optString("command")) {
                "settings" -> OverlayAction.Ui(UiTarget.SETTINGS)
                else -> { warnings += "$id: unknown ui command `${o.optString("command")}`"; null }
            }
            else -> { warnings += "$id: unknown action type `$type`"; null }
        }
    }
}
