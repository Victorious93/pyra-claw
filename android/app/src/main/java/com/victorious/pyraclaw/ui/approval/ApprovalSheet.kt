@file:OptIn(ExperimentalMaterial3Api::class)

package com.victorious.pyraclaw.ui.approval

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ApprovalSheet(
    ui: ApprovalUi,
    onHoldComplete: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Neon.Surface,
        contentColor = Neon.Text,
        scrimColor = Neon.Bg.copy(alpha = 0.8f),
    ) {
        ApprovalSheetContent(ui, onHoldComplete, onSend, onDismiss)
    }
}

/** Sheet body; separate so tests and screenshots can render it without a window. */
@Composable
fun ApprovalSheetContent(
    ui: ApprovalUi,
    onHoldComplete: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 22.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Neon.Magenta.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.WarningAmber, null, tint = Neon.Magenta) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("APPROVAL REQUIRED", style = MaterialTheme.typography.labelSmall, color = Neon.Magenta)
                Text(ui.request.title, style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(ui.request.body, style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim)
        StepIndicator(ui.phase)
        AnimatedContent(ui.phase::class, label = "phase") { _ ->
            when (val p = ui.phase) {
                ApprovalPhase.Review -> HoldToConfirm("Hold to request approval", onHoldComplete)
                ApprovalPhase.Requesting -> Busy("Asking the hub for a one-time token…")
                is ApprovalPhase.Armed -> Armed(p, ui.request.actionLabel, onSend)
                ApprovalPhase.Sending -> Busy("Sending ${ui.request.actionLabel.lowercase()}…")
                is ApprovalPhase.Done -> Outcome(Icons.Filled.CheckCircle, Neon.Green, "Done", p.summary)
                is ApprovalPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Outcome(Icons.Filled.ErrorOutline, Neon.Red, p.error.title, listOfNotNull(p.error.hint, p.error.detail).joinToString("\n"))
                    OutlinedButton(onClick = onHoldComplete, modifier = Modifier.fillMaxWidth()) { Text("Request a new approval") }
                }
                ApprovalPhase.Expired -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Outcome(Icons.Filled.HourglassBottom, Neon.Amber, "Approval expired", "Nothing was sent. Tokens last 30 seconds and work once.")
                    OutlinedButton(onClick = onHoldComplete, modifier = Modifier.fillMaxWidth()) { Text("Request again") }
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (ui.phase is ApprovalPhase.Done) "Close" else "Cancel", color = Neon.TextDim)
        }
    }
}

@Composable
private fun StepIndicator(phase: ApprovalPhase) {
    val step = when (phase) {
        ApprovalPhase.Review, ApprovalPhase.Requesting -> 0
        is ApprovalPhase.Armed, ApprovalPhase.Sending, ApprovalPhase.Expired, is ApprovalPhase.Failed -> 1
        is ApprovalPhase.Done -> 2
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Confirm", "Approve & send", "Done").forEachIndexed { i, label ->
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) Neon.Magenta else Neon.Outline),
                )
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (i <= step) Neon.Text else Neon.TextFaint, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Press and hold for [holdMs]; light haptic ticks while filling, a strong one at the end. */
@Composable
fun HoldToConfirm(label: String, onComplete: () -> Unit, holdMs: Long = 1500) {
    val haptics = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(holding) {
        if (!holding) { progress = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        var lastTick = 0
        while (isActive && holding) {
            progress = ((System.currentTimeMillis() - start).toFloat() / holdMs).coerceAtMost(1f)
            val tick = (progress * 5).toInt()
            if (tick > lastTick) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastTick = tick }
            if (progress >= 1f) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                holding = false
                onComplete()
                break
            }
            delay(16)
        }
    }
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp))
            .background(Neon.Magenta.copy(alpha = 0.08f))
            .border(1.dp, Neon.Magenta.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .testTag("holdToConfirm")
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)
                .background(Neon.Magenta.copy(alpha = 0.35f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PowerSettingsNew, null, tint = Neon.Magenta, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (holding) "Keep holding…" else label, style = MaterialTheme.typography.titleSmall, color = Neon.Text)
        }
    }
}

@Composable
private fun Armed(p: ApprovalPhase.Armed, actionLabel: String, onSend: () -> Unit) {
    val frac = p.secondsLeft.toFloat() / p.approval.ttlS.coerceAtLeast(1)
    val color = if (p.secondsLeft <= 5) Neon.Red else if (p.secondsLeft <= 10) Neon.Amber else Neon.Cyan
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { frac }, modifier = Modifier.size(64.dp), color = color,
                    trackColor = Neon.Outline, strokeWidth = 5.dp,
                )
                Text("${p.secondsLeft}", fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Approval token active", style = MaterialTheme.typography.titleMedium)
                Text("Single use · expires in ${p.secondsLeft}s · logged in the hub audit", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            }
        }
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("approvalSend"),
            colors = ButtonDefaults.buttonColors(containerColor = Neon.Magenta, contentColor = Neon.Bg),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Send: $actionLabel", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(26.dp), color = Neon.Cyan, strokeWidth = 3.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, color = Neon.TextDim)
    }
}

@Composable
private fun Outcome(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Neon.Text)
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
        }
    }
}
