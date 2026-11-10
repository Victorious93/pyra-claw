package com.victorious.pyraclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.data.SettingsStore
import com.victorious.pyraclaw.service.ServiceBus
import com.victorious.pyraclaw.service.ServiceState
import com.victorious.pyraclaw.ui.onboarding.TestState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: HubSettings = HubSettings(),
    val service: ServiceState = ServiceState(),
    val live: LiveSnapshot = LiveSnapshot(),
    val test: TestState = TestState.Idle,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val repo: HubRepository,
    live: StateFlow<LiveSnapshot>,
    service: StateFlow<ServiceState> = ServiceBus.state,
) : ViewModel() {
    private val test = MutableStateFlow<TestState>(TestState.Idle)

    val ui: StateFlow<SettingsUi> = combine(store.settings, service, live, test) { s, svc, l, t ->
        SettingsUi(s, svc, l, t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi(store.current()))

    fun testConnection() {
        val s = store.current()
        test.value = TestState.Testing
        viewModelScope.launch {
            test.value = when (val r = repo.testConnection(s.hubUrl, s.token)) {
                is HubResult.Ok -> TestState.Success(s.hubUrl, r.value)
                is HubResult.Err -> TestState.Failure(r.error)
            }
        }
    }

    fun setBackgroundLink(on: Boolean) = store.setBackgroundLink(on)
    fun forget() { store.forget(); test.value = TestState.Idle }
}
