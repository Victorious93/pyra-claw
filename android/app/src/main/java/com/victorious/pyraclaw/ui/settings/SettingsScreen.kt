package com.victorious.pyraclaw.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.BuildConfig
import com.victorious.pyraclaw.R
import com.victorious.pyraclaw.fleet.DroidCommandNode
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.ConnectionPill
import com.victorious.pyraclaw.ui.components.GradientTitle
import com.victorious.pyraclaw.ui.components.KeyValue
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.onboarding.TestState
import com.victorious.pyraclaw.ui.theme.Neon

/** Phone-side facts the screen shows; read by the host so this stays previewable. */
data class DeviceFacts(
    val batteryExempt: Boolean,
    val notificationsAllowed: Boolean,
)

@Composable
fun SettingsScreen(
    ui: SettingsUi,
    facts: DeviceFacts,
    nowMs: Long,
    onChangeHub: () -> Unit,
    onTest: () -> Unit,
    onForget: () -> Unit,
    onBackgroundLink: (Boolean) -> Unit,
    onBatteryExemption: () -> Unit,
    onNotifications: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionHeader("Hub")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Hub, null, tint = Neon.Cyan)
                Spacer(Modifier.width(10.dp))
                Text("Paired hub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ConnectionPill(ui.live.link, nowMs)
            }
            KeyValue("URL", ui.settings.hubUrl, mono = true)
            KeyValue("Token", if (ui.settings.token.isNotBlank()) "Saved · Keystore-encrypted" else "Not set")
            ui.live.status?.let {
                KeyValue("Hub version", "v${it.version} (protocol ${it.protocol ?: "?"})")
                KeyValue("Uptime", Fmt.uptime(it.uptime_s))
                KeyValue("MQTT broker", if (it.mqtt) "Connected" else "Down")
            }
            when (val t = ui.test) {
                TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Testing…", color = Neon.TextDim)
                }
                is TestState.Success -> Text("✓ Hub v${t.status.version} answered, token accepted", color = Neon.Green, style = MaterialTheme.typography.bodySmall)
                is TestState.Failure -> Text("${t.error.title}. ${t.error.hint}", color = Neon.Red, style = MaterialTheme.typography.bodySmall)
                TestState.Idle -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onChangeHub, modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = Neon.Cyan.copy(alpha = 0.16f), contentColor = Neon.Cyan),
                ) { Text("Change hub") }
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) { Text("Test") }
            }
            TextButton(onClick = { confirmForget = true }) { Text("Forget this hub", color = Neon.Red) }
        }

        SectionHeader("Background link")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep hub link with the screen off", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !ui.service.running -> "Off. Timers still run on the hub; you just won't get live updates or droidcommand-AI commands while asleep."
                            ui.service.connected -> "Running · connected (${ui.service.fgsType})"
                            else -> "Running · connecting…"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
                    )
                }
                Switch(
                    checked = ui.settings.backgroundLink, onCheckedChange = onBackgroundLink,
                    enabled = ui.settings.isConfigured,
                    colors = SwitchDefaults.colors(checkedTrackColor = Neon.Cyan, checkedThumbColor = Neon.Bg),
                )
            }
            PermissionRow(
                Icons.Filled.BatterySaver, "Battery optimization",
                if (facts.batteryExempt) "Unrestricted · Android won't kill the link" else "Restricted · Android may stop the link when idle",
                facts.batteryExempt, "Allow", onBatteryExemption,
            )
            PermissionRow(
                Icons.Filled.NotificationsActive, "Notifications",
                if (facts.notificationsAllowed) "Allowed · status notification visible" else "Blocked · Android needs this for the background link",
                facts.notificationsAllowed, "Allow", onNotifications,
            )
        }

        SectionHeader("Appearance")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Palette, null, tint = Neon.Magenta)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Victorious fleet neon (dark)", style = MaterialTheme.typography.titleMedium)
                    Text("Shared by every fleet app. Always dark, no white screens.", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Swatch(Neon.Bg, "#0B0F1A"); Swatch(Neon.Cyan, "#00E5FF"); Swatch(Neon.Magenta, "#FF2BD6")
            }
        }

        SectionHeader("droidcommand-AI")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SettingsEthernet, null, tint = Neon.Violet)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("EXTERNAL_NODE \"${DroidCommandNode.NODE_ID}\"", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Registration is broadcast on launch. The contract is still a draft, so droidcommand-AI may not pick it up yet.",
                        style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
                    )
                }
            }
            KeyValue("Commands", DroidCommandNode.CAPABILITIES.size.toString())
            KeyValue("Need approval", DroidCommandNode.APPROVAL_REQUIRED.joinToString())
        }

        SectionHeader("About")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.pyra_logo), null, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    GradientTitle("Pyra Claw")
                    Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.DEBUG) "debug" else "release"}", color = Neon.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
            KeyValue("Package", BuildConfig.APPLICATION_ID, mono = true)
            KeyValue("Hub protocol", BuildConfig.PROTOCOL_VERSION)
            KeyValue("Source", "github.com/Victorious93/pyra-claw")
            KeyValue("License", "MIT")
            Text(
                "Not yet tested on real hardware (Pi 5, ESP32, phones). Report issues on GitHub.",
                style = MaterialTheme.typography.bodySmall, color = Neon.Amber,
            )
        }
        Spacer(Modifier.size(24.dp))
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = Neon.SurfaceHigh,
            title = { Text("Forget this hub?") },
            text = { Text("Deletes the saved URL and token from this phone and stops the background link. The hub itself is unchanged.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget", color = Neon.Red) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (ok) Neon.Green else Neon.Amber)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
        }
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun Swatch(c: Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c).border(1.dp, Neon.Outline, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Neon.TextDim)
    }
}

