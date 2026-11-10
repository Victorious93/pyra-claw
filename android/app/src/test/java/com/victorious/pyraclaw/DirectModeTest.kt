package com.victorious.pyraclaw

import com.victorious.pyraclaw.data.DefaultDirectEsp32
import com.victorious.pyraclaw.data.DirectEsp32
import com.victorious.pyraclaw.data.DirectHealthDto
import com.victorious.pyraclaw.data.DirectWakeDto
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.HubUrl
import com.victorious.pyraclaw.data.InMemorySettingsStore
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.ui.dashboard.ControlMode
import com.victorious.pyraclaw.ui.dashboard.DashboardViewModel
import com.victorious.pyraclaw.ui.dashboard.WakeState
import com.victorious.pyraclaw.ui.dashboard.WakeVia
import com.victorious.pyraclaw.ui.settings.DirectTest
import com.victorious.pyraclaw.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private const val WAKE_TOKEN = "wake-only-token-0123456789"

/** Scriptable ESP32 for view-model tests. */
class FakeDirect : DirectEsp32 {
    val calls = mutableListOf<String>()
    var health: HubResult<DirectHealthDto> = HubResult.Ok(DirectHealthDto(true, "esp32-wake-01", "0.4.0", "0.4", true, false, "192.168.1.42", true))
    var wake: HubResult<DirectWakeDto> = HubResult.Ok(DirectWakeDto(true, "wake_pc"))
    override suspend fun health(baseUrl: String) = health.also { calls += "health $baseUrl" }
    override suspend fun wake(baseUrl: String, token: String) = wake.also { calls += "wake $baseUrl $token" }
}

/** Real OkHttp client against a scripted ESP32 (firmware 0.4 HTTP API). */
class DirectEsp32ClientTest {
    private lateinit var server: MockWebServer
    private val client = DefaultDirectEsp32(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")
    private val base get() = server.url("/").toString()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    @Test fun healthParsesAndSendsNoAuth() = runTest {
        server.enqueue(json("""{"ok":true,"node_id":"esp32-wake-01","fw":"0.4.0","protocol":"0.4","time_synced":false,"mqtt":false,"ip":"192.168.1.42","ntp":"192.168.1.20","usb":[true],"can_wake":true}"""))
        val r = client.health(base) as HubResult.Ok
        assertEquals("esp32-wake-01", r.value.node_id)
        assertEquals(false, r.value.time_synced)
        assertEquals(true, r.value.can_wake)
        val req = server.takeRequest()
        assertEquals("GET", req.method); assertEquals("/health", req.path)
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun healthFromOlderFirmwareWithoutNewFields() = runTest {
        server.enqueue(json("""{"ok":true,"node_id":"n","fw":"0.3.0","protocol":"0.2","time_synced":true,"mqtt":true,"ip":"10.0.0.2"}"""))
        val r = client.health(base) as HubResult.Ok
        assertNull(r.value.can_wake)
    }

    @Test fun wakePostsWithBearer() = runTest {
        server.enqueue(json("""{"ok":true,"sent":"wake_pc"}"""))
        val r = client.wake(base, WAKE_TOKEN)
        assertEquals(HubResult.Ok(DirectWakeDto(true, "wake_pc")), r)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/wake", req.path)
        assertEquals("Bearer $WAKE_TOKEN", req.getHeader("Authorization"))
    }

    @Test fun mapsFirmwareErrors() = runTest {
        server.enqueue(json("""{"ok":false,"err":"unauthorized"}""", 401))
        server.enqueue(json("""{"ok":false,"err":"rate_limited"}""", 429))
        server.enqueue(json("""{"ok":false,"err":"no_hub_token"}""", 503))
        val e401 = (client.wake(base, "bad") as HubResult.Err).error as HubError.Esp32Refused
        assertEquals(401, e401.code); assertEquals("unauthorized", e401.detail)
        assertTrue(e401.hint.contains("wake_token"))
        val e429 = (client.wake(base, WAKE_TOKEN) as HubResult.Err).error as HubError.Esp32Refused
        assertEquals(429, e429.code); assertEquals("rate_limited", e429.detail)
        assertEquals(503, ((client.wake(base, WAKE_TOKEN) as HubResult.Err).error as HubError.Esp32Refused).code)
    }

    @Test fun unreachableNode() = runTest {
        val dead = server.url("/").toString()
        server.shutdown()
        val e = (client.wake(dead, WAKE_TOKEN) as HubResult.Err).error
        assertTrue(e is HubError.Esp32Unreachable)
    }

    @Test fun garbageIsBadResponse() = runTest {
        server.enqueue(json("<html>router login</html>"))
        assertTrue((client.health(base) as HubResult.Err).error is HubError.BadResponse)
    }

    @Test fun esp32UrlNormalization() {
        assertEquals("http://pyra-esp32-wake-01.local/", HubUrl.normalizeEsp32("pyra-esp32-wake-01.local"))
        assertEquals("http://192.168.1.42/", HubUrl.normalizeEsp32(" 192.168.1.42 "))
        assertEquals("http://192.168.1.42:8080/", HubUrl.normalizeEsp32("http://192.168.1.42:8080"))
        assertNull(HubUrl.normalizeEsp32(""))
        assertEquals("http://192.168.1.20:8787/", HubUrl.normalize("192.168.1.20")) // hub default unchanged
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DirectModeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repo = FakeHubRepository()
    private val direct = FakeDirect()
    private val esp = "http://pyra-esp32-wake-01.local/"
    private val withDirect = InMemorySettingsStore(HubSettings("http://192.168.1.20:8787/", "t", paired = true, directUrl = esp, directToken = WAKE_TOKEN))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(store: InMemorySettingsStore = withDirect) =
        DashboardViewModel(repo, MutableStateFlow(LiveSnapshot()), reconnect = {}, now = { 7L }, settings = store, direct = direct)
            .also { v -> backgroundScope.launch { v.ui.collect {} } }

    @Test fun hubUpMeansHubModeAndNoEsp32Traffic() = scope.runTest {
        val vm = vm(); advanceUntilIdle()
        assertEquals(ControlMode.Hub, vm.ui.value.mode)
        vm.wakePc(); advanceUntilIdle()
        assertEquals(WakeVia.Hub, (vm.ui.value.wake as WakeState.Sent).via)
        assertEquals(emptyList<String>(), direct.calls)
    }

    @Test fun hubDownFallsBackToDirectWake() = scope.runTest {
        repo.status = FakeHubRepository.unreachable
        repo.commandResult = FakeHubRepository.unreachable
        val vm = vm(); advanceUntilIdle()
        assertEquals(ControlMode.DirectEsp32, vm.ui.value.mode)
        assertEquals("esp32-wake-01", vm.ui.value.direct!!.health!!.node_id) // probed for the banner
        vm.wakePc(); advanceUntilIdle()
        val sent = vm.ui.value.wake as WakeState.Sent
        assertEquals(WakeVia.DirectEsp32, sent.via)
        assertFalse(sent.relayed)
        assertEquals("wake $esp $WAKE_TOKEN", direct.calls.last())
        // Known-down hub: wake went straight to the ESP32 without another hub attempt.
        assertFalse(repo.calls.contains("command pc.wake"))
    }

    @Test fun hubTimesOutDuringWakeThenDirect() = scope.runTest {
        val vm = vm(); advanceUntilIdle()
        assertEquals(ControlMode.Hub, vm.ui.value.mode)
        repo.commandResult = HubResult.Err(HubError.Timeout("timeout"))
        vm.wakePc(); advanceUntilIdle()
        assertEquals(listOf("command pc.wake"), repo.calls.filter { it.startsWith("command") })
        assertEquals(WakeVia.DirectEsp32, (vm.ui.value.wake as WakeState.Sent).via)
    }

    @Test fun hubRefusalIsNotMaskedByFallback() = scope.runTest {
        repo.commandResult = HubResult.Err(HubError.Unauthorized)
        val vm = vm(); advanceUntilIdle()
        vm.wakePc(); advanceUntilIdle()
        assertEquals(HubError.Unauthorized, (vm.ui.value.wake as WakeState.Failed).error)
        assertTrue(direct.calls.none { it.startsWith("wake") })
    }

    @Test fun directFailureIsShown() = scope.runTest {
        repo.status = FakeHubRepository.unreachable
        direct.wake = HubResult.Err(HubError.Esp32Refused(429, "rate_limited"))
        val vm = vm(); advanceUntilIdle()
        vm.wakePc(); advanceUntilIdle()
        val e = (vm.ui.value.wake as WakeState.Failed).error as HubError.Esp32Refused
        assertEquals(429, e.code)
    }

    @Test fun noDirectConfiguredMeansOffline() = scope.runTest {
        repo.status = FakeHubRepository.unreachable
        repo.commandResult = FakeHubRepository.unreachable
        val vm = vm(InMemorySettingsStore(HubSettings("http://192.168.1.20:8787/", "t", paired = true))); advanceUntilIdle()
        assertEquals(ControlMode.Offline, vm.ui.value.mode)
        assertNull(vm.ui.value.direct)
        vm.wakePc(); advanceUntilIdle()
        assertTrue((vm.ui.value.wake as WakeState.Failed).error is HubError.Unreachable)
        assertEquals(emptyList<String>(), direct.calls)
    }

    @Test fun settingsSaveDirectValidatesSavesAndTests() = scope.runTest {
        val store = InMemorySettingsStore(HubSettings("http://192.168.1.20:8787/", "t", paired = true))
        val vm = SettingsViewModel(store, repo, MutableStateFlow(LiveSnapshot()), service = MutableStateFlow(com.victorious.pyraclaw.service.ServiceState()), direct = direct)
        backgroundScope.launch { vm.ui.collect {} }
        vm.saveDirect("", WAKE_TOKEN); runCurrent()
        assertTrue(vm.ui.value.directTest is DirectTest.Invalid)
        vm.saveDirect("pyra-esp32-wake-01.local", "short"); runCurrent()
        assertTrue(vm.ui.value.directTest is DirectTest.Invalid)
        assertFalse(store.current().hasDirect)
        vm.saveDirect("pyra-esp32-wake-01.local", " $WAKE_TOKEN "); advanceUntilIdle()
        assertEquals(esp, store.current().directUrl)
        assertEquals(WAKE_TOKEN, store.current().directToken)
        assertTrue(vm.ui.value.directTest is DirectTest.Ok)
        assertEquals("health $esp", direct.calls.last())
        vm.clearDirect(); runCurrent()
        assertFalse(store.current().hasDirect)
        assertEquals(DirectTest.Idle, vm.ui.value.directTest)
    }
}

/** Dashboard view model + real HTTP client + scripted ESP32, end to end. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectModeIntegrationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun wakeFallsBackOverHttp() = TestScope(dispatcher).runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"ok":true,"node_id":"esp32-wake-01","fw":"0.4.0","time_synced":true,"can_wake":true}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"sent":"wake_pc"}"""))
            val repo = FakeHubRepository().apply {
                status = FakeHubRepository.unreachable; commandResult = FakeHubRepository.unreachable
            }
            val store = InMemorySettingsStore(HubSettings("http://192.168.1.20:8787/", "t", paired = true, directUrl = server.url("/").toString(), directToken = WAKE_TOKEN))
            val vm = DashboardViewModel(repo, MutableStateFlow(LiveSnapshot()), {}, settings = store, direct = DefaultDirectEsp32(OkHttpClient()))
            backgroundScope.launch { vm.ui.collect {} }
            fun pump(until: () -> Boolean) {
                val end = System.currentTimeMillis() + 5_000
                while (!until() && System.currentTimeMillis() < end) { Thread.sleep(10); dispatcher.scheduler.advanceUntilIdle() }
            }
            pump { vm.ui.value.direct?.health != null }
            assertEquals(ControlMode.DirectEsp32, vm.ui.value.mode)
            vm.wakePc()
            pump { vm.ui.value.wake is WakeState.Sent || vm.ui.value.wake is WakeState.Failed }
            assertEquals(WakeVia.DirectEsp32, (vm.ui.value.wake as WakeState.Sent).via)
            assertEquals("/health", server.takeRequest().path)
            val wake = server.takeRequest()
            assertEquals("/wake", wake.path)
            assertEquals("Bearer $WAKE_TOKEN", wake.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
