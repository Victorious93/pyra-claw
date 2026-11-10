package com.victorious.pyraclaw.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.DirectEsp32
import com.victorious.pyraclaw.data.DirectHealthDto
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.data.SettingsStore
import com.victorious.pyraclaw.data.field
import com.victorious.pyraclaw.data.getOrNull
import com.victorious.pyraclaw.data.isHubDown
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.StatusDto
import com.victorious.pyraclaw.net.TimerDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface WakeState {
    data object Idle : WakeState
    data object Sending : WakeState
    /** The magic packet went out; nobody can confirm the PC actually booted. */
    data class Sent(val wolBytes: Int, val relayed: Boolean, val atMs: Long, val via: WakeVia = WakeVia.Hub) : WakeState
    data class Failed(val error: HubError) : WakeState
}

enum class WakeVia { Hub, DirectEsp32 }

/** Where commands go right now. Shown on the dashboard so it's never ambiguous. */
enum class ControlMode {
    /** Hub reachable (or not known to be down): everything goes through the Pi. */
    Hub,
    /** Hub unreachable and a Direct ESP32 is set up: Wake PC goes straight to the node. */
    DirectEsp32,
    /** Hub unreachable and no Direct ESP32 configured. */
    Offline,
}

data class DirectInfo(
    val url: String,
    /** null = not probed yet. */
    val health: DirectHealthDto? = null,
    val healthError: HubError? = null,
)

data class DashboardUi(
    val link: LinkState = LinkState.NotPaired,
    val status: StatusDto? = null,
    val lastUpdateMs: Long = 0,
    val manualTimers: List<TimerDto> = emptyList(),
    val runningTimers: List<TimerDto> = emptyList(),
    val wake: WakeState = WakeState.Idle,
    val refreshing: Boolean = false,
    val restError: HubError? = null,
    val mode: ControlMode = ControlMode.Hub,
    /** Set when a Direct ESP32 is configured, whatever the mode. */
    val direct: DirectInfo? = null,
)

class DashboardViewModel(
    private val repo: HubRepository,
    live: StateFlow<LiveSnapshot>,
    private val reconnect: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val settings: SettingsStore? = null,
    private val direct: DirectEsp32? = null,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private data class Local(
        val restStatus: StatusDto? = null,
        val restTimers: List<TimerDto>? = null,
        val restAtMs: Long = 0,
        val wake: WakeState = WakeState.Idle,
        val refreshing: Boolean = false,
        val restError: HubError? = null,
        val directHealth: DirectHealthDto? = null,
        val directError: HubError? = null,
    )

    private val directSettings = settings?.settings ?: MutableStateFlow(HubSettings())

    private fun hubDown(l: LiveSnapshot, loc: Local): Boolean =
        l.link !is LinkState.Connected && loc.restError?.isHubDown() == true

    val ui: StateFlow<DashboardUi> = combine(live, local, directSettings) { l, loc, cfg ->
        val liveFresh = l.link is LinkState.Connected && l.status != null
        val status = if (liveFresh) l.status else (loc.restStatus ?: l.status)
        val timers = l.timers?.timers ?: loc.restTimers ?: emptyList()
        DashboardUi(
            link = l.link,
            status = status,
            lastUpdateMs = if (liveFresh) l.lastFrameMs else maxOf(loc.restAtMs, l.lastFrameMs),
            manualTimers = timers.filter { it.schedule.kind == "manual" && it.enabled },
            runningTimers = timers.filter { it.running },
            wake = loc.wake,
            refreshing = loc.refreshing,
            restError = if (liveFresh) null else loc.restError,
            mode = when {
                liveFresh || !hubDown(l, loc) -> ControlMode.Hub
                cfg.hasDirect && direct != null -> ControlMode.DirectEsp32
                else -> ControlMode.Offline
            },
            direct = if (cfg.hasDirect && direct != null) DirectInfo(cfg.directUrl, loc.directHealth, loc.directError) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    init { refresh() }

    fun refresh() {
        local.update { it.copy(refreshing = true) }
        reconnect()
        viewModelScope.launch {
            val s = repo.status()
            val t = repo.timers()
            local.update {
                it.copy(
                    refreshing = false,
                    restStatus = (s as? HubResult.Ok)?.value ?: it.restStatus,
                    restTimers = (t as? HubResult.Ok)?.value?.timers ?: it.restTimers,
                    restAtMs = if (s is HubResult.Ok) now() else it.restAtMs,
                    restError = (s as? HubResult.Err)?.error,
                )
            }
            // Hub down: probe the ESP32 so the banner can say whether direct mode will work.
            val cfg = settings?.current()
            if (s is HubResult.Err && s.error.isHubDown() && cfg?.hasDirect == true && direct != null) {
                val h = direct.health(cfg.directUrl)
                local.update { it.copy(directHealth = h.getOrNull(), directError = (h as? HubResult.Err)?.error) }
            }
        }
    }

    fun wakePc() {
        if (local.value.wake == WakeState.Sending) return
        local.update { it.copy(wake = WakeState.Sending) }
        val alreadyDirect = ui.value.mode == ControlMode.DirectEsp32
        viewModelScope.launch {
            // Hub known to be down: go straight to the ESP32 instead of waiting for a timeout.
            val next = if (alreadyDirect) wakeDirect(null) else when (val r = repo.command(RemoteCommand(command = "pc.wake"))) {
                // target null = the hub's configured PC (same call VictorSuite uses).
                is HubResult.Ok -> WakeState.Sent(
                    wolBytes = r.value.field("wol_bytes")?.toIntOrNull() ?: 0,
                    relayed = r.value.field("relayed") == "true",
                    atMs = now(),
                )
                is HubResult.Err ->
                    if (r.error.isHubDown() && settings?.current()?.hasDirect == true && direct != null) {
                        local.update { it.copy(restError = r.error) }
                        wakeDirect(r.error)
                    } else {
                        WakeState.Failed(r.error)
                    }
            }
            local.update { it.copy(wake = next) }
            _messages.tryEmit(
                when (next) {
                    is WakeState.Sent ->
                        if (next.via == WakeVia.DirectEsp32) "Hub unreachable · ESP32 sent Wake-on-LAN directly"
                        else "Wake-on-LAN sent" + if (next.relayed) " + relayed to ESP32" else ""
                    is WakeState.Failed -> next.error.message
                    else -> ""
                },
            )
        }
    }

    /** POST /wake on the ESP32. [hubError] is the hub failure that sent us here, if any. */
    private suspend fun wakeDirect(hubError: HubError?): WakeState {
        val cfg = settings?.current()
        if (cfg == null || !cfg.hasDirect || direct == null) return WakeState.Failed(hubError ?: HubError.DirectNotConfigured)
        return when (val d = direct.wake(cfg.directUrl, cfg.directToken)) {
            is HubResult.Ok -> {
                local.update { it.copy(directError = null) }
                WakeState.Sent(wolBytes = 102, relayed = false, atMs = now(), via = WakeVia.DirectEsp32)
            }
            is HubResult.Err -> {
                local.update { it.copy(directError = d.error) }
                WakeState.Failed(d.error)
            }
        }
    }

    fun runTimer(t: TimerDto) {
        viewModelScope.launch {
            when (val r = repo.runTimer(t.id)) {
                is HubResult.Ok -> _messages.tryEmit("Started \"${t.name}\"")
                is HubResult.Err -> _messages.tryEmit(r.error.message)
            }
        }
    }
}
