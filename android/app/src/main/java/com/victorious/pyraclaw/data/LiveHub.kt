package com.victorious.pyraclaw.data

import com.victorious.pyraclaw.net.HubJson
import com.victorious.pyraclaw.net.StatusDto
import com.victorious.pyraclaw.net.TimersDto
import com.victorious.pyraclaw.net.WsFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder

sealed interface LinkState {
    data object NotPaired : LinkState
    data class Connecting(val attempt: Int) : LinkState
    data class Connected(val sinceMs: Long) : LinkState
    data class Retrying(val reason: String, val retryAtMs: Long) : LinkState
    data object Unauthorized : LinkState
}

data class LiveSnapshot(
    val link: LinkState = LinkState.NotPaired,
    val status: StatusDto? = null,
    val timers: TimersDto? = null,
    val lastFrameMs: Long = 0L,
)

/**
 * The hub's `/ws` stream (a status + timers frame every 2 s), shared by every screen and the
 * background service through one connection. Reconnects with exponential backoff (1 s → 30 s);
 * [reconnectNow] skips the wait. Stops 5 s after the last collector leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveHub(
    settings: SettingsStore,
    private val client: OkHttpClient,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val state: StateFlow<LiveSnapshot> = settings.settings
        .map { if (it.isConfigured) it.hubUrl to it.token else null }
        .distinctUntilChanged()
        .flatMapLatest { cfg -> if (cfg == null) flowOf(LiveSnapshot()) else connect(cfg.first, cfg.second) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), LiveSnapshot())

    fun reconnectNow() { kick.tryEmit(Unit) }

    private fun connect(url: String, token: String): Flow<LiveSnapshot> = channelFlow {
        var snap = LiveSnapshot(LinkState.Connecting(1))
        var attempt = 0
        var backoff = 1_000L
        while (true) {
            attempt++
            snap = snap.copy(link = LinkState.Connecting(attempt)); send(snap)
            val closed = CompletableDeferred<Pair<String, Boolean>>() // reason, unauthorized
            val req = Request.Builder()
                .url(HubUrl.webSocket(url) + "?token=" + URLEncoder.encode(token, "UTF-8"))
                .build()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff = 1_000L
                    attempt = 0
                    snap = snap.copy(link = LinkState.Connected(now()))
                    trySend(snap)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { HubJson.decodeFromString(WsFrame.serializer(), text) }.getOrNull() ?: return
                    snap = snap.copy(status = frame.status(), timers = frame.timers ?: snap.timers, lastFrameMs = now())
                    trySend(snap)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed.complete((reason.ifBlank { "closed ($code)" }) to (code == 4401))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete("closed ($code)" to (code == 4401))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    // Starlette rejects a bad token before accept: the handshake gets HTTP 403.
                    closed.complete((t.message ?: "HTTP $code") to (code == 401 || code == 403))
                }
            })
            val (reason, unauthorized) = try { closed.await() } finally { ws.cancel() }
            val wait = if (unauthorized) 30_000L else backoff
            snap = snap.copy(link = if (unauthorized) LinkState.Unauthorized else LinkState.Retrying(reason, now() + wait))
            send(snap)
            withTimeoutOrNull(wait) { kick.first() }
            if (!unauthorized) backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }
}
