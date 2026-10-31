@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.victorious.pyraclaw.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.net.ScheduleDto
import com.victorious.pyraclaw.net.StepDto
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.net.TimerIn
import com.victorious.pyraclaw.service.HubConnectionService
import com.victorious.pyraclaw.service.ServiceBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Commands a timer step may use (must match hub KNOWN_COMMANDS for timers). */
val STEP_COMMANDS = listOf("charge.on", "charge.off", "device.power_on", "device.power_off", "pc.wake")
val GATED_COMMANDS = setOf("device.power_off", "pc.power_pulse")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun fmtTs(ts: Double?): String =
    if (ts == null) "—" else SimpleDateFormat("EEE dd MMM HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))

private fun scheduleText(s: ScheduleDto): String = when (s.kind) {
    "once" -> "once at ${s.at}"
    "daily" -> "daily ${s.time}"
    "weekly" -> "weekly ${s.time} ${s.days.joinToString(",") { DAY_NAMES.getOrElse(it) { "?" } }}"
    else -> "manual"
}

@Composable
fun TimerScreen(vm: HubViewModel) {
    val ctx = LocalContext.current
    val svc by ServiceBus.state.collectAsState()
    var editing by remember { mutableStateOf<TimerDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshTimers() }
    // Live updates from the service's WebSocket take precedence over the last REST poll.
    val timers = if (svc.connected && svc.timers.isNotEmpty()) svc.timers else vm.timers

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Timers", style = MaterialTheme.typography.headlineSmall)
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Background service: " + when {
                        !svc.running -> "stopped"
                        svc.connected -> "running · hub connected (${svc.fgsType})"
                        else -> "running · connecting…"
                    } + if (svc.working) " · executing" else "",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (svc.running) {
                        OutlinedButton(onClick = { HubConnectionService.stop(ctx) }) { Text("Stop service") }
                    } else {
                        Button(onClick = { HubConnectionService.start(ctx, vm.hubUrl, vm.token) }) {
                            Text("Start service")
                        }
                    }
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("Battery settings") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("New timer") }
            OutlinedButton(onClick = { vm.refreshTimers() }) { Text("Refresh") }
        }
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(timers, key = { it.id }) { t ->
                TimerRow(
                    t,
                    onToggle = { vm.setTimerEnabled(t.id, it) },
                    onRun = { vm.runTimer(t.id) },
                    onEdit = { editing = t },
                    onDelete = { vm.deleteTimer(t.id) },
                )
            }
            item {
                Text("Status log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(svc.log.take(50)) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (creating || editing != null) {
        TimerEditor(
            initial = editing,
            deviceIds = vm.devices.map { it.id },
            onDismiss = { creating = false; editing = null },
            onSave = { body ->
                val gated = body.steps.any { it.command in GATED_COMMANDS }
                val unchanged = editing?.let { it.preapproved && it.steps == body.steps } ?: false
                vm.saveTimer(editing?.id, body, needsApproval = gated && !unchanged)
                creating = false; editing = null
            },
        )
    }
}

@Composable
private fun TimerRow(
    t: TimerDto,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                    Text("${scheduleText(t.schedule)} · next ${fmtTs(t.next_run)}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = t.enabled, onCheckedChange = onToggle)
            }
            Text(
                t.steps.joinToString("  →  ") { s ->
                    (if (s.delay_s > 0) "wait ${s.delay_s.toInt()}s, " else "") + "${s.command} ${s.target ?: ""}".trim()
                },
                style = MaterialTheme.typography.bodySmall,
            )
            val state = when {
                t.running -> "running step ${(t.current_step ?: 0) + 1}/${t.steps.size}"
                t.last_result != null -> "last ${fmtTs(t.last_run)}: ${t.last_result}"
                else -> "never run"
            }
            Text(state + if (t.preapproved) " · pre-approved" else "", style = MaterialTheme.typography.bodySmall)
            Row {
                IconButton(onClick = onRun, enabled = !t.running) { Icon(Icons.Filled.PlayArrow, "Run now") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun <T> Picker(label: String, value: T, options: List<T>, show: (T) -> String = { it.toString() }, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${show(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(show(o)) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

@Composable
private fun TimerEditor(
    initial: TimerDto?,
    deviceIds: List<String>,
    onDismiss: () -> Unit,
    onSave: (TimerIn) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.schedule?.kind ?: "daily") }
    var at by remember { mutableStateOf(initial?.schedule?.at ?: "") }
    var time by remember { mutableStateOf(initial?.schedule?.time ?: "07:00") }
    var days by remember { mutableStateOf(initial?.schedule?.days?.toSet() ?: setOf(0, 1, 2, 3, 4)) }
    val steps = remember {
        mutableStateListOf<StepDto>().apply {
            addAll(initial?.steps ?: listOf(StepDto("charge.off", deviceIds.firstOrNull())))
        }
    }
    val targets: List<String?> = listOf<String?>(null) + deviceIds

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New timer" else "Edit timer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Picker("Repeat", kind, listOf("once", "daily", "weekly", "manual")) { kind = it }
                when (kind) {
                    "once" -> OutlinedTextField(at, { at = it }, label = { Text("At (YYYY-MM-DDTHH:MM, hub local)") }, singleLine = true)
                    "daily", "weekly" -> OutlinedTextField(time, { time = it }, label = { Text("Time (HH:MM, hub local)") }, singleLine = true)
                }
                if (kind == "weekly") {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DAY_NAMES.forEachIndexed { i, d ->
                            FilterChip(
                                selected = i in days,
                                onClick = { days = if (i in days) days - i else days + i },
                                label = { Text(d.take(2)) },
                            )
                        }
                    }
                }
                Text("Sequence (runs top to bottom; delay is waited before the step)", style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { i, s ->
                    Card {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Step ${i + 1}" + if (s.command in GATED_COMMANDS) " (approval required)" else "")
                            Picker("Action", s.command, STEP_COMMANDS) { steps[i] = s.copy(command = it) }
                            if (s.command != "pc.wake") {
                                Picker("Device", s.target, targets, show = { it ?: "—" }) { steps[i] = s.copy(target = it) }
                            }
                            OutlinedTextField(
                                value = s.delay_s.toInt().toString(),
                                onValueChange = { v -> steps[i] = s.copy(delay_s = (v.toIntOrNull() ?: 0).toDouble()) },
                                label = { Text("Delay before (s)") }, singleLine = true,
                            )
                            if (steps.size > 1) TextButton(onClick = { steps.removeAt(i) }) { Text("Remove step") }
                        }
                    }
                }
                TextButton(onClick = { steps.add(StepDto("charge.on", deviceIds.firstOrNull(), delay_s = 5.0)) }) {
                    Text("+ Add step")
                }
                if (steps.any { it.command in GATED_COMMANDS }) {
                    Text(
                        "Saving will request a one-time timer.arm approval from the hub; it is recorded " +
                            "in the audit log and lets this timer run gated steps unattended.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val sched = when (kind) {
                    "once" -> ScheduleDto("once", at = at.trim())
                    "daily" -> ScheduleDto("daily", time = time.trim())
                    "weekly" -> ScheduleDto("weekly", time = time.trim(), days = days.sorted())
                    else -> ScheduleDto("manual")
                }
                val cleaned = steps.map { if (it.command == "pc.wake") it.copy(target = null) else it }
                onSave(TimerIn(name.ifBlank { "Timer" }, initial?.enabled ?: true, sched, cleaned))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
