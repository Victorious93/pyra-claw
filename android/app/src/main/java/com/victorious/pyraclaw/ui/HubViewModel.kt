package com.victorious.pyraclaw.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.BuildConfig
import com.victorious.pyraclaw.net.HubApi
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
}
