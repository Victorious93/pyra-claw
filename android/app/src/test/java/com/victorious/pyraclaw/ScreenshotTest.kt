package com.victorious.pyraclaw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.victorious.pyraclaw.data.Approval
import com.victorious.pyraclaw.data.FoundHub
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.service.ServiceState
import com.victorious.pyraclaw.ui.PC_POWER_REQUEST
import com.victorious.pyraclaw.ui.PyraBottomBar
import com.victorious.pyraclaw.ui.Tab
import com.victorious.pyraclaw.ui.activity.ActivityScreen
import com.victorious.pyraclaw.ui.activity.ActivityUi
import com.victorious.pyraclaw.ui.approval.ApprovalPhase
import com.victorious.pyraclaw.ui.approval.ApprovalSheetContent
import com.victorious.pyraclaw.ui.approval.ApprovalUi
import com.victorious.pyraclaw.ui.components.Load
import com.victorious.pyraclaw.ui.components.LocalNow
import com.victorious.pyraclaw.ui.dashboard.DashboardScreen
import com.victorious.pyraclaw.ui.dashboard.DashboardUi
import com.victorious.pyraclaw.ui.dashboard.WakeState
import com.victorious.pyraclaw.ui.devices.DeviceResult
import com.victorious.pyraclaw.ui.devices.DevicesScreen
import com.victorious.pyraclaw.ui.devices.DevicesUi
import com.victorious.pyraclaw.ui.help.HelpScreen
import com.victorious.pyraclaw.ui.onboarding.OnboardingScreen
import com.victorious.pyraclaw.ui.onboarding.OnboardingUi
import com.victorious.pyraclaw.ui.onboarding.TestState
import com.victorious.pyraclaw.ui.settings.DeviceFacts
import com.victorious.pyraclaw.ui.settings.SettingsScreen
import com.victorious.pyraclaw.ui.settings.SettingsUi
import com.victorious.pyraclaw.ui.theme.Neon
import com.victorious.pyraclaw.ui.theme.PyraTheme
import com.victorious.pyraclaw.ui.timers.SaveState
import com.victorious.pyraclaw.ui.timers.TimerDraft
import com.victorious.pyraclaw.ui.timers.TimerEditor
import com.victorious.pyraclaw.ui.timers.TimersScreen
import com.victorious.pyraclaw.ui.timers.TimersUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screen composables with sample data (Robolectric native graphics) and writes
 * PNGs to docs/img/app/. These are genuine renders of this code, not mockups; the data is fake.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("pyra.screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private val now = Sample.NOW_MS

    private fun shot(name: String, tab: Tab?, content: @Composable () -> Unit) {
        rule.setContent {
            PyraTheme {
                CompositionLocalProvider(LocalNow provides now) {
                    if (tab == null) Box(Modifier.fillMaxSize().background(Neon.Bg)) { content() }
                    else Scaffold(containerColor = Neon.Bg, bottomBar = { PyraBottomBar(tab.route) {} }) { pad ->
                        Box(Modifier.padding(pad).fillMaxSize()) { content() }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Test fun s01_onboarding() = shot("01-onboarding", null) {
        OnboardingScreen(
            OnboardingUi(
                url = "http://192.168.1.20:8787/", token = "s3cr3t-token-value",
                found = listOf(FoundHub("Pyra Claw Hub on pyra", "http://192.168.1.20:8787/", "mDNS"), FoundHub("192.168.42.1:8787", "http://192.168.42.1:8787/", "address probe")),
                scannedOnce = true, test = TestState.Success("http://192.168.1.20:8787/", Sample.status),
            ),
            canGoBack = false, onUrl = {}, onToken = {}, onScan = {}, onPick = {}, onTest = {}, onSave = {}, onBack = {},
        )
    }

    @Test fun s02_dashboard() = shot("02-dashboard", Tab.Dashboard) {
        DashboardScreen(
            DashboardUi(
                link = LinkState.Connected(now - 60_000), status = Sample.status, lastUpdateMs = now,
                manualTimers = Sample.timers.filter { it.schedule.kind == "manual" }, runningTimers = Sample.timers.filter { it.running },
                wake = WakeState.Sent(102, true, now - 12_000),
            ),
            now, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun s03_dashboard_offline() = shot("03-dashboard-offline", Tab.Dashboard) {
        DashboardScreen(
            DashboardUi(link = LinkState.Retrying("failed to connect", now + 8_000), restError = HubError.Unreachable("failed to connect to /192.168.1.20:8787")),
            now, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun s04_approval() = shot("04-approval-countdown", null) {
        ApprovalSheetContent(
            ApprovalUi(PC_POWER_REQUEST, ApprovalPhase.Armed(Approval("pc.power_pulse", "t", 30, now + 22_000), 22)), {}, {}, {},
        )
    }

    @Test fun s05_devices() = shot("05-devices", Tab.Devices) {
        DevicesScreen(
            DevicesUi(Load.Ready(Sample.devices), results = mapOf("phone1" to DeviceResult("Charging on: ok", true, now - 5_000))),
            now, {}, { _, _ -> }, {}, {}, {},
        )
    }

    @Test fun s06_timers() = shot("06-timers", Tab.Timers) {
        TimersScreen(TimersUi(Load.Ready(Sample.timers), Sample.timerEvents, Sample.devices, live = true), now, {}, {}, {}, { _, _ -> }, {}, {})
    }

    @Test fun s07_timer_editor() = shot("07-timer-editor", null) {
        TimerEditor(
            TimerDraft.from(Sample.timers[1]).copy(id = null, name = "Weekday shutdown"), Sample.devices, SaveState.Idle,
            needsArm = true, onChange = {}, onMove = { _, _ -> }, onSave = {}, onClose = {},
        )
    }

    @Test fun s08_activity() = shot("08-activity", Tab.Activity) {
        ActivityScreen(ActivityUi(Load.Ready(Sample.audit)), emptyList(), {}, {}, {})
    }

    @Test fun s09_settings() = shot("09-settings", Tab.Settings) {
        SettingsScreen(
            SettingsUi(
                HubSettings("http://192.168.1.20:8787/", "x", paired = true, backgroundLink = true),
                ServiceState(running = true, connected = true, fgsType = "connectedDevice"),
                LiveSnapshot(LinkState.Connected(now), Sample.status),
            ),
            DeviceFacts(batteryExempt = false, notificationsAllowed = true), now, {}, {}, {}, {}, {}, {},
        )
    }

    @Test fun s10_help() = shot("10-help", Tab.Help) { HelpScreen(initiallyExpanded = setOf("approvals")) }
}
