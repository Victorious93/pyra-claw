package com.victorious.pyraclaw.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.victorious.pyraclaw.AppContainer
import com.victorious.pyraclaw.service.HubConnectionService
import com.victorious.pyraclaw.service.ServiceBus
import com.victorious.pyraclaw.ui.activity.ActivityScreen
import com.victorious.pyraclaw.ui.activity.ActivityViewModel
import com.victorious.pyraclaw.ui.approval.ApprovalRequest
import com.victorious.pyraclaw.ui.approval.ApprovalSheet
import com.victorious.pyraclaw.ui.approval.ApprovalViewModel
import com.victorious.pyraclaw.ui.components.LocalSnackbar
import com.victorious.pyraclaw.ui.components.now
import com.victorious.pyraclaw.ui.dashboard.DashboardScreen
import com.victorious.pyraclaw.ui.dashboard.DashboardViewModel
import com.victorious.pyraclaw.ui.devices.DevicesScreen
import com.victorious.pyraclaw.ui.devices.DevicesViewModel
import com.victorious.pyraclaw.ui.help.HelpScreen
import com.victorious.pyraclaw.ui.onboarding.OnboardingScreen
import com.victorious.pyraclaw.ui.onboarding.OnboardingViewModel
import com.victorious.pyraclaw.ui.settings.DeviceFacts
import com.victorious.pyraclaw.ui.settings.SettingsScreen
import com.victorious.pyraclaw.ui.settings.SettingsViewModel
import com.victorious.pyraclaw.ui.theme.Neon
import com.victorious.pyraclaw.ui.timers.TimerEditorDialog
import com.victorious.pyraclaw.ui.timers.TimersScreen
import com.victorious.pyraclaw.ui.timers.TimersViewModel
import kotlinx.coroutines.flow.Flow

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Filled.Dashboard),
    Devices("devices", "Devices", Icons.Filled.Devices),
    Timers("timers", "Timers", Icons.Filled.Timer),
    Activity("activity", "Activity", Icons.Filled.History),
    Settings("settings", "Settings", Icons.Filled.Settings),
    Help("help", "Help", Icons.AutoMirrored.Filled.HelpOutline),
}

val PC_POWER_REQUEST = ApprovalRequest(
    command = "pc.power_pulse",
    title = "Press the PC power button",
    body = "Sends a signed 300 ms pulse through the ESP32's PC817 to the PC's power header. " +
        "If the PC is on, this may sleep or shut it down, depending on its power settings.",
    actionLabel = "Press power button",
    args = kotlinx.serialization.json.JsonObject(mapOf("ms" to kotlinx.serialization.json.JsonPrimitive(300))),
)

@Composable
private fun Messages(flow: Flow<String>, host: SnackbarHostState) {
    LaunchedEffect(flow) { flow.collect { if (it.isNotBlank()) host.showSnackbar(it) } }
}

@Composable
fun PyraAppUi(c: AppContainer) {
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var editingHub by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        if (!settings.isConfigured || editingHub) {
            val vm: OnboardingViewModel = viewModel(key = "onboarding-$editingHub") {
                OnboardingViewModel(c.repository, c.settings) { c.discovery.scan() }
            }
            val ui by vm.ui.collectAsStateWithLifecycle()
            LaunchedEffect(ui.saved) { if (ui.saved) editingHub = false }
            OnboardingScreen(
                ui, canGoBack = settings.isConfigured,
                onUrl = vm::setUrl, onToken = vm::setToken, onScan = vm::startScan, onPick = vm::pick,
                onTest = vm::test, onSave = vm::save, onBack = { editingHub = false },
            )
        } else {
            MainScaffold(c, snackbar, onChangeHub = { editingHub = true })
        }
    }
}

@Composable
private fun MainScaffold(c: AppContainer, snackbar: SnackbarHostState, onChangeHub: () -> Unit) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val approvals: ApprovalViewModel = viewModel { ApprovalViewModel(c.repository) }
    val approval by approvals.ui.collectAsStateWithLifecycle()
    Messages(approvals.messages, snackbar)
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val nowMs = now()

    Scaffold(
        containerColor = Neon.Bg,
        snackbarHost = {
            SnackbarHost(snackbar) { Snackbar(it, containerColor = Neon.SurfaceHighest, contentColor = Neon.Text, actionColor = Neon.Cyan) }
        },
        bottomBar = {
            PyraBottomBar(current) { t ->
                nav.navigate(t.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Tab.Dashboard.route, modifier = Modifier.padding(pad).fillMaxSize()) {
            composable(Tab.Dashboard.route) {
                val vm: DashboardViewModel = viewModel { DashboardViewModel(c.repository, c.live.state, c.live::reconnectNow, settings = c.settings, direct = c.direct) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                Messages(vm.messages, snackbar)
                DashboardScreen(
                    ui, nowMs, onRefresh = vm::refresh, onWake = vm::wakePc,
                    onPowerButton = { approvals.open(PC_POWER_REQUEST) },
                    onRunTimer = vm::runTimer,
                    onOpenDevices = { nav.navigate(Tab.Devices.route) { launchSingleTop = true } },
                    onOpenTimers = { nav.navigate(Tab.Timers.route) { launchSingleTop = true } },
                )
            }
            composable(Tab.Devices.route) {
                val vm: DevicesViewModel = viewModel { DevicesViewModel(c.repository) }
                val dash: DashboardViewModel = viewModel { DashboardViewModel(c.repository, c.live.state, c.live::reconnectNow, settings = c.settings, direct = c.direct) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                Messages(vm.messages, snackbar)
                Messages(dash.messages, snackbar)
                DevicesScreen(
                    ui, nowMs, onRefresh = vm::refresh, onRun = vm::run,
                    onPowerOff = { d ->
                        approvals.open(
                            ApprovalRequest(
                                command = "device.power_off", target = d.id,
                                title = "Power off ${d.label ?: d.id}",
                                body = "Runs the hub's power_off backend (${d.power_off_backend}) for ${d.id}. Turning it back on may need someone to press its button.",
                                actionLabel = "Power off ${d.id}",
                            ),
                        )
                    },
                    onWakePc = dash::wakePc,
                    onPcPowerButton = { approvals.open(PC_POWER_REQUEST) },
                )
            }
            composable(Tab.Timers.route) {
                val vm: TimersViewModel = viewModel { TimersViewModel(c.repository, c.live.state) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                Messages(vm.messages, snackbar)
                TimersScreen(
                    ui, nowMs, onRefresh = vm::refresh, onNew = vm::newTimer, onEdit = vm::edit,
                    onToggle = vm::setEnabled, onRun = vm::run, onDelete = vm::delete,
                )
                ui.editor?.let { draft ->
                    TimerEditorDialog(
                        draft, ui.devices, ui.save, needsArm = vm.draftNeedsArm(),
                        onChange = vm::updateDraft, onMove = vm::moveStep, onSave = vm::save, onClose = vm::closeEditor,
                    )
                }
            }
            composable(Tab.Activity.route) {
                val vm: ActivityViewModel = viewModel { ActivityViewModel(c.repository) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                val svc by ServiceBus.state.collectAsStateWithLifecycle()
                ActivityScreen(ui, svc.log, onRefresh = vm::refresh, onFilter = vm::setFilter, onQuery = vm::setQuery)
            }
            composable(Tab.Settings.route) {
                val vm: SettingsViewModel = viewModel { SettingsViewModel(c.settings, c.repository, c.live.state, direct = c.direct) }
                val ui by vm.ui.collectAsStateWithLifecycle()
                var facts by remember { mutableStateOf(readFacts(ctx)) }
                var tick by remember { mutableIntStateOf(0) }
                LifecycleResumeEffect(tick) { facts = readFacts(ctx); onPauseOrDispose { } }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
                fun askNotifications() {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                SettingsScreen(
                    ui, facts, nowMs,
                    onChangeHub = onChangeHub,
                    onTest = vm::testConnection,
                    onForget = { HubConnectionService.stop(ctx); vm.forget() },
                    onBackgroundLink = { on ->
                        vm.setBackgroundLink(on)
                        if (on) {
                            if (!facts.notificationsAllowed) askNotifications()
                            HubConnectionService.start(ctx)
                        } else HubConnectionService.stop(ctx)
                    },
                    onBatteryExemption = { openBatteryExemption(ctx) },
                    onNotifications = ::askNotifications,
                    onSaveDirect = vm::saveDirect,
                    onTestDirect = vm::testDirect,
                    onClearDirect = vm::clearDirect,
                )
            }
            composable(Tab.Help.route) { HelpScreen() }
        }
    }

    approval?.let { a ->
        ApprovalSheet(a, onHoldComplete = approvals::requestApproval, onSend = approvals::send, onDismiss = approvals::dismiss)
    }
}

@Composable
fun PyraBottomBar(currentRoute: String?, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Neon.Surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = currentRoute == t.route,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, t.label) },
                label = { Text(t.label, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Visible, softWrap = false) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Neon.Cyan, selectedTextColor = Neon.Cyan,
                    indicatorColor = Neon.Cyan.copy(alpha = 0.14f),
                    unselectedIconColor = Neon.TextDim, unselectedTextColor = Neon.TextDim,
                ),
            )
        }
    }
}

private fun readFacts(ctx: Context): DeviceFacts {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val notif = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    return DeviceFacts(batteryExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName), notificationsAllowed = notif)
}

@android.annotation.SuppressLint("BatteryLife") // sideloaded fleet tool; the user asks for it explicitly
private fun openBatteryExemption(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}
