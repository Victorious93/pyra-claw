@file:OptIn(ExperimentalMaterial3Api::class)

package com.victorious.pyraclaw.ui.timers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.data.str
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.ui.Commands
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.CenteredState
import com.victorious.pyraclaw.ui.components.ErrorState
import com.victorious.pyraclaw.ui.components.Load
import com.victorious.pyraclaw.ui.components.LoadingState
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.components.Tag
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun TimersScreen(
    ui: TimersUi,
    nowMs: Long,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onEdit: (TimerDto) -> Unit,
    onToggle: (TimerDto, Boolean) -> Unit,
    onRun: (TimerDto) -> Unit,
    onDelete: (TimerDto) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<TimerDto?>(null) }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Timers", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        if (ui.live) Tag("LIVE", Neon.Green)
                    }
                    Text(
                        "Schedules run on the hub, so they fire even when this phone is asleep.",
                        style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
                    )
                }
                when (val t = ui.timers) {
                    Load.Loading -> item { Box(Modifier.fillMaxWidth().height(260.dp)) { LoadingState() } }
                    is Load.Failed -> item { ErrorState(t.error, onRefresh) }
                    is Load.Ready -> if (t.data.isEmpty()) {
                        item {
                            CenteredState(
                                Icons.Filled.Timer, "No timers yet",
                                "Build a routine like \"charge phone 1, wait 30 s, charge phone 2\" and let the hub run it on a schedule.",
                            ) { TextButton(onClick = onNew) { Text("Create your first timer") } }
                        }
                    } else {
                        val sorted = t.data.sortedWith(compareByDescending<TimerDto> { it.running }.thenByDescending { it.enabled }.thenBy { it.next_run ?: Double.MAX_VALUE })
                        items(sorted, key = { it.id }) { timer ->
                            TimerCard(timer, nowMs, onEdit, onToggle, onRun) { confirmDelete = timer }
                        }
                    }
                }
                if (ui.events.isNotEmpty()) {
                    item { SectionHeader("Recent timer events") }
                    items(ui.events.reversed().take(12)) { e ->
                        val ok = e.str("event") != "step_failed"
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Neon.Cyan else Neon.Red))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                listOfNotNull(
                                    e.str("ts")?.toDoubleOrNull()?.let { Fmt.time(it) },
                                    e.str("name"),
                                    e.str("event")?.replace('_', ' '),
                                    e.str("step")?.let { "step ${(it.toIntOrNull() ?: 0) + 1}" },
                                    (e.str("result") ?: e.str("error")),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = if (ok) Neon.TextDim else Neon.Red,
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New timer", fontWeight = FontWeight.Bold) },
            containerColor = Neon.Cyan, contentColor = Neon.Bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Neon.SurfaceHigh,
            title = { Text("Delete \"${t.name}\"?") },
            text = { Text("The hub stops running it and removes it for every phone. This is logged in the audit.") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirmDelete = null }) { Text("Delete", color = Neon.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun TimerCard(
    t: TimerDto,
    nowMs: Long,
    onEdit: (TimerDto) -> Unit,
    onToggle: (TimerDto, Boolean) -> Unit,
    onRun: (TimerDto) -> Unit,
    onDelete: () -> Unit,
) {
    val accent = when {
        t.running -> Neon.Violet
        !t.enabled -> Neon.Outline
        else -> Neon.Cyan.copy(alpha = 0.4f)
    }
    NeonCard(accent = accent, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleMedium, color = if (t.enabled) Neon.Text else Neon.TextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = Neon.TextDim)
                    Spacer(Modifier.width(4.dp))
                    Text(Fmt.schedule(t.schedule), style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
                }
            }
            Switch(
                checked = t.enabled, onCheckedChange = { onToggle(t, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Neon.Cyan, checkedThumbColor = Neon.Bg),
            )
        }
        if (t.running) {
            Text("Running step ${(t.current_step ?: 0) + 1} of ${t.steps.size}", style = MaterialTheme.typography.labelMedium, color = Neon.Violet)
            LinearProgressIndicator(
                progress = { ((t.current_step ?: 0) + 1f) / t.steps.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(), color = Neon.Violet, trackColor = Neon.Outline,
            )
        } else if (t.enabled && t.next_run != null) {
            Text(
                "Next run ${Fmt.clock(t.next_run)} · ${Fmt.relative(t.next_run, nowMs)}",
                style = MaterialTheme.typography.labelMedium, color = Neon.Cyan,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            t.steps.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val active = t.running && t.current_step == i
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(if (active) Neon.Violet else Neon.SurfaceHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (s.delay_s > 0) Fmt.delay(s.delay_s) + " → " else "") + Commands.name(s.command) + (s.target?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.command in Commands.GATED) Neon.Magenta else Neon.Text,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val last = when {
                t.last_result != null -> "Last: ${t.last_result} · ${Fmt.relative(t.last_run, nowMs)}"
                else -> "Never run"
            }
            Text(
                last, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                color = if (t.last_result?.contains("fail", true) == true) Neon.Red else Neon.TextFaint,
            )
            if (t.preapproved) {
                Icon(Icons.Filled.VerifiedUser, "Pre-approved", Modifier.size(16.dp), tint = Neon.Magenta)
                Spacer(Modifier.width(4.dp))
                Text("armed", style = MaterialTheme.typography.labelSmall, color = Neon.Magenta)
            }
        }
        Row {
            IconButton(onClick = { onRun(t) }, enabled = !t.running) { Icon(Icons.Filled.PlayArrow, "Run now", tint = if (t.running) Neon.TextFaint else Neon.Green) }
            IconButton(onClick = { onEdit(t) }, enabled = !t.running) { Icon(Icons.Filled.Edit, "Edit", tint = Neon.Cyan) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete", tint = Neon.TextDim) }
        }
    }
}
