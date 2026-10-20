package com.victorious.pyraclaw.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DashboardScreen(vm: HubViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pyra Claw", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(vm.hubUrl, { vm.hubUrl = it }, label = { Text("Hub URL") })
        OutlinedTextField(vm.token, { vm.token = it }, label = { Text("Auth token") })
        val s = vm.status
        Text(if (s == null) "Hub: unknown" else "Hub v${s.version} up ${s.uptime_s}s, MQTT ${s.mqtt}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refresh() }) { Text("Refresh") }
            Button(onClick = { vm.wakePc() }) { Text("Wake PC") }
            Button(
                onClick = { confirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Power Pulse") }
        }
        vm.message?.let { Text(it) }
    }
    if (confirm) PowerPulseDialog(onDismiss = { confirm = false }, onConfirmed = {
        confirm = false; vm.powerPulse()
    })
}

/** Hold-to-confirm: the user must hold for 2 s before the pulse is sent. */
@Composable
fun PowerPulseDialog(onDismiss: () -> Unit, onConfirmed: () -> Unit, holdMs: Long = 2000) {
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        val start = System.currentTimeMillis()
        while (holding && isActive) {
            progress = ((System.currentTimeMillis() - start).toFloat() / holdMs).coerceAtMost(1f)
            if (progress >= 1f) { holding = false; onConfirmed(); break }
            delay(16)
        }
        if (!holding) progress = 0f
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Press PC power button?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sends a 300 ms pulse to the PC PWR_SW header. Holding power can force-off a running PC.")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            holding = true
                            tryAwaitRelease()
                            holding = false
                        })
                    },
                ) { Box(Modifier.padding(16.dp)) { Text("Hold to confirm") } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NodesScreen(vm: HubViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.padding(16.dp)) {
        items(vm.nodes) { n ->
            ListItem(
                headlineContent = { Text(n.id) },
                supportingContent = { Text(if (n.online) "online" else "offline") },
            )
        }
    }
}

@Composable
fun LogsScreen(vm: HubViewModel) {
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.padding(16.dp)) { items(vm.logs) { Text(it) } }
}

@Composable
fun HelpScreen() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Help", style = MaterialTheme.typography.headlineSmall)
        Text("1. Connect phone to the Pi 5 via USB-C (enable USB tethering) or the same Wi-Fi.")
        Text("2. Enter the hub URL (USB tether default http://192.168.42.1:8787/) and auth token.")
        Text("3. Wake PC sends Wake-on-LAN; Power Pulse presses PWR_SW for 300 ms after hold-to-confirm.")
        Text("See docs/MANUAL.md in the repo for wiring and troubleshooting.")
    }
}
