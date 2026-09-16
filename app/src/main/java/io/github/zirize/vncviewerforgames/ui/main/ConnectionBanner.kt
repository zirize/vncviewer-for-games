// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Bill Kang

package io.github.zirize.vncviewerforgames.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zirize.vncviewerforgames.R
import io.github.zirize.vncviewerforgames.conn.VncConnectionState

/**
 * The notice that appears at the top of the screen when the connection is not currently live.
 *
 * 🔴 **Why it is needed** — when the connection drops, **the last frame stays on screen**, so
 * "disconnected" and "nothing is moving right now" look **identical**.
 * Measured 2026-09-15: pointed at a dead port, it retried once a second and **the screen said
 * nothing at all**.
 *
 * 🔑 What the user can do differs by state, so the buttons do too:
 * - [VncConnectionState.Retrying] = reconnecting by itself ⇒ **no button**; there is nothing to press.
 * - [VncConnectionState.Failed] = automatic retry deliberately suppressed (auth failure) ⇒
 *   **Retry is the only way forward**.
 */
@Composable
fun ConnectionBanner(state: VncConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state is VncConnectionState.Connected) return

    val (title, detail, fatal) = when (state) {
        is VncConnectionState.Connecting ->
            Triple(stringResource(R.string.conn_connecting), state.detail, false)
        is VncConnectionState.Retrying ->
            Triple(stringResource(R.string.conn_retrying), state.reason, false)
        is VncConnectionState.Failed ->
            Triple(stringResource(R.string.conn_failed), state.message, true)
        VncConnectionState.Connected -> return
    }

    Card(
        modifier.padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (fatal) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                // 🔑 Says out loud that the picture on screen is not current.
                Text(stringResource(R.string.conn_stale_picture),
                    style = MaterialTheme.typography.labelSmall)
            }
            // 🔴 No button while it is reconnecting on its own: a button with nothing behind it
            //    reads as "you must press this", and pressing it changing nothing reads as broken.
            if (fatal) Button(onClick = onRetry) { Text(stringResource(R.string.conn_retry)) }
        }
    }
}
