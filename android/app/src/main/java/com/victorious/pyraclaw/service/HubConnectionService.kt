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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.victorious.pyraclaw.MainActivity
import com.victorious.pyraclaw.net.HubApi
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.TimerDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Observable state shared by the service and the Timer tab. */
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
 * - Holds a WebSocket to the hub's /ws (timer + node state) and reconnects with backoff.
 * - Accepts commands via [ACTION_EXECUTE] intents (from the app, or from droidcommand-AI
 *   through [com.victorious.pyraclaw.fleet.ExternalNodeReceiver]) and forwards them to
 *   POST /api/command. A PARTIAL_WAKE_LOCK is held only while a command is in flight.
 * - The hub is the scheduler; this service never fires timers itself.
 */
class HubConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var stopped = false
    private lateinit var wakeLock: PowerManager.WakeLock

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
        connectLoop()
    }

    private var fgsType = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (fgsType.isNotEmpty()) goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // Every startForegroundService() must be answered with startForeground().
            ACTION_EXECUTE -> intent.getStringExtra(EXTRA_ENVELOPE)?.let { execute(it) }
        }
        return START_STICKY
    }

    private fun goForeground(): String {
        val n = notification("Connecting to hub…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                return "connectedDevice"
            } catch (e: SecurityException) {
                // Fall back to dataSync (note: Android 15 caps dataSync at 6 h per 24 h).
                ServiceCompat.startForeground(
                    this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                return "dataSync"
            }
        }
        startForeground(NOTIF_ID, n)
        return "legacy"
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pyra Claw hub link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun setNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }

    private fun connectLoop() = scope.launch {
        var backoff = 2_000L
        while (!stopped) {
            val (url, token) = Prefs.read(this@HubConnectionService)
            if (token.isBlank()) {
                ServiceBus.log("no auth token saved; open the app and press Start service")
                delay(30_000)
                continue
            }
            val wsUrl = url.trimEnd('/').replaceFirst("http", "ws") +
                "/ws?token=" + URLEncoder.encode(token, "UTF-8")
            val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
            socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff = 2_000L
                    ServiceBus.update { it.copy(connected = true) }
                    ServiceBus.log("hub connected")
                    setNotif("Connected to $url")
                }
                override fun onMessage(webSocket: WebSocket, text: String) = onStatus(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ServiceBus.log("hub link lost: ${t.message ?: response?.code}")
                    closed.complete(Unit)
                }
            })
            closed.await()
            ServiceBus.update { it.copy(connected = false) }
            if (stopped) break
            setNotif("Reconnecting…")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private var lastEventTs = 0.0

    private fun onStatus(text: String) {
        try {
            val obj = gson.fromJson(text, com.google.gson.JsonObject::class.java)
            val t = obj.getAsJsonObject("timers") ?: return
            val timers: List<TimerDto> = gson.fromJson<List<TimerDto>>(
                t.getAsJsonArray("timers"), object : TypeToken<List<TimerDto>>() {}.type,
            ) ?: emptyList()
            ServiceBus.update { it.copy(timers = timers) }
            t.getAsJsonArray("events")?.forEach { e ->
                val ev = e.asJsonObject
                fun str(k: String) = ev.get(k)?.takeIf { !it.isJsonNull }?.toString()?.trim('"')
                val ts = ev.get("ts")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                if (ts > lastEventTs) {
                    lastEventTs = ts
                    val step = str("step")?.let { " step $it" } ?: ""
                    val res = (str("result") ?: str("error"))?.let { " → $it" } ?: ""
                    ServiceBus.log("timer ${str("name")}: ${str("event")}$step$res")
                }
            }
        } catch (e: Exception) {
            ServiceBus.log("bad status frame: ${e.message}")
        }
    }

    private fun execute(envelopeJson: String) {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val m: Map<String, Any?> = try { gson.fromJson(envelopeJson, type) } catch (e: Exception) {
            ServiceBus.log("rejected malformed envelope"); return
        }
        val command = m["command"] as? String ?: run { ServiceBus.log("envelope without command"); return }
        @Suppress("UNCHECKED_CAST")
        val cmd = RemoteCommand(
            command = command,
            target = m["target"] as? String,
            args = (m["args"] as? Map<String, Any?>) ?: emptyMap(),
            approval_token = m["approval_token"] as? String,
            source = (m["source"] as? String) ?: "pyra-claw-android",
        )
        scope.launch {
            wakeLock.acquire(60_000L) // released in finally; timeout is a safety net
            ServiceBus.update { it.copy(working = true) }
            try {
                val (url, token) = Prefs.read(this@HubConnectionService)
                val r = HubApi.create(url, token).command(cmd)
                ServiceBus.log("${cmd.command} ${cmd.target ?: ""} → ok=${r.ok}")
            } catch (e: Exception) {
                ServiceBus.log("${cmd.command} failed: ${e.message}")
            } finally {
                ServiceBus.update { it.copy(working = false) }
                try { if (wakeLock.isHeld) wakeLock.release() } catch (_: RuntimeException) {}
            }
        }
    }

    override fun onDestroy() {
        stopped = true
        socket?.close(1000, "service stopped")
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

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Hub link", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps the Pyra Claw hub connection alive for timers and remote commands"
                    },
                )
            }
        }

        fun start(ctx: Context, url: String, token: String) {
            Prefs.write(ctx, url, token)
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

/** Hub URL + token persisted so the service can reconnect after the activity is gone. */
object Prefs {
    private const val FILE = "pyra_hub"
    fun write(ctx: Context, url: String, token: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("url", url).putString("token", token).apply()
    }
    fun read(ctx: Context): Pair<String, String> {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return (p.getString("url", null) ?: com.victorious.pyraclaw.BuildConfig.DEFAULT_HUB_URL) to
            (p.getString("token", null) ?: "")
    }
}
