package com.victorious.pyraclaw.ui.approval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.Approval
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.field
import com.victorious.pyraclaw.net.CommandResult
import com.victorious.pyraclaw.net.RemoteCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A destructive command waiting for the user's explicit approval. */
data class ApprovalRequest(
    val command: String,
    val target: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val title: String,
    val body: String,
    val actionLabel: String,
)

sealed interface ApprovalPhase {
    /** Explains the action; the user holds the button to ask the hub for a token. */
    data object Review : ApprovalPhase
    data object Requesting : ApprovalPhase
    /** Token issued; the user has until it expires to press Send. */
    data class Armed(val approval: Approval, val secondsLeft: Int) : ApprovalPhase
    data object Sending : ApprovalPhase
    data class Done(val summary: String) : ApprovalPhase
    data class Failed(val error: HubError) : ApprovalPhase
    data object Expired : ApprovalPhase
}

data class ApprovalUi(val request: ApprovalRequest, val phase: ApprovalPhase = ApprovalPhase.Review)

/**
 * Two-step approval for every gated command (pc.power_pulse, device.power_off):
 * hold-to-confirm → `POST /api/approve/{command}` → 30 s countdown → Send → `POST /api/command`.
 * Nothing destructive happens without both a hold and a tap inside the token window.
 */
class ApprovalViewModel(
    private val repo: HubRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow<ApprovalUi?>(null)
    val ui: StateFlow<ApprovalUi?> = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    private var ticker: Job? = null

    fun open(request: ApprovalRequest) {
        ticker?.cancel()
        _ui.value = ApprovalUi(request)
    }

    fun dismiss() {
        ticker?.cancel()
        _ui.value = null
    }

    /** Called when the hold-to-confirm completes (or "Request again" after expiry/failure). */
    fun requestApproval() {
        val cur = _ui.value ?: return
        if (cur.phase is ApprovalPhase.Requesting || cur.phase is ApprovalPhase.Sending) return
        setPhase(ApprovalPhase.Requesting)
        viewModelScope.launch {
            when (val r = repo.approve(cur.request.command)) {
                is HubResult.Err -> setPhase(ApprovalPhase.Failed(r.error))
                is HubResult.Ok -> startCountdown(r.value)
            }
        }
    }

    private fun startCountdown(a: Approval) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val left = ((a.expiresAtMs - now() + 999) / 1000).toInt()
                if (left <= 0) { setPhase(ApprovalPhase.Expired); break }
                setPhase(ApprovalPhase.Armed(a, left))
                delay(250)
            }
        }
    }

    fun send() {
        val cur = _ui.value ?: return
        val armed = cur.phase as? ApprovalPhase.Armed ?: return
        if (now() >= armed.approval.expiresAtMs) { setPhase(ApprovalPhase.Expired); return }
        ticker?.cancel()
        setPhase(ApprovalPhase.Sending)
        viewModelScope.launch {
            val cmd = RemoteCommand(
                command = cur.request.command,
                target = cur.request.target,
                args = cur.request.args,
                approval_token = armed.approval.token,
            )
            when (val r = repo.command(cmd)) {
                is HubResult.Err -> setPhase(ApprovalPhase.Failed(r.error))
                is HubResult.Ok -> {
                    val s = summarize(cur.request, r.value)
                    setPhase(ApprovalPhase.Done(s))
                    _messages.tryEmit(s)
                }
            }
        }
    }

    private fun setPhase(p: ApprovalPhase) = _ui.update { it?.copy(phase = p) }

    companion object {
        fun summarize(req: ApprovalRequest, r: CommandResult): String = when (req.command) {
            "pc.power_pulse" -> {
                val relayed = r.field("relayed") == "true"
                "Power button pressed for ${r.field("ms") ?: "300"} ms" +
                    if (relayed) " · relayed to ESP32" else " · NOT relayed (wake node offline or MQTT down)"
            }
            "device.power_off" -> "Power off sent to ${req.target}"
            else -> "${req.actionLabel}: done"
        }
    }
}
