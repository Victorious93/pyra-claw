package com.victorious.pyraclaw

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.victorious.pyraclaw.fleet.DroidCommandNode
import com.victorious.pyraclaw.service.HubConnectionService
import com.victorious.pyraclaw.ui.PyraAppUi
import com.victorious.pyraclaw.ui.theme.Neon
import com.victorious.pyraclaw.ui.theme.PyraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val bar = SystemBarStyle.dark(Color.parseColor("#0B0F1A"))
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        DroidCommandNode.register(this)
        val s = container.settings.current()
        if (s.isConfigured && s.backgroundLink) {
            runCatching { HubConnectionService.start(this) } // activity is foreground, so this is allowed
        }
        setContent {
            PyraTheme {
                Box(Modifier.fillMaxSize().background(Neon.Bg)) { PyraAppUi(container) }
            }
        }
    }
}
