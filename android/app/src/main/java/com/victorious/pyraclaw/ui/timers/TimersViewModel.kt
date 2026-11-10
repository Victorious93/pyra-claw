package com.victorious.pyraclaw.ui.timers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.net.ScheduleDto
import com.victorious.pyraclaw.net.StepDto
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.net.TimerIn
import com.victorious.pyraclaw.ui.Commands
import com.victorious.pyraclaw.ui.components.Load
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** One editable step; [key] keeps Compose rows stable while dragging. */
data class StepDraft(
    val command: String,
    val target: String? = null,
    val delayS: Int = 0,
    val args: JsonObject = JsonObject(emptyMap()),
    val key: String = UUID.randomUUID().toString(),
) {
    fun toDto() = StepDto(command, if (command in Commands.NEEDS_TARGET) target else null, args, delayS.toDouble())
}

/** Everything the editor edits. Pure Kotlin so it can be unit-tested. */
data class TimerDraft(
    val id: String? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: String = "daily",
    val time: String = "07:00",
    val date: String = "",
    val days: Set<Int> = setOf(0, 1, 2, 3, 4),
    val steps: List<StepDraft> = emptyList(),
) {
    val gated: Boolean get() = steps.any { it.command in Commands.GATED }

    fun errors(): List<String> = buildList {
        if (name.isBlank()) add("Give the timer a name")
        if (kind in setOf("daily", "weekly", "once") && !Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) add("Pick a time")
        if (kind == "once" && !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) add("Pick a date")
        if (kind == "weekly" && days.isEmpty()) add("Pick at least one day")
        if (steps.isEmpty()) add("Add at least one step")
        steps.forEachIndexed { i, s ->
            if (s.command in Commands.NEEDS_TARGET && s.target.isNullOrBlank()) add("Step ${i + 1}: choose a device")
            if (s.delayS !in 0..21_600) add("Step ${i + 1}: wait must be 0 to 6 h")
        }
    }

    fun toTimerIn(approvalToken: String? = null) = TimerIn(
        name = name.trim(),
        enabled = enabled,
        schedule = when (kind) {
            "once" -> ScheduleDto("once", at = "${date}T$time")
            "daily" -> ScheduleDto("daily", time = time)
            "weekly" -> ScheduleDto("weekly", time = time, days = days.sorted())
            else -> ScheduleDto("manual")
        },
        steps = steps.map { it.toDto() },
        approval_token = approvalToken,
    )

    companion object {
        fun new(devices: List<DeviceDto>) = TimerDraft(
            steps = listOf(StepDraft("charge.off", devices.firstOrNull()?.id)),
        )

        fun from(t: TimerDto) = TimerDraft(
            id = t.id,
            name = t.name,
            enabled = t.enabled,
            kind = t.schedule.kind,
            time = t.schedule.time ?: t.schedule.at?.substringAfter('T', "07:00")?.take(5) ?: "07:00",
            date = t.schedule.at?.substringBefore('T') ?: "",
            days = t.schedule.days.toSet().ifEmpty { setOf(0, 1, 2, 3, 4) },
            steps = t.steps.map { StepDraft(it.command, it.target, it.delay_s.toInt(), it.args) },
        )

        /** Hub rule: a gated timer keeps its pre-approval only if its steps are unchanged. */
        fun needsArm(draft: TimerDraft, previous: TimerDto?): Boolean {
            if (!draft.gated) return false
            val steps = draft.steps.map { it.toDto() }
            return !(previous != null && previous.preapproved && previous.steps == steps)
        }
    }
}

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Failed(val error: HubError) : SaveState
}

data class TimersUi(
    val timers: Load<List<TimerDto>> = Load.Loading,
    val events: List<JsonObject> = emptyList(),
    val devices: List<DeviceDto> = emptyList(),
    val refreshing: Boolean = false,
    val editor: TimerDraft? = null,
    val save: SaveState = SaveState.Idle,
    val live: Boolean = false,
)

class TimersViewModel(
    private val repo: HubRepository,
    live: StateFlow<LiveSnapshot>,
) : ViewModel() {
    private val local = MutableStateFlow(TimersUi())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** REST data, overridden by the live `/ws` timers snapshot whenever one is flowing. */
    val ui: StateFlow<TimersUi> = combine(local, live) { l, snap ->
        val liveTimers = snap.timers
        if (liveTimers != null && snap.link is com.victorious.pyraclaw.data.LinkState.Connected) {
            l.copy(timers = Load.Ready(liveTimers.timers), events = liveTimers.events, live = true)
        } else l
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimersUi())

    init { refresh() }

    fun refresh() {
        local.update { it.copy(refreshing = it.timers is Load.Ready) }
        viewModelScope.launch {
            val t = repo.timers()
            val d = repo.devices()
            local.update {
                it.copy(
                    refreshing = false,
                    timers = when (t) {
                        is HubResult.Ok -> Load.Ready(t.value.timers)
                        is HubResult.Err -> if (it.timers is Load.Ready) it.timers.also { _ -> _messages.tryEmit(t.error.message) } else Load.Failed(t.error)
                    },
                    events = (t as? HubResult.Ok)?.value?.events ?: it.events,
                    devices = (d as? HubResult.Ok)?.value ?: it.devices,
                )
            }
        }
    }

    fun newTimer() = local.update { it.copy(editor = TimerDraft.new(it.devices), save = SaveState.Idle) }
    fun edit(t: TimerDto) = local.update { it.copy(editor = TimerDraft.from(t), save = SaveState.Idle) }
    fun updateDraft(f: (TimerDraft) -> TimerDraft) = local.update { s -> s.copy(editor = s.editor?.let(f), save = SaveState.Idle) }
    fun closeEditor() = local.update { it.copy(editor = null, save = SaveState.Idle) }

    fun moveStep(from: Int, to: Int) = updateDraft { d ->
        if (from !in d.steps.indices || to !in d.steps.indices) d
        else d.copy(steps = d.steps.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** True when saving the open draft needs the hold-to-confirm "arm" step first. */
    fun draftNeedsArm(): Boolean {
        val d = local.value.editor ?: return false
        val prev = (ui.value.timers as? Load.Ready)?.data?.firstOrNull { it.id == d.id }
        return TimerDraft.needsArm(d, prev)
    }

    /**
     * Saves the draft. For gated timers the user has already held-to-confirm; this asks the hub for
     * a `timer.arm` token and sends it with the timer (hub audits it as timer_preapproved).
     */
    fun save() {
        val d = local.value.editor ?: return
        if (d.errors().isNotEmpty() || local.value.save == SaveState.Saving) return
        val arm = draftNeedsArm()
        local.update { it.copy(save = SaveState.Saving) }
        viewModelScope.launch {
            var token: String? = null
            if (arm) {
                when (val a = repo.approve("timer.arm")) {
                    is HubResult.Err -> { local.update { it.copy(save = SaveState.Failed(a.error)) }; return@launch }
                    is HubResult.Ok -> token = a.value.token
                }
            }
            when (val r = repo.saveTimer(d.id, d.toTimerIn(token))) {
                is HubResult.Err -> local.update { it.copy(save = SaveState.Failed(r.error)) }
                is HubResult.Ok -> {
                    local.update { it.copy(editor = null, save = SaveState.Idle) }
                    _messages.tryEmit(if (arm) "Saved and armed \"${r.value.name}\"" else "Saved \"${r.value.name}\"")
                    refresh()
                }
            }
        }
    }

    fun setEnabled(t: TimerDto, on: Boolean) = act(if (on) "Enabled \"${t.name}\"" else "Paused \"${t.name}\"") {
        repo.setTimerEnabled(t.id, on)
    }
    fun run(t: TimerDto) = act("Started \"${t.name}\"") { repo.runTimer(t.id) }
    fun delete(t: TimerDto) = act("Deleted \"${t.name}\"") { repo.deleteTimer(t.id) }

    private fun act(ok: String, block: suspend () -> HubResult<*>) {
        viewModelScope.launch {
            when (val r = block()) {
                is HubResult.Ok -> { _messages.tryEmit(ok); refresh() }
                is HubResult.Err -> _messages.tryEmit(r.error.message)
            }
        }
    }
}
