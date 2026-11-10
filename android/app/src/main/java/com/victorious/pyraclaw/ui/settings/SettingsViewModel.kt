package com.victorious.pyraclaw.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.DirectEsp32
import com.victorious.pyraclaw.data.DirectHealthDto
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.HubUrl
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

/** State of the Direct ESP32 card's Save/Test. */
sealed interface DirectTest {
    data object Idle : DirectTest
    data object Testing : DirectTest
    data class Ok(val health: DirectHealthDto) : DirectTest
    data class Failed(val error: HubError) : DirectTest
    data class Invalid(val message: String) : DirectTest
}

data class SettingsUi(
    val settings: HubSettings = HubSettings(),
    val service: ServiceState = ServiceState(),
    val live: LiveSnapshot = LiveSnapshot(),
    val test: TestState = TestState.Idle,
    val directTest: DirectTest = DirectTest.Idle,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val repo: HubRepository,
    live: StateFlow<LiveSnapshot>,
    service: StateFlow<ServiceState> = ServiceBus.state,
    private val direct: DirectEsp32? = null,
) : ViewModel() {
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val directTest = MutableStateFlow<DirectTest>(DirectTest.Idle)

    val ui: StateFlow<SettingsUi> = combine(store.settings, service, live, test, directTest) { s, svc, l, t, d ->
        SettingsUi(s, svc, l, t, d)
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

    /** Validates, saves (token Keystore-encrypted), then probes GET /health on the node. */
    fun saveDirect(url: String, token: String) {
        val n = HubUrl.normalizeEsp32(url)
        when {
            n == null -> directTest.value = DirectTest.Invalid("Enter pyra-<node_id>.local or the ESP32's IP address.")
            token.trim().length < MIN_WAKE_TOKEN -> directTest.value =
                DirectTest.Invalid("Paste the node's wake_token (at least $MIN_WAKE_TOKEN characters).")
            else -> {
                store.saveDirect(n, token.trim())
                testDirect()
            }
        }
    }

    fun testDirect() {
        val s = store.current()
        val d = direct ?: return
        if (s.directUrl.isBlank()) { directTest.value = DirectTest.Failed(HubError.DirectNotConfigured); return }
        directTest.value = DirectTest.Testing
        viewModelScope.launch {
            directTest.value = when (val r = d.health(s.directUrl)) {
                is HubResult.Ok -> DirectTest.Ok(r.value)
                is HubResult.Err -> DirectTest.Failed(r.error)
            }
        }
    }

    fun clearDirect() { store.clearDirect(); directTest.value = DirectTest.Idle }

    fun setBackgroundLink(on: Boolean) = store.setBackgroundLink(on)
    fun forget() { store.forget(); test.value = TestState.Idle; directTest.value = DirectTest.Idle }

    companion object {
        /** Matches the firmware's minimum wake_token length. */
        const val MIN_WAKE_TOKEN = 16
    }
}
