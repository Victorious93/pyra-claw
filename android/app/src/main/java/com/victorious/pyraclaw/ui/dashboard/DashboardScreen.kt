@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.victorious.pyraclaw.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.R
import com.victorious.pyraclaw.data.str
import com.victorious.pyraclaw.net.NodeDto
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.CenteredState
import com.victorious.pyraclaw.ui.components.ConnectionPill
import com.victorious.pyraclaw.ui.components.ErrorBanner
import com.victorious.pyraclaw.ui.components.GradientTitle
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.components.StatusDot
import com.victorious.pyraclaw.ui.components.Tag
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun DashboardScreen(
    ui: DashboardUi,
    nowMs: Long,
    onRefresh: () -> Unit,
    onWake: () -> Unit,
    onPowerButton: () -> Unit,
    onRunTimer: (TimerDto) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTimers: () -> Unit,
) {
    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("dashboard"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(ui, nowMs) }
            if (ui.restError != null && ui.status == null) item { ErrorBanner(ui.restError, onRefresh) }
            item { WakeHero(ui, nowMs, onWake) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("Power button", "Needs approval", Icons.Filled.PowerSettingsNew, Neon.Magenta, Modifier.weight(1f), onPowerButton)
                    QuickAction("Devices", "Charge & power", Icons.Filled.Devices, Neon.Cyan, Modifier.weight(1f), onOpenDevices)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("Timers", if (ui.runningTimers.isNotEmpty()) "${ui.runningTimers.size} running now" else "Schedules", Icons.Filled.Timer, Neon.Violet, Modifier.weight(1f), onOpenTimers)
                    QuickAction("Refresh", "Reconnect live link", Icons.Filled.Sync, Neon.Green, Modifier.weight(1f), onRefresh)
                }
            }
            if (ui.runningTimers.isNotEmpty()) {
                item { SectionHeader("Running now") }
                items(ui.runningTimers, key = { "run-" + it.id }) { RunningTimer(it) }
            }
            if (ui.manualTimers.isNotEmpty()) {
                item { SectionHeader("Run a routine") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.manualTimers.forEach { t ->
                            AssistChip(
                                onClick = { onRunTimer(t) }, label = { Text(t.name) },
                                leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = Neon.Cyan) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Neon.Surface),
                            )
                        }
                    }
                }
            }
            item { SectionHeader("ESP32 nodes", trailing = { Text("${ui.status?.nodes?.count { it.online } ?: 0}/${ui.status?.nodes?.size ?: 0} online", style = MaterialTheme.typography.labelMedium, color = Neon.TextDim) }) }
            val nodes = ui.status?.nodes.orEmpty().sortedWith(compareByDescending<NodeDto> { it.online }.thenBy { it.id })
            if (nodes.isEmpty()) {
                item {
                    NeonCard {
                        CenteredState(
                            Icons.Filled.Memory, "No nodes yet",
                            if (ui.status == null) "Node cards appear once the hub is reachable."
                            else "No ESP32 has sent a heartbeat. Check its Wi-Fi/MQTT provisioning (Help > ESP32).",
                            tint = Neon.TextDim,
                        )
                    }
                }
            } else {
                items(nodes, key = { "node-" + it.id }) { NodeCard(it, nowMs) }
            }
        }
    }
}

@Composable
private fun Header(ui: DashboardUi, nowMs: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.pyra_logo), null, Modifier.size(44.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            GradientTitle("Pyra Claw")
            val s = ui.status
            Text(
                if (s == null) "Hub status unknown"
                else "Hub v${s.version} · up ${Fmt.uptime(s.uptime_s)} · MQTT ${if (s.mqtt) "ok" else "down"}",
                style = MaterialTheme.typography.bodySmall, color = if (s?.mqtt == false) Neon.Amber else Neon.TextDim,
            )
        }
        ConnectionPill(ui.link, nowMs)
    }
}

@Composable
private fun WakeHero(ui: DashboardUi, nowMs: Long, onWake: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val sending = ui.wake is WakeState.Sending
    val pulse by rememberInfiniteTransition(label = "hero").animateFloat(
        1f, if (sending) 1.06f else 1.015f, infiniteRepeatable(tween(if (sending) 450 else 1800), RepeatMode.Reverse), label = "s",
    )
    NeonCard(accent = Neon.Cyan.copy(alpha = 0.5f), glow = true, padding = 20.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(168.dp).scale(pulse).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Neon.Cyan.copy(alpha = 0.28f), Neon.Bg)))
                    .border(3.dp, Neon.CyanMagenta, CircleShape)
                    .clickable(enabled = !sending, role = Role.Button, onClickLabel = "Wake PC") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onWake()
                    }
                    .testTag("wakeButton"),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(150.dp), color = Neon.Cyan, strokeWidth = 3.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Bolt, null, tint = Neon.Cyan, modifier = Modifier.size(54.dp))
                    Text("WAKE PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Neon.Text)
                }
            }
            Spacer(Modifier.height(14.dp))
            val (text, color) = when (val w = ui.wake) {
                WakeState.Idle -> "Wake-on-LAN + ESP32 relay. No approval needed." to Neon.TextDim
                WakeState.Sending -> "Sending magic packet…" to Neon.Cyan
                is WakeState.Sent -> (
                    "Magic packet sent ${Fmt.relative(w.atMs / 1000.0, nowMs)}" +
                        (if (w.relayed) " · ESP32 relayed it" else " · ESP32 relay unavailable") +
                        "\nThe hub can't confirm the PC booted. Allow about 30 s."
                    ) to Neon.Green
                is WakeState.Failed -> "${w.error.title}\n${w.error.hint}" to Neon.Red
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center, modifier = Modifier.testTag("wakeResult"))
        }
    }
}

@Composable
private fun QuickAction(title: String, sub: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Neon.Surface)
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun RunningTimer(t: TimerDto) {
    NeonCard(accent = Neon.Violet.copy(alpha = 0.6f), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = Neon.Violet)
            Spacer(Modifier.width(10.dp))
            Text(t.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("step ${(t.current_step ?: 0) + 1}/${t.steps.size}", style = MaterialTheme.typography.labelMedium, color = Neon.TextDim)
        }
        LinearProgressIndicator(
            progress = { ((t.current_step ?: 0) + 1f) / t.steps.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(), color = Neon.Violet, trackColor = Neon.Outline,
        )
    }
}

@Composable
private fun NodeCard(n: NodeDto, nowMs: Long) {
    val st = n.status
    NeonCard(accent = if (n.online) Neon.Green.copy(alpha = 0.4f) else Neon.Outline, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (n.online) Neon.Green else Neon.Red, pulsing = n.online)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.id, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono))
                Text(
                    (if (n.online) "Online" else "Offline") + " · last seen ${Fmt.relative(n.last_seen, nowMs)}",
                    style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
                )
            }
            Icon(Icons.Filled.Memory, null, tint = Neon.TextFaint)
        }
        val tags = listOfNotNull(
            st.str("ip")?.let { it to Neon.Cyan },
            st.str("fw")?.let { "fw $it" to Neon.Violet },
            st.str("rssi")?.let { "$it dBm" to Neon.Cyan },
            st.str("time_synced")?.let { (if (it == "true") "time synced" else "no NTP") to (if (it == "true") Neon.Green else Neon.Amber) },
            st.str("detail")?.let { it to Neon.Amber },
        )
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { (t, c) -> Tag(t, c) }
            }
        }
    }
}
