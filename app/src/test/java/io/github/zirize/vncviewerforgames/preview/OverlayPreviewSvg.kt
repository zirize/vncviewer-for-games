// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.preview

import io.github.zirize.vncviewerforgames.overlay.ButtonShape
import io.github.zirize.vncviewerforgames.overlay.OverlayPanel
import io.github.zirize.vncviewerforgames.overlay.OverlayProfile
import io.github.zirize.vncviewerforgames.overlay.PlacedButton
import io.github.zirize.vncviewerforgames.overlay.layoutPanel

/**
 * Draws a profile as **one SVG**, so it can be looked at without a device.
 *
 * 🔑 **Why it is needed** — the validator only answers "are the numbers legal". It cannot catch a
 * layout where the numbers are fine but the result looks wrong: everything bunched to one side,
 * uneven spacing. ⇒ After changing a layout, an agent needs something it can **look at** and hand
 * to the person who asked.
 *
 * 🔴 **It uses the same [layoutPanel] the app does.** Compute it separately here and the preview
 * quietly drifts away from the real screen, at which point the preview is **a tool that lies**.
 *
 * ℹ️ It lives in the test source set: a development tool has no reason to ship inside the APK.
 */
object OverlayPreviewSvg {

    /** Preview canvas size, in authored pixels - the same shape as the 2400x1080 device. */
    private const val SCREEN_W = 2400
    private const val SCREEN_H = 1080

    fun render(profile: OverlayProfile): String {
        val panelW = profile.authoredMarginPx.toFloat()
        val left = layoutPanel(profile, OverlayPanel.LEFT, panelW, SCREEN_H.toFloat())
        val right = layoutPanel(profile, OverlayPanel.RIGHT, panelW, SCREEN_H.toFloat())

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$SCREEN_W" height="$SCREEN_H" """)
        sb.append("""viewBox="0 0 $SCREEN_W $SCREEN_H" font-family="sans-serif">""").append('\n')
        sb.append("""<title>${esc(profile.name)} (${profile.id})</title>""").append('\n')

        // Where the remote screen sits, so "a button here covers what you are doing" is visible.
        sb.append("""<rect width="$SCREEN_W" height="$SCREEN_H" fill="#101820"/>""").append('\n')
        sb.append("""<rect x="$panelW" y="0" width="${SCREEN_W - 2 * panelW}" height="$SCREEN_H" """)
        sb.append("""fill="#1b2a3a"/>""").append('\n')
        sb.append("""<text x="${SCREEN_W / 2}" y="${SCREEN_H / 2}" fill="#3d5a75" font-size="42" """)
        sb.append("""text-anchor="middle">remote screen</text>""").append('\n')

        // Panel edges - without the guides you cannot see that something has spilled out.
        for (x in listOf(0f, panelW, SCREEN_W - panelW, SCREEN_W.toFloat())) {
            sb.append("""<line x1="$x" y1="0" x2="$x" y2="$SCREEN_H" stroke="#55707f" """)
            sb.append("""stroke-width="2" stroke-dasharray="8 8"/>""").append('\n')
        }

        for (p in left) drawButton(sb, p, 0f, profile)
        for (p in right) drawButton(sb, p, SCREEN_W - panelW, profile)

        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun drawButton(sb: StringBuilder, p: PlacedButton, offsetX: Float, profile: OverlayProfile) {
        val x = p.left + offsetX
        val y = p.top
        // 🔑 Disabled buttons are dimmed in the app too; the preview has to match.
        val stroke = if (p.button.enabled) "#ffffff" else "#ffffff55"
        val fill = if (p.button.enabled) "#00000099" else "#00000044"
        val sw = 4f

        when (p.button.shape) {
            ButtonShape.CIRCLE ->
                sb.append("""<circle cx="${x + p.width / 2}" cy="${y + p.height / 2}" """)
                  .append("""r="${p.width / 2 - sw / 2}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""")
            ButtonShape.DPAD -> {
                // A cross. It conveys the same *meaning* as the app's D-pad drawing: four
                // directions inside one button.
                val cw = p.width / 3f; val ch = p.height / 3f
                sb.append("""<rect x="${x + cw}" y="$y" width="$cw" height="${p.height}" rx="12" """)
                  .append("""fill="$fill" stroke="$stroke" stroke-width="$sw"/>""").append('\n')
                sb.append("""<rect x="$x" y="${y + ch}" width="${p.width}" height="$ch" rx="12" """)
                  .append("""fill="$fill" stroke="$stroke" stroke-width="$sw"/>""")
            }
            else ->
                sb.append("""<rect x="${x + sw / 2}" y="${y + sw / 2}" """)
                  .append("""width="${p.width - sw}" height="${p.height - sw}" """)
                  .append("""rx="${profile.cornerRadiusPx}" fill="$fill" stroke="$stroke" stroke-width="$sw"/>""")
        }
        sb.append('\n')

        val text = p.button.label ?: shapeHint(p.button.shape)
        if (text != null) {
            val size = if (text.length > 2) p.width * 0.26f else p.width * 0.45f
            sb.append("""<text x="${x + p.width / 2}" y="${y + p.height / 2 + size * 0.35f}" """)
              .append("""fill="$stroke" font-size="$size" text-anchor="middle">${esc(text)}</text>""")
              .append('\n')
        }
        // The id, small - so it is obvious from the picture which button to edit.
        sb.append("""<text x="${x + p.width / 2}" y="${y + p.height + 20}" fill="#7d99ad" """)
          .append("""font-size="18" text-anchor="middle">${esc(p.button.id)}</text>""").append('\n')
    }

    private fun shapeHint(s: ButtonShape): String? = when (s) {
        ButtonShape.ICON -> "⋯"
        ButtonShape.DPAD -> null
        else -> null
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
