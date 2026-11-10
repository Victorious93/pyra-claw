package com.victorious.pyraclaw.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.victorious.pyraclaw.R
import com.victorious.pyraclaw.ui.Fmt
import com.victorious.pyraclaw.ui.components.GradientTitle
import com.victorious.pyraclaw.ui.components.NeonCard
import com.victorious.pyraclaw.ui.components.SectionHeader
import com.victorious.pyraclaw.ui.components.Tag
import com.victorious.pyraclaw.ui.theme.Mono
import com.victorious.pyraclaw.ui.theme.Neon

@Composable
fun OnboardingScreen(
    ui: OnboardingUi,
    canGoBack: Boolean,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onScan: () -> Unit,
    onPick: (com.victorious.pyraclaw.data.FoundHub) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.pyra_logo), null, Modifier.size(132.dp))
            GradientTitle("Pair your hub")
            Text(
                "Pyra Claw talks to the hub on your Raspberry Pi 5 over USB tethering or Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium, color = Neon.TextDim, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
            )
        }

        SectionHeader("1 · Find the hub")
        NeonCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scan this network", style = MaterialTheme.typography.titleMedium)
                    Text("mDNS plus the usual addresses (USB tether, pyra.local)", style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
                }
                OutlinedButton(onClick = onScan, enabled = !ui.scanning) {
                    if (ui.scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Radar, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (ui.scanning) "Scanning" else "Scan")
                }
            }
            ui.found.forEach { hub ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(hub) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Router, null, tint = Neon.Cyan)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hub.name, style = MaterialTheme.typography.titleSmall)
                        Text(hub.url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Neon.TextDim)
                    }
                    Tag(hub.via)
                }
            }
            if (ui.scannedOnce && !ui.scanning && ui.found.isEmpty()) {
                Text(
                    "No hub answered. Type its address below (find it with `hostname -I` on the Pi).",
                    style = MaterialTheme.typography.bodySmall, color = Neon.Amber,
                )
            }
        }

        SectionHeader("2 · Address and token")
        OutlinedTextField(
            value = ui.url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth().testTag("hubUrl"),
            label = { Text("Hub URL") }, placeholder = { Text("http://192.168.1.20:8787") },
            leadingIcon = { Icon(Icons.Filled.Link, null) }, singleLine = true,
            isError = ui.urlError != null,
            supportingText = { Text(ui.urlError ?: "Port 8787 is added if you leave it out") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = ui.token, onValueChange = onToken, modifier = Modifier.fillMaxWidth().testTag("hubToken"),
            label = { Text("Auth token") }, leadingIcon = { Icon(Icons.Filled.Key, null) }, singleLine = true,
            isError = ui.tokenError != null,
            supportingText = { Text(ui.tokenError ?: "Stored encrypted with the Android Keystore") },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showToken) "Hide token" else "Show token")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )

        SectionHeader("3 · Test and save")
        TestResult(ui.test)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f).height(50.dp), enabled = ui.test !is TestState.Testing) {
                Text("Test connection")
            }
            Button(
                onClick = onSave, modifier = Modifier.weight(1f).height(50.dp).testTag("saveHub"),
                colors = ButtonDefaults.buttonColors(containerColor = Neon.Cyan, contentColor = Neon.Bg),
            ) { Text(if (ui.test is TestState.Success) "Save & continue" else "Save anyway", fontWeight = FontWeight.Bold) }
        }
        Text(
            "Need help? Help > Pairing explains where the token lives and how USB tethering works.",
            style = MaterialTheme.typography.bodySmall, color = Neon.TextFaint, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun TestResult(t: TestState) {
    when (t) {
        TestState.Idle -> Text(
            "Test checks GET /health (address) then GET /api/status (token).",
            style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
        )
        TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
            Text("Testing…", color = Neon.TextDim)
        }
        is TestState.Success -> NeonCard(accent = Neon.Green, glow = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Neon.Green); Spacer(Modifier.width(10.dp))
                Text("Connected to hub v${t.status.version}", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Protocol ${t.status.protocol ?: "?"} · up ${Fmt.uptime(t.status.uptime_s)} · MQTT ${if (t.status.mqtt) "connected" else "down"} · ${t.status.nodes.size} node(s)",
                style = MaterialTheme.typography.bodySmall, color = Neon.TextDim,
            )
        }
        is TestState.Failure -> NeonCard(accent = Neon.Red, glow = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Neon.Red); Spacer(Modifier.width(10.dp))
                Text(t.error.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(t.error.hint, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            t.error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), color = Neon.TextFaint) }
        }
    }
}
