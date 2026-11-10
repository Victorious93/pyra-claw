package com.victorious.pyraclaw.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.pyraclaw.data.FoundHub
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubUrl
import com.victorious.pyraclaw.data.SettingsStore
import com.victorious.pyraclaw.net.StatusDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val url: String, val status: StatusDto) : TestState
    data class Failure(val error: HubError) : TestState
}

data class OnboardingUi(
    val url: String = "",
    val token: String = "",
    val urlError: String? = null,
    val tokenError: String? = null,
    val test: TestState = TestState.Idle,
    val scanning: Boolean = false,
    val found: List<FoundHub> = emptyList(),
    val scannedOnce: Boolean = false,
    val saved: Boolean = false,
)

class OnboardingViewModel(
    private val repo: HubRepository,
    private val settings: SettingsStore,
    private val scan: () -> Flow<FoundHub>,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        settings.current().let { OnboardingUi(url = it.hubUrl, token = it.token) },
    )
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()
    private var scanJob: Job? = null

    fun setUrl(v: String) = _ui.update { it.copy(url = v, urlError = null, test = TestState.Idle) }
    fun setToken(v: String) = _ui.update { it.copy(token = v, tokenError = null, test = TestState.Idle) }

    fun pick(hub: FoundHub) = _ui.update { it.copy(url = hub.url, urlError = null, test = TestState.Idle) }

    fun startScan() {
        scanJob?.cancel()
        _ui.update { it.copy(scanning = true, found = emptyList()) }
        scanJob = viewModelScope.launch {
            scan()
                .catch { }
                .onCompletion { _ui.update { it.copy(scanning = false, scannedOnce = true) } }
                .collect { hub ->
                    _ui.update { s -> if (s.found.any { it.url == hub.url }) s else s.copy(found = s.found + hub) }
                }
        }
    }

    private fun validate(): Pair<String, String>? {
        val s = _ui.value
        val url = HubUrl.normalize(s.url)
        val token = s.token.trim()
        _ui.update {
            it.copy(
                urlError = if (url == null) "Enter an address like 192.168.1.20 or http://pyra.local:8787" else null,
                tokenError = if (token.isEmpty()) "The token is auth_token from /etc/pyra-claw/config.toml" else null,
            )
        }
        return if (url != null && token.isNotEmpty()) url to token else null
    }

    fun test() {
        val (url, token) = validate() ?: return
        _ui.update { it.copy(url = url, test = TestState.Testing) }
        viewModelScope.launch {
            val r = repo.testConnection(url, token)
            _ui.update {
                it.copy(
                    test = when (r) {
                        is HubResult.Ok -> TestState.Success(url, r.value)
                        is HubResult.Err -> TestState.Failure(r.error)
                    },
                )
            }
        }
    }

    /** Saves the pairing. Allowed without a passing test (e.g. setting up before the Pi is on). */
    fun save() {
        val (url, token) = validate() ?: return
        settings.savePairing(url, token)
        _ui.update { it.copy(saved = true) }
    }
}
