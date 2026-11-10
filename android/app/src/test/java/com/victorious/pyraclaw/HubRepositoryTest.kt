package com.victorious.pyraclaw

import com.victorious.pyraclaw.data.DefaultHubRepository
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.InMemorySettingsStore
import com.victorious.pyraclaw.data.field
import com.victorious.pyraclaw.net.RemoteCommand
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real Retrofit + kotlinx.serialization stack against a scripted HTTP server. */
class HubRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySettingsStore
    private lateinit var repo: DefaultHubRepository
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = InMemorySettingsStore(HubSettings(server.url("/").toString(), "secret-token", paired = true))
        repo = DefaultHubRepository(store, client, now = { 1_000_000L })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    @Test fun statusParsesAndSendsBearer() = runTest {
        server.enqueue(json("""{"version":"0.3.0","protocol":"0.3","uptime_s":12.5,"mqtt":true,"nodes":[{"id":"esp32-wake-01","online":true,"last_seen":1.0,"status":{"ip":"192.168.1.42","rssi":-60}}],"extra":1}"""))
        val r = repo.status()
        assertTrue(r is HubResult.Ok)
        val s = (r as HubResult.Ok).value
        assertEquals("0.3", s.protocol)
        assertEquals("esp32-wake-01", s.nodes.single().id)
        val req = server.takeRequest()
        assertEquals("/api/status", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
    }

    @Test fun mapsHttpErrorsWithDetail() = runTest {
        server.enqueue(json("""{"detail":"invalid or missing token"}""", 401))
        assertEquals(HubError.Unauthorized, (repo.status() as HubResult.Err).error)
        server.enqueue(json("""{"detail":"pc.power_pulse requires a valid approval token"}""", 403))
        val e = (repo.command(RemoteCommand("pc.power_pulse")) as HubResult.Err).error
        assertTrue(e is HubError.ApprovalRejected)
        assertEquals("pc.power_pulse requires a valid approval token", e.detail)
        server.enqueue(json("""{"detail":"uhubctl failed: port 3"}""", 502))
        assertTrue((repo.command(RemoteCommand("charge.on", "phone3")) as HubResult.Err).error is HubError.DeviceFailed)
        server.enqueue(json("""{"detail":"timer is already running"}""", 409))
        assertTrue((repo.runTimer("t1") as HubResult.Err).error is HubError.Conflict)
        server.enqueue(json("""{"detail":"node.hub_token not configured"}""", 503))
        assertTrue((repo.command(RemoteCommand("pc.power_pulse")) as HubResult.Err).error is HubError.Unavailable)
    }

    @Test fun wakeResultFields() = runTest {
        server.enqueue(json("""{"id":"abc","ok":true,"result":{"wol_bytes":102,"relayed":false}}"""))
        val r = (repo.command(RemoteCommand("pc.wake")) as HubResult.Ok).value
        assertEquals("102", r.field("wol_bytes"))
        assertEquals("false", r.field("relayed"))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"command\":\"pc.wake\"") && body.contains("\"type\":\"remote.command\""))
    }

    @Test fun approvalExpiryHasSlack() = runTest {
        server.enqueue(json("""{"command":"pc.power_pulse","approval_token":"t","ttl_s":30}"""))
        val a = (repo.approve("pc.power_pulse") as HubResult.Ok).value
        assertEquals(1_000_000L + 29_000L, a.expiresAtMs)
        assertEquals("/api/approve/pc.power_pulse", server.takeRequest().path)
    }

    @Test fun auditIsNewestFirst() = runTest {
        server.enqueue(json("""{"events":[{"ts":1,"event":"accepted","command":"pc.wake","source":"x"},{"ts":5,"event":"rejected","command":"pc.power_pulse","reason":"no_approval"}]}"""))
        val ev = (repo.audit() as HubResult.Ok).value
        assertEquals(listOf("rejected", "accepted"), ev.map { it.event })
        assertEquals("no_approval", ev.first().fields["reason"])
    }

    @Test fun testConnectionDistinguishesAddressFromToken() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        server.enqueue(json("""{"detail":"invalid or missing token"}""", 401))
        assertEquals(HubError.Unauthorized, (repo.testConnection(server.url("/").toString(), "bad") as HubResult.Err).error)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test fun unreachableAndNotConfigured() = runTest {
        server.shutdown()
        assertTrue((repo.status() as HubResult.Err).error is HubError.Unreachable)
        store.forget()
        assertEquals(HubError.NotConfigured, (repo.status() as HubResult.Err).error)
    }

    @Test fun garbageBodyIsBadResponse() = runTest {
        server.enqueue(json("<html>not a hub</html>"))
        assertTrue((repo.status() as HubResult.Err).error is HubError.BadResponse)
    }
}
