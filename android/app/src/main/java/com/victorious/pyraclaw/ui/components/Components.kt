package com.victorious.pyraclaw.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon
import com.victorious.pyraclaw.ui.theme.Overline

/** App-wide snackbar host so any screen can report a result. */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Generic screen data state: every list screen shows exactly one of these. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val error: HubError) : Load<Nothing>
}

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    accent: Color = Neon.Outline,
    glow: Boolean = false,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(Neon.CardGlow)
            .border(1.dp, if (glow) Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.15f))) else Brush.linearGradient(listOf(accent, accent)), shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Overline, color = Neon.TextDim, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 10.dp) {
    val a = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        v
    } else 1f
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

fun LinkState.color(): Color = when (this) {
    is LinkState.Connected -> Neon.Green
    is LinkState.Connecting -> Neon.Amber
    is LinkState.Retrying -> Neon.Amber
    LinkState.Unauthorized -> Neon.Red
    LinkState.NotPaired -> Neon.TextFaint
}

fun LinkState.label(nowMs: Long = System.currentTimeMillis()): String = when (this) {
    is LinkState.Connected -> "Live"
    is LinkState.Connecting -> if (attempt <= 1) "Connecting…" else "Connecting… (try $attempt)"
    is LinkState.Retrying -> "Offline · retry in ${((retryAtMs - nowMs) / 1000).coerceAtLeast(0)}s"
    LinkState.Unauthorized -> "Token rejected"
    LinkState.NotPaired -> "Not paired"
}

@Composable
fun ConnectionPill(link: LinkState, nowMs: Long, modifier: Modifier = Modifier) {
    val c = link.color()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c, pulsing = link is LinkState.Connected || link is LinkState.Connecting, size = 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(link.label(nowMs), style = MaterialTheme.typography.labelMedium, color = c)
    }
}

@Composable
fun Tag(text: String, color: Color = Neon.Cyan, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim, modifier = Modifier.weight(0.34f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let { if (mono) it.copy(fontFamily = Mono) else it },
            color = Neon.Text,
            modifier = Modifier.weight(0.66f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun CenteredState(
    icon: ImageVector,
    title: String,
    body: String,
    tint: Color = Neon.Cyan,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(tint.copy(alpha = 0.10f))
                .border(1.dp, tint.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.size(4.dp)); it() }
    }
}

@Composable
fun ErrorState(error: HubError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CenteredState(
        Icons.Filled.CloudOff, error.title,
        listOfNotNull(error.hint, error.detail?.let { "Details: $it" }).joinToString("\n\n"),
        tint = Neon.Red, modifier = modifier,
    ) {
        FilledTonalButton(
            onClick = onRetry,
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Neon.Cyan.copy(alpha = 0.16f), contentColor = Neon.Cyan),
        ) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Try again")
        }
    }
}

/** Inline error banner for when cached data is still shown. */
@Composable
fun ErrorBanner(error: HubError, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Neon.Red.copy(alpha = 0.10f))
            .border(1.dp, Neon.Red.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CloudOff, null, tint = Neon.Red, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(error.title, style = MaterialTheme.typography.titleSmall, color = Neon.Text)
            Text(error.hint, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
        }
        onRetry?.let { OutlinedButton(onClick = it) { Text("Retry") } }
    }
}

@Composable
fun LoadingState(label: String = "Talking to the hub…") {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Neon.Cyan, strokeWidth = 3.dp)
        Spacer(Modifier.size(14.dp))
        Text(label, color = Neon.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GradientTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(brush = Neon.CyanMagenta, fontWeight = FontWeight.Black),
    )
}

/** Wall clock that ticks every [periodMs] so "last seen 12 s ago" labels stay fresh. */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    val v by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(periodMs); value = System.currentTimeMillis() }
    }
    return v
}

/** Overridable clock for deterministic screenshots/tests. */
val LocalNow = staticCompositionLocalOf<Long?> { null }

@Composable
fun now(): Long = LocalNow.current ?: rememberNow()
