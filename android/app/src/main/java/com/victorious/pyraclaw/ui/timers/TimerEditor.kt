@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.victorious.pyraclaw.ui.timers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.ui.Commands
import com.victorious.pyraclaw.ui.DAY_NAMES
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.approval.HoldToConfirm
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val DELAY_PRESETS = listOf(0, 5, 10, 30, 60, 120, 300, 600, 1800, 3600)

/** Full-screen timer editor shown as a dialog (dark, no platform white). */
@Composable
fun TimerEditorDialog(
    draft: TimerDraft,
    devices: List<DeviceDto>,
    save: SaveState,
    needsArm: Boolean,
    onChange: ((TimerDraft) -> TimerDraft) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        TimerEditor(draft, devices, save, needsArm, onChange, onMove, onSave, onClose)
    }
}

@Composable
fun TimerEditor(
    draft: TimerDraft,
    devices: List<DeviceDto>,
    save: SaveState,
    needsArm: Boolean,
    onChange: ((TimerDraft) -> TimerDraft) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var showTime by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showArm by remember { mutableStateOf(false) }
    var triedSave by remember { mutableStateOf(false) }
    val errors = draft.errors()
    val saving = save == SaveState.Saving

    Scaffold(
        containerColor = Neon.Bg,
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (draft.id == null) "New timer" else "Edit timer") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } },
                actions = {
                    TextButton(
                        onClick = {
                            triedSave = true
                            if (errors.isEmpty()) { if (needsArm) showArm = true else onSave() }
                        },
                        enabled = !saving,
                        modifier = Modifier.testTag("timerSave"),
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Save", fontWeight = FontWeight.Bold, color = Neon.Cyan)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Neon.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                draft.name, { v -> onChange { it.copy(name = v) } }, Modifier.fillMaxWidth().testTag("timerName"),
                label = { Text("Name") }, placeholder = { Text("Night charge rotation") }, singleLine = true,
                isError = triedSave && draft.name.isBlank(),
            )

            SectionHeader("Repeat")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("once" to "Once", "daily" to "Daily", "weekly" to "Weekly", "manual" to "Manual").forEach { (k, label) ->
                    NeonChip(label, draft.kind == k) { onChange { it.copy(kind = k) } }
                }
            }
            if (draft.kind != "manual") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerTile("Time (hub local)", draft.time, Icons.Filled.Schedule, Modifier.weight(1f)) { showTime = true }
                    if (draft.kind == "once") {
                        PickerTile("Date", draft.date.ifBlank { "Pick a date" }, Icons.Filled.CalendarMonth, Modifier.weight(1f)) { showDate = true }
                    }
                }
            } else {
                Text("Manual timers only run when you press Run (here or on the Dashboard).", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            }
            if (draft.kind == "weekly") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_NAMES.forEachIndexed { i, d ->
                        NeonChip(d, i in draft.days) { onChange { it.copy(days = if (i in it.days) it.days - i else it.days + i) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onChange { it.copy(days = setOf(0, 1, 2, 3, 4)) } }) { Text("Weekdays") }
                    TextButton(onClick = { onChange { it.copy(days = setOf(5, 6)) } }) { Text("Weekends") }
                    TextButton(onClick = { onChange { it.copy(days = (0..6).toSet()) } }) { Text("Every day") }
                }
            }

            SectionHeader("Steps", trailing = { Text("drag ⠿ to reorder", style = MaterialTheme.typography.labelSmall, color = Neon.TextFaint) })
            Text("Steps run top to bottom. Each wait happens before its step. The first failing step stops the run.", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            StepList(draft, devices, onChange, onMove)
            OutlinedButton(
                onClick = { onChange { it.copy(steps = it.steps + StepDraft("charge.on", devices.firstOrNull()?.id, 5)) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add step") }

            if (draft.gated) {
                NeonCard(accent = Neon.Magenta.copy(alpha = 0.6f), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, null, tint = Neon.Magenta)
                        Spacer(Modifier.width(10.dp))
                        Text("This timer powers devices off", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        if (needsArm) "Saving asks the hub for a one-time timer.arm approval (you'll hold to confirm). It lets these steps run unattended and is written to the audit log."
                        else "Already armed. Changing the steps will need a new approval.",
                        style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
                    )
                }
            }
            if (triedSave && errors.isNotEmpty()) {
                errors.forEach { Text("• $it", color = Neon.Red, style = MaterialTheme.typography.bodySmall) }
            }
            (save as? SaveState.Failed)?.error?.let { SaveError(it) }
            Spacer(Modifier.size(40.dp))
        }
    }

    if (showTime) TimeDialog(draft.time, onDismiss = { showTime = false }) { t -> onChange { it.copy(time = t) }; showTime = false }
    if (showDate) DateDialog(onDismiss = { showDate = false }) { d -> onChange { it.copy(date = d) }; showDate = false }
    if (showArm) {
        AlertDialog(
            onDismissRequest = { showArm = false },
            containerColor = Neon.SurfaceHigh,
            title = { Text("Arm this timer?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("It can run ${draft.steps.filter { it.command in Commands.GATED }.joinToString { Commands.name(it.command) + " · " + (it.target ?: "") }} without asking again.", color = Neon.TextDim)
                    HoldToConfirm("Hold to arm and save", onComplete = { showArm = false; onSave() })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showArm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SaveError(e: HubError) {
    NeonCard(accent = Neon.Red, padding = 12.dp) {
        Text(e.title, style = MaterialTheme.typography.titleSmall, color = Neon.Red)
        Text(listOfNotNull(e.detail, e.hint).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
    }
}

/** Steps with a drag handle (drag to reorder) plus up/down buttons for accessibility. */
@Composable
private fun StepList(
    draft: TimerDraft,
    devices: List<DeviceDto>,
    onChange: ((TimerDraft) -> TimerDraft) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val steps by rememberUpdatedState(draft.steps)
    val spacingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        draft.steps.forEachIndexed { i, step ->
            val dragging = dragKey == step.key
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 16f else 0f }
                    .onSizeChanged { heights[step.key] = it.height },
            ) {
                StepRow(
                    index = i, count = draft.steps.size, step = step, devices = devices, dragging = dragging,
                    handle = Modifier.pointerInput(step.key) {
                        detectDragGestures(
                            onDragStart = { dragKey = step.key; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = { dragKey = null; dragOffset = 0f },
                            onDragCancel = { dragKey = null; dragOffset = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val cur = steps.indexOfFirst { it.key == dragKey }
                            if (cur < 0) return@detectDragGestures
                            if (dragOffset > 0 && cur < steps.lastIndex) {
                                val h = (heights[steps[cur + 1].key] ?: 0) + spacingPx
                                if (dragOffset > h / 2) { onMove(cur, cur + 1); dragOffset -= h; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            } else if (dragOffset < 0 && cur > 0) {
                                val h = (heights[steps[cur - 1].key] ?: 0) + spacingPx
                                if (-dragOffset > h / 2) { onMove(cur, cur - 1); dragOffset += h; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }
                    },
                    onUpdate = { s -> onChange { d -> d.copy(steps = d.steps.map { if (it.key == step.key) s else it }) } },
                    onRemove = { onChange { d -> d.copy(steps = d.steps.filterNot { it.key == step.key }) } },
                    onUp = { onMove(i, i - 1) },
                    onDown = { onMove(i, i + 1) },
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    count: Int,
    step: StepDraft,
    devices: List<DeviceDto>,
    dragging: Boolean,
    handle: Modifier,
    onUpdate: (StepDraft) -> Unit,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val gated = step.command in Commands.GATED
    val border = when {
        dragging -> Neon.Cyan
        gated -> Neon.Magenta.copy(alpha = 0.6f)
        else -> Neon.Outline
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (dragging) Neon.SurfaceHighest else Neon.Surface)
            .border(1.dp, border, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DragIndicator, "Drag to reorder", tint = Neon.TextDim, modifier = handle.size(28.dp))
            Spacer(Modifier.width(6.dp))
            Text("Step ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (gated) Text("approval", style = MaterialTheme.typography.labelSmall, color = Neon.Magenta)
            IconButton(onClick = onUp, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = index < count - 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onRemove, enabled = count > 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.RemoveCircleOutline, "Remove step", tint = Neon.TextDim) }
        }
        Dropdown(
            label = "Action", value = Commands.name(step.command),
            options = Commands.STEP_COMMANDS.map { it to Commands.name(it) + if (it in Commands.GATED) "  (approval)" else "" },
        ) { onUpdate(step.copy(command = it, target = if (it in Commands.NEEDS_TARGET) step.target ?: devices.firstOrNull()?.id else null)) }
        if (step.command in Commands.NEEDS_TARGET) {
            Dropdown(
                label = "Device", value = step.target?.let { id -> devices.firstOrNull { it.id == id }?.label?.let { "$it ($id)" } ?: id } ?: "Choose…",
                options = devices.map { it.id to ((it.label ?: it.id) + " (${it.id})") },
                empty = "No devices on the hub. Add [devices.<id>] to its config.",
            ) { onUpdate(step.copy(target = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wait before", style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim, modifier = Modifier.weight(1f))
            IconButton(onClick = { onUpdate(step.copy(delayS = DELAY_PRESETS.lastOrNull { it < step.delayS } ?: 0)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Remove, "Shorter wait")
            }
            Text(
                Fmt.delay(step.delayS.toDouble()).removePrefix("wait "),
                fontFamily = Mono, fontSize = 15.sp, color = Neon.Cyan, modifier = Modifier.width(84.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onUpdate(step.copy(delayS = DELAY_PRESETS.firstOrNull { it > step.delayS } ?: step.delayS)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Add, "Longer wait")
            }
        }
    }
}

@Composable
private fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, empty: String? = null, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Neon.SurfaceHigh) {
            if (options.isEmpty() && empty != null) {
                DropdownMenuItem(text = { Text(empty, color = Neon.TextDim) }, onClick = { open = false }, enabled = false)
            }
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { onPick(k); open = false }) }
        }
    }
}

@Composable
private fun NeonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Neon.Surface, labelColor = Neon.TextDim,
            selectedContainerColor = Neon.Cyan.copy(alpha = 0.18f), selectedLabelColor = Neon.Cyan,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = Neon.Outline, selectedBorderColor = Neon.Cyan,
        ),
    )
}

@Composable
private fun PickerTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Neon.Surface).border(1.dp, Neon.Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Neon.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(value, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TimeDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val h = initial.substringBefore(':').toIntOrNull() ?: 7
    val m = initial.substringAfter(':').toIntOrNull() ?: 0
    val state = rememberTimePickerState(h, m, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.SurfaceHigh,
        title = { Text("Time (hub local)") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DateDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Neon.SurfaceHigh),
        confirmButton = {
            Button(
                onClick = {
                    val ms = state.selectedDateMillis ?: return@Button
                    // DatePicker returns UTC midnight of the chosen day.
                    onPick(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan, contentColor = Neon.Bg),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
