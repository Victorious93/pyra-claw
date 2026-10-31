package com.victorious.pyraclaw.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.BuildConfig
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.net.HubApi
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.net.TimerIn
import com.victorious.pyraclaw.net.NodeDto
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.StatusDto
import kotlinx.coroutines.launch

class HubViewModel : ViewModel() {
    var hubUrl by mutableStateOf(BuildConfig.DEFAULT_HUB_URL)
    var token by mutableStateOf("")
    var status by mutableStateOf<StatusDto?>(null)
    var nodes by mutableStateOf<List<NodeDto>>(emptyList())
    var logs by mutableStateOf<List<String>>(emptyList())
    var message by mutableStateOf<String?>(null)
    var timers by mutableStateOf<List<TimerDto>>(emptyList())
    var devices by mutableStateOf<List<DeviceDto>>(emptyList())

    private fun api() = HubApi.create(hubUrl, token)

    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { message = "Error: ${e.message}" }
    }

    fun refresh() = guarded {
        status = api().status()
        nodes = status?.nodes ?: emptyList()
        logs = api().audit().events.reversed().map { "${it["event"]}  ${it["command"] ?: ""}" }
    }

    fun wakePc() = guarded {
        val r = api().command(RemoteCommand(command = "pc.wake"))
        message = "Wake sent: ${r.result}"
    }

    /** Called only after the hold-to-confirm dialog completes. */
    fun powerPulse() = guarded {
        val approval = api().approve("pc.power_pulse")
        val r = api().command(
            RemoteCommand(command = "pc.power_pulse", approval_token = approval.approval_token,
                args = mapOf("ms" to 300))
        )
        message = "Power pulse: ${r.result}"
    }

    // ---- v0.3 timers ------------------------------------------------------------
    fun refreshTimers() = guarded {
        timers = api().timers().timers ?: emptyList()
        devices = api().devices().devices ?: emptyList()
    }

    /** Timers with approval-gated steps need a fresh timer.arm token (pre-approval, audited). */
    fun saveTimer(id: String?, t: TimerIn, needsApproval: Boolean) = guarded {
        val body = if (needsApproval) t.copy(approval_token = api().approve("timer.arm").approval_token) else t
        if (id == null) api().createTimer(body) else api().updateTimer(id, body)
        message = "Saved timer ${t.name}"
        refreshTimers()
    }

    fun setTimerEnabled(id: String, on: Boolean) = guarded {
        if (on) api().enableTimer(id) else api().disableTimer(id)
        refreshTimers()
    }

    fun runTimer(id: String) = guarded {
        api().runTimer(id)
        message = "Timer started"
        refreshTimers()
    }

    fun deleteTimer(id: String) = guarded {
        api().deleteTimer(id)
        refreshTimers()
    }
}
