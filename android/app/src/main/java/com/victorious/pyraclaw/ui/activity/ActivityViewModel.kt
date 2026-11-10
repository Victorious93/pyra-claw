package com.victorious.pyraclaw.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.AuditEvent
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.ui.components.Load
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuditFilter(val label: String) {
    All("All"), Commands("Commands"), Approvals("Approvals"), Problems("Problems"), Timers("Timers");

    fun matches(e: AuditEvent): Boolean = when (this) {
        All -> true
        Commands -> e.event in setOf("accepted", "executed")
        Approvals -> e.event in setOf("approval_issued", "timer_preapproved")
        Problems -> e.event in setOf("rejected", "timer_rejected", "failed") || e.event.contains("fail")
        Timers -> e.event.startsWith("timer_") || e.fields.containsKey("timer_id")
    }
}

data class ActivityUi(
    val events: Load<List<AuditEvent>> = Load.Loading,
    val filter: AuditFilter = AuditFilter.All,
    val query: String = "",
    val refreshing: Boolean = false,
) {
    val visible: List<AuditEvent>
        get() = (events as? Load.Ready)?.data.orEmpty().filter { e ->
            filter.matches(e) && (query.isBlank() || listOfNotNull(e.event, e.command, *e.fields.values.toTypedArray())
                .any { it.contains(query.trim(), ignoreCase = true) })
        }

    fun count(f: AuditFilter) = (events as? Load.Ready)?.data.orEmpty().count(f::matches)
}

class ActivityViewModel(private val repo: HubRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ActivityUi())
    val ui: StateFlow<ActivityUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        _ui.update { it.copy(refreshing = it.events is Load.Ready) }
        viewModelScope.launch {
            val r = repo.audit()
            _ui.update {
                it.copy(
                    refreshing = false,
                    events = when (r) {
                        is HubResult.Ok -> Load.Ready(r.value)
                        is HubResult.Err -> Load.Failed(r.error)
                    },
                )
            }
        }
    }

    fun setFilter(f: AuditFilter) = _ui.update { it.copy(filter = f) }
    fun setQuery(q: String) = _ui.update { it.copy(query = q) }
}
