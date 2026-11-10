@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.victorious.pyraclaw.ui.devices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.CenteredState
import com.victorious.pyraclaw.ui.components.ErrorState
import com.victorious.pyraclaw.ui.components.Load
import com.victorious.pyraclaw.ui.components.LoadingState
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.components.Tag
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun DevicesScreen(
    ui: DevicesUi,
    nowMs: Long,
    onRefresh: () -> Unit,
    onRun: (deviceId: String, command: String) -> Unit,
    onPowerOff: (DeviceDto) -> Unit,
    onWakePc: () -> Unit,
    onPcPowerButton: () -> Unit,
) {
    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Devices", style = MaterialTheme.typography.headlineMedium) }
            item { SectionHeader("PC") }
            item { PcCard(onWakePc, onPcPowerButton) }
            item { SectionHeader("Phones and other devices") }
            when (val d = ui.devices) {
                Load.Loading -> item { Box(Modifier.fillMaxWidth().height(220.dp)) { LoadingState() } }
                is Load.Failed -> item { ErrorState(d.error, onRefresh) }
                is Load.Ready -> if (d.data.isEmpty()) {
                    item {
                        CenteredState(
                            Icons.Filled.PhoneAndroid, "No devices configured",
                            "Add [devices.<id>] blocks to /etc/pyra-claw/config.toml on the hub (charge, power_on, power_off backends), then restart pyra-hub. See Help > Devices.",
                        )
                    }
                } else items(d.data, key = { it.id }) { dev ->
                    DeviceCard(dev, ui, nowMs, onRun, onPowerOff)
                }
            }
        }
    }
}

@Composable
private fun PcCard(onWake: () -> Unit, onPower: () -> Unit) {
    NeonCard(accent = Neon.Cyan.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Computer, Neon.Cyan)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Desktop PC", style = MaterialTheme.typography.titleMedium)
                Text("Wake-on-LAN + ESP32 PC817 on the power header", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onWake, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Wake")
            }
            OutlinedButton(
                onClick = onPower, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Neon.Magenta),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Power")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    d: DeviceDto,
    ui: DevicesUi,
    nowMs: Long,
    onRun: (String, String) -> Unit,
    onPowerOff: (DeviceDto) -> Unit,
) {
    val result = ui.results[d.id]
    NeonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.PhoneAndroid, Neon.Violet)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label ?: d.id, style = MaterialTheme.typography.titleMedium)
                Text(d.id, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Neon.TextDim)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("charge: ${d.charge_backend ?: "none"}", if (d.charge_backend != null) Neon.Cyan else Neon.TextFaint)
            Tag("on: ${d.power_on_backend ?: "none"}", if (d.power_on_backend != null) Neon.Cyan else Neon.TextFaint)
            Tag("off: ${d.power_off_backend ?: "none"}", if (d.power_off_backend != null) Neon.Magenta else Neon.TextFaint)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.charge_backend != null) {
                ActionButton("Charge on", Icons.Filled.BatteryChargingFull, Neon.Green, "${d.id}|charge.on" in ui.busy) { onRun(d.id, "charge.on") }
                ActionButton("Charge off", Icons.Outlined.BatteryAlert, Neon.Amber, "${d.id}|charge.off" in ui.busy) { onRun(d.id, "charge.off") }
            }
            if (d.power_on_backend != null) {
                ActionButton("Power on", Icons.Filled.PowerSettingsNew, Neon.Cyan, "${d.id}|device.power_on" in ui.busy) { onRun(d.id, "device.power_on") }
            }
            if (d.power_off_backend != null) {
                ActionButton("Power off", Icons.Filled.PowerOff, Neon.Magenta, false) { onPowerOff(d) }
            }
            if (d.charge_backend == null && d.power_on_backend == null && d.power_off_backend == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SettingsInputComponent, null, tint = Neon.TextFaint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("No backends configured for this device", style = MaterialTheme.typography.bodySmall, color = Neon.TextFaint)
                }
            }
        }
        if (result != null) {
            Text(
                "${result.text} · ${Fmt.relative(result.atMs / 1000.0, nowMs)}",
                style = MaterialTheme.typography.bodySmall, color = if (result.ok) Neon.Green else Neon.Red,
            )
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, color: Color, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = !busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
        else Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color)
    }
}
