@file:OptIn(ExperimentalMaterial3Api::class)

package com.victorious.pyraclaw.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.data.AuditEvent
import com.victorious.pyraclaw.ui.Commands
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.CenteredState
import com.victorious.pyraclaw.ui.components.ErrorState
import com.victorious.pyraclaw.ui.components.Load
import com.victorious.pyraclaw.ui.components.LoadingState
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun ActivityScreen(
    ui: ActivityUi,
    phoneLog: List<String>,
    onRefresh: () -> Unit,
    onFilter: (AuditFilter) -> Unit,
    onQuery: (String) -> Unit,
) {
    var source by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Hub audit" to Icons.Filled.Router, "This phone" to Icons.Filled.PhoneAndroid).forEachIndexed { i, (label, icon) ->
                    SegmentedButton(
                        selected = source == i, onClick = { source = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { Icon(icon, null, Modifier.size(18.dp)) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Neon.Cyan.copy(alpha = 0.16f), activeContentColor = Neon.Cyan,
                            inactiveContainerColor = Neon.Surface, inactiveContentColor = Neon.TextDim,
                        ),
                    ) { Text(label) }
                }
            }
            if (source == 0) {
                OutlinedTextField(
                    ui.query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search events") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (ui.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear search") } },
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuditFilter.entries.forEach { f ->
                        FilterChip(
                            selected = ui.filter == f, onClick = { onFilter(f) },
                            label = { Text(if (ui.events is Load.Ready) "${f.label} ${ui.count(f)}" else f.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Neon.Surface, labelColor = Neon.TextDim,
                                selectedContainerColor = Neon.Cyan.copy(alpha = 0.18f), selectedLabelColor = Neon.Cyan,
                            ),
                        )
                    }
                }
            }
        }
        if (source == 1) PhoneLog(phoneLog) else HubAudit(ui, onRefresh)
    }
}

@Composable
private fun HubAudit(ui: ActivityUi, onRefresh: () -> Unit) {
    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when (val e = ui.events) {
            Load.Loading -> LoadingState("Loading the audit log…")
            is Load.Failed -> LazyColumn(Modifier.fillMaxSize()) { item { ErrorState(e.error, onRefresh) } }
            is Load.Ready -> {
                val list = ui.visible
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (list.isEmpty()) item {
                        CenteredState(
                            Icons.Filled.History,
                            if (e.data.isEmpty()) "No activity yet" else "Nothing matches",
                            if (e.data.isEmpty()) "Every command, approval and timer run on the hub shows up here (last 200 events)."
                            else "Try another filter or clear the search.",
                        )
                    }
                    var lastDay: String? = null
                    list.forEach { ev ->
                        val day = Fmt.day(ev.ts)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day-$day-${ev.ts}") {
                                Text(day.uppercase(), style = MaterialTheme.typography.labelSmall, color = Neon.TextFaint, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            }
                        }
                        item { AuditRow(ev) }
                    }
                }
            }
        }
    }
}

private data class EventLook(val icon: ImageVector, val color: Color)

private fun look(e: AuditEvent): EventLook = when {
    e.event in setOf("rejected", "timer_rejected") || e.event.contains("fail") -> EventLook(Icons.Filled.Block, Neon.Red)
    e.event == "approval_issued" || e.event == "timer_preapproved" -> EventLook(Icons.Filled.Key, Neon.Magenta)
    e.event.startsWith("timer_") -> EventLook(Icons.Filled.Timer, Neon.Violet)
    e.event == "executed" -> EventLook(Icons.Filled.TaskAlt, Neon.Green)
    else -> EventLook(Icons.Filled.CheckCircleOutline, Neon.Cyan)
}

@Composable
private fun AuditRow(e: AuditEvent) {
    val l = look(e)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Neon.Surface).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(l.color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(l.icon, null, tint = l.color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.event.replace('_', ' ').replaceFirstChar { it.uppercase() } + (e.command?.let { " · " + Commands.name(it) } ?: ""),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                Text(Fmt.time(e.ts), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = Neon.TextFaint)
            }
            val detail = listOfNotNull(
                e.fields["target"]?.let { "target $it" },
                e.fields["reason"]?.let { "reason: $it" },
                e.fields["error"],
                e.fields["source"],
                e.fields["name"],
                e.fields["timer_id"]?.let { "timer $it" },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (l.color == Neon.Red) Neon.Red else Neon.TextDim)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PhoneLog(lines: List<String>) {
    if (lines.isEmpty()) {
        CenteredState(Icons.Filled.PhoneAndroid, "Quiet so far", "The background link and droidcommand-AI commands log here while the app runs.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines) { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Neon.TextDim) }
    }
}
