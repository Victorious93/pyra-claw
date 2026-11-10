package com.victorious.pyraclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.victorious.pyraclaw.MainActivity
import com.victorious.pyraclaw.R
import com.victorious.pyraclaw.container
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.net.HubJson
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.TimerDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Observable state shared by the service and the UI (Settings, Activity > This phone). */
data class ServiceState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val working: Boolean = false,
    val fgsType: String = "",
    val timers: List<TimerDto> = emptyList(),
    val log: List<String> = emptyList(),
)

object ServiceBus {
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(f: (ServiceState) -> ServiceState) = _state.update(f)
    fun log(line: String) = _state.update {
        it.copy(log = (listOf("${fmt.format(Date())}  $line") + it.log).take(200))
    }
}

/**
 * Foreground service that keeps the hub link alive with the screen off.
 *
 * - Collects the app-wide [com.victorious.pyraclaw.data.LiveHub] stream (one `/ws` connection,
 *   auto-reconnect), which keeps it open while the activity is gone.
 * - Accepts commands via [ACTION_EXECUTE] intents (from droidcommand-AI through
 *   [com.victorious.pyraclaw.fleet.ExternalNodeReceiver]) and forwards them to POST /api/command.
 *   A PARTIAL_WAKE_LOCK is held only while a command is in flight.
 * - The hub is the scheduler; this service never fires timers itself.
 */
class HubConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var fgsType = ""
    private var lastLink: LinkState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PyraClaw:command").apply {
            setReferenceCounted(true)
        }
        ensureChannel(this)
        fgsType = goForeground()
        ServiceBus.update { it.copy(running = true, fgsType = fgsType) }
        ServiceBus.log("service started ($fgsType)")
        scope.launch {
            container.live.state.collect { snap ->
                val connected = snap.link is LinkState.Connected
                ServiceBus.update { it.copy(connected = connected, timers = snap.timers?.timers ?: it.timers) }
                if (snap.link::class != lastLink?.let { it::class }) {
                    lastLink = snap.link
                    val text = when (val l = snap.link) {
                        is LinkState.Connected -> "Connected to ${container.settings.current().hubUrl}"
                        is LinkState.Connecting -> "Connecting to hub…"
                        is LinkState.Retrying -> "Hub offline, retrying"
                        LinkState.Unauthorized -> "Token rejected. Open the app to re-pair."
                        LinkState.NotPaired -> "No hub paired"
                    }
                    ServiceBus.log(text.lowercase())
                    setNotif(text)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground().
        if (fgsType.isNotEmpty()) goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                container.settings.setBackgroundLink(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXECUTE -> intent.getStringExtra(EXTRA_ENVELOPE)?.let { execute(it) }
        }
        return START_STICKY
    }

    private fun goForeground(): String {
        val n = notification("Connecting to hub…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                return "connectedDevice"
            } catch (e: SecurityException) {
                // Fall back to dataSync (note: Android 15 caps dataSync at 6 h per 24 h).
                ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                return "dataSync"
            }
        }
        startForeground(NOTIF_ID, n)
        return "legacy"
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(0xFF00E5FF.toInt())
            .setContentTitle("Pyra Claw hub link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun setNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }

    private fun execute(envelopeJson: String) {
        val cmd = parseEnvelope(envelopeJson) ?: run { ServiceBus.log("rejected malformed envelope"); return }
        scope.launch {
            wakeLock.acquire(60_000L) // released in finally; timeout is a safety net
            ServiceBus.update { it.copy(working = true) }
            try {
                when (val r = container.repository.command(cmd)) {
                    is HubResult.Ok -> ServiceBus.log("${cmd.command} ${cmd.target ?: ""} → ok=${r.value.ok}")
                    is HubResult.Err -> ServiceBus.log("${cmd.command} failed: ${r.error.message}")
                }
            } finally {
                ServiceBus.update { it.copy(working = false) }
                try { if (wakeLock.isHeld) wakeLock.release() } catch (_: RuntimeException) {}
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { while (wakeLock.isHeld) wakeLock.release() } catch (_: RuntimeException) {}
        ServiceBus.update { it.copy(running = false, connected = false, working = false) }
        ServiceBus.log("service stopped")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pyra_hub_link"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.victorious.pyraclaw.action.STOP_SERVICE"
        const val ACTION_EXECUTE = "com.victorious.pyraclaw.action.EXECUTE"
        const val EXTRA_ENVELOPE = "envelope"

        /** remote.command or macro.step JSON → command; null if it isn't one. */
        fun parseEnvelope(json: String): RemoteCommand? = runCatching {
            var o: JsonObject = HubJson.parseToJsonElement(json).jsonObject
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "macro.step") o = o["command"]!!.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            RemoteCommand(
                command = s("command") ?: return null,
                target = s("target"),
                args = (o["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                approval_token = s("approval_token"),
                source = s("source") ?: "droidcommand-ai",
            )
        }.getOrNull()

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = ctx.getString(R.string.notif_channel_desc)
                    },
                )
            }
        }

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, HubConnectionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HubConnectionService::class.java))
        }

        /** Forward an envelope; requires the service to be running (or the caller to be foreground). */
        fun execute(ctx: Context, envelopeJson: String) {
            val i = Intent(ctx, HubConnectionService::class.java)
                .setAction(ACTION_EXECUTE).putExtra(EXTRA_ENVELOPE, envelopeJson)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
