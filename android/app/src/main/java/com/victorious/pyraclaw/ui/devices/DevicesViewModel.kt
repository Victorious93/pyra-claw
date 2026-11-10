package com.victorious.pyraclaw.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.ui.Commands
import com.victorious.pyraclaw.ui.components.Load
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceResult(val text: String, val ok: Boolean, val atMs: Long)

data class DevicesUi(
    val devices: Load<List<DeviceDto>> = Load.Loading,
    val refreshing: Boolean = false,
    /** "deviceId|command" currently in flight. */
    val busy: Set<String> = emptySet(),
    val results: Map<String, DeviceResult> = emptyMap(),
)

class DevicesViewModel(
    private val repo: HubRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow(DevicesUi())
    val ui: StateFlow<DevicesUi> = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(refreshing = it.devices is Load.Ready) }
        viewModelScope.launch {
            val r = repo.devices()
            _ui.update {
                when (r) {
                    is HubResult.Ok -> it.copy(devices = Load.Ready(r.value), refreshing = false)
                    // Keep showing the last good list if we have one; report the error once.
                    is HubResult.Err -> if (it.devices is Load.Ready) {
                        _messages.tryEmit(r.error.message); it.copy(refreshing = false)
                    } else it.copy(devices = Load.Failed(r.error), refreshing = false)
                }
            }
        }
    }

    /** Runs a non-gated device command (charge on/off, power on). Gated ones go through approval. */
    fun run(deviceId: String, command: String) {
        require(command !in Commands.GATED) { "$command needs the approval flow" }
        val key = "$deviceId|$command"
        if (key in _ui.value.busy) return
        _ui.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            val r = repo.command(RemoteCommand(command = command, target = deviceId))
            val res = when (r) {
                is HubResult.Ok -> DeviceResult("${Commands.name(command)}: ok", true, now())
                is HubResult.Err -> DeviceResult("${Commands.name(command)}: ${r.error.message}", false, now())
            }
            _ui.update { it.copy(busy = it.busy - key, results = it.results + (deviceId to res)) }
            _messages.tryEmit("$deviceId · ${res.text}")
        }
    }
}
