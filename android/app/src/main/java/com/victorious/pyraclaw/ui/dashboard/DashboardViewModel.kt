package com.victorious.pyraclaw.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.data.field
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
    /** The hub sent the magic packet; it can't confirm the PC actually booted. */
    data class Sent(val wolBytes: Int, val relayed: Boolean, val atMs: Long) : WakeState
    data class Failed(val error: HubError) : WakeState
}

data class DashboardUi(
    val link: LinkState = LinkState.NotPaired,
    val status: StatusDto? = null,
    val lastUpdateMs: Long = 0,
    val manualTimers: List<TimerDto> = emptyList(),
    val runningTimers: List<TimerDto> = emptyList(),
    val wake: WakeState = WakeState.Idle,
    val refreshing: Boolean = false,
    val restError: HubError? = null,
)

class DashboardViewModel(
    private val repo: HubRepository,
    live: StateFlow<LiveSnapshot>,
    private val reconnect: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
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
    )

    val ui: StateFlow<DashboardUi> = combine(live, local) { l, loc ->
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
        }
    }

    fun wakePc() {
        if (local.value.wake == WakeState.Sending) return
        local.update { it.copy(wake = WakeState.Sending) }
        viewModelScope.launch {
            // target null = the hub's configured PC (same call VictorSuite uses).
            val r = repo.command(RemoteCommand(command = "pc.wake"))
            val next = when (r) {
                is HubResult.Ok -> WakeState.Sent(
                    wolBytes = r.value.field("wol_bytes")?.toIntOrNull() ?: 0,
                    relayed = r.value.field("relayed") == "true",
                    atMs = now(),
                )
                is HubResult.Err -> WakeState.Failed(r.error)
            }
            local.update { it.copy(wake = next) }
            _messages.tryEmit(
                when (next) {
                    is WakeState.Sent -> "Wake-on-LAN sent" + if (next.relayed) " + relayed to ESP32" else ""
                    is WakeState.Failed -> next.error.message
                    else -> ""
                },
            )
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
