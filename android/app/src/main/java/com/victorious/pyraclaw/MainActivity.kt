package com.victorious.pyraclaw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.victorious.pyraclaw.fleet.DroidCommandNode
import com.victorious.pyraclaw.ui.*

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Nodes("Nodes", Icons.Filled.Hub),
    Timer("Timer", Icons.Filled.Timer),
    Logs("Logs", Icons.Filled.List),
    Help("Help", Icons.Filled.Info),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DroidCommandNode.register(this)
        // Android 13+: the foreground-service notification needs POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: HubViewModel = viewModel()
                var tab by remember { mutableStateOf(Tab.Dashboard) }
                Scaffold(bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                            )
                        }
                    }
                }) { pad ->
                    Surface(Modifier.padding(pad)) {
                        when (tab) {
                            Tab.Dashboard -> DashboardScreen(vm)
                            Tab.Nodes -> NodesScreen(vm)
                            Tab.Timer -> TimerScreen(vm)
                            Tab.Logs -> LogsScreen(vm)
                            Tab.Help -> HelpScreen()
                        }
                    }
                }
            }
        }
    }
}
