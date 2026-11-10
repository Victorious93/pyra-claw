package com.victorious.pyraclaw

import com.victorious.pyraclaw.data.FoundHub
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.data.HubSettings
import com.victorious.pyraclaw.data.InMemorySettingsStore
import com.victorious.pyraclaw.data.LinkState
import com.victorious.pyraclaw.data.LiveSnapshot
import com.victorious.pyraclaw.net.TimersDto
import com.victorious.pyraclaw.service.HubConnectionService
import com.victorious.pyraclaw.ui.activity.ActivityViewModel
import com.victorious.pyraclaw.ui.activity.AuditFilter
import com.victorious.pyraclaw.ui.approval.ApprovalPhase
import com.victorious.pyraclaw.ui.approval.ApprovalRequest
import com.victorious.pyraclaw.ui.approval.ApprovalViewModel
import com.victorious.pyraclaw.ui.components.Load
import com.victorious.pyraclaw.ui.dashboard.DashboardViewModel
import com.victorious.pyraclaw.ui.dashboard.WakeState
import com.victorious.pyraclaw.ui.devices.DevicesViewModel
import com.victorious.pyraclaw.ui.onboarding.OnboardingViewModel
import com.victorious.pyraclaw.ui.onboarding.TestState
import com.victorious.pyraclaw.ui.timers.StepDraft
import com.victorious.pyraclaw.ui.timers.TimerDraft
import com.victorious.pyraclaw.ui.timers.TimersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTests {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val repo = FakeHubRepository(nowMs = { dispatcher.scheduler.currentTime })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectIn(flow: kotlinx.coroutines.flow.Flow<*>) = backgroundScope.launch { flow.collect {} }

    // ---- approval ------------------------------------------------------------------
    private val pulse = ApprovalRequest("pc.power_pulse", title = "t", body = "b", actionLabel = "Press")

    @Test fun approvalHappyPathSendsTokenOnce() = scope.runTest {
        val vm = ApprovalViewModel(repo, now = { dispatcher.scheduler.currentTime })
        vm.open(pulse)
        assertEquals(ApprovalPhase.Review, vm.ui.value!!.phase)
        vm.requestApproval(); runCurrent()
        val armed = vm.ui.value!!.phase as ApprovalPhase.Armed
        assertEquals(29, armed.secondsLeft)
        vm.send(); advanceUntilIdle()
        assertTrue(vm.ui.value!!.phase is ApprovalPhase.Done)
        assertEquals("tok-pc.power_pulse", repo.commands.single().approval_token)
        assertEquals(listOf("approve pc.power_pulse", "command pc.power_pulse"), repo.calls)
    }

    @Test fun approvalCountsDownAndExpiresWithoutSending() = scope.runTest {
        val vm = ApprovalViewModel(repo, now = { dispatcher.scheduler.currentTime })
        vm.open(pulse); vm.requestApproval(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(19, (vm.ui.value!!.phase as ApprovalPhase.Armed).secondsLeft)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(ApprovalPhase.Expired, vm.ui.value!!.phase)
        vm.send(); advanceUntilIdle()
        assertTrue("no command after expiry", repo.commands.isEmpty())
    }

    @Test fun approvalFailureIsShown() = scope.runTest {
        repo.approveResult = { HubResult.Err(HubError.Unauthorized) }
        val vm = ApprovalViewModel(repo)
        vm.open(pulse); vm.requestApproval(); advanceUntilIdle()
        assertEquals(ApprovalPhase.Failed(HubError.Unauthorized), vm.ui.value!!.phase)
        vm.dismiss(); assertNull(vm.ui.value)
    }

    // ---- dashboard -----------------------------------------------------------------
    @Test fun wakeSuccessAndFailure() = scope.runTest {
        val live = MutableStateFlow(LiveSnapshot())
        val vm = DashboardViewModel(repo, live, reconnect = {}, now = { 42L })
        collectIn(vm.ui); advanceUntilIdle()
        assertEquals(Sample.status, vm.ui.value.status) // REST fallback while the socket is down
        vm.wakePc(); advanceUntilIdle()
        assertEquals(WakeState.Sent(102, true, 42L), vm.ui.value.wake)
        repo.commandResult = FakeHubRepository.unreachable
        vm.wakePc(); advanceUntilIdle()
        assertTrue((vm.ui.value.wake as WakeState.Failed).error is HubError.Unreachable)
        assertEquals(null, repo.commands.last().target) // VictorSuite-compatible pc.wake
    }

    @Test fun liveFrameWinsOverRest() = scope.runTest {
        val liveStatus = Sample.status.copy(version = "9.9.9")
        val live = MutableStateFlow(LiveSnapshot(LinkState.Connected(1), liveStatus, TimersDto(Sample.timers), 5))
        val vm = DashboardViewModel(repo, live, reconnect = {})
        collectIn(vm.ui); advanceUntilIdle()
        assertEquals("9.9.9", vm.ui.value.status!!.version)
        assertEquals(listOf("t3"), vm.ui.value.runningTimers.map { it.id })
        assertEquals(listOf("t3"), vm.ui.value.manualTimers.map { it.id })
    }

    @Test fun restErrorShownWhenOffline() = scope.runTest {
        repo.status = FakeHubRepository.unreachable
        val vm = DashboardViewModel(repo, MutableStateFlow(LiveSnapshot()), reconnect = {})
        collectIn(vm.ui); advanceUntilIdle()
        assertTrue(vm.ui.value.restError is HubError.Unreachable)
        assertNull(vm.ui.value.status)
    }

    // ---- devices -------------------------------------------------------------------
    @Test fun devicesLoadAndRun() = scope.runTest {
        val vm = DevicesViewModel(repo, now = { 7L })
        advanceUntilIdle()
        assertEquals(3, (vm.ui.value.devices as Load.Ready).data.size)
        vm.run("phone1", "charge.on"); advanceUntilIdle()
        assertEquals("phone1", repo.commands.single().target)
        assertTrue(vm.ui.value.results["phone1"]!!.ok)
        assertTrue(vm.ui.value.busy.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun devicesRefuseGatedWithoutApproval() {
        DevicesViewModel(repo).run("phone1", "device.power_off")
    }

    @Test fun devicesErrorState() = scope.runTest {
        repo.devices = FakeHubRepository.unreachable
        val vm = DevicesViewModel(repo); advanceUntilIdle()
        assertTrue(vm.ui.value.devices is Load.Failed)
    }

    // ---- timers --------------------------------------------------------------------
    @Test fun newGatedTimerIsArmedOnSave() = scope.runTest {
        val vm = TimersViewModel(repo, MutableStateFlow(LiveSnapshot()))
        collectIn(vm.ui); advanceUntilIdle()
        vm.newTimer()
        vm.updateDraft { it.copy(name = "Off at night", steps = listOf(StepDraft("device.power_off", "phone1"))) }
        assertTrue(vm.draftNeedsArm())
        vm.save(); advanceUntilIdle()
        assertTrue("approve timer.arm" in repo.calls)
        assertEquals("tok-timer.arm", repo.savedTimers.single().second.approval_token)
        assertNull(vm.ui.value.editor)
    }

    @Test fun unchangedArmedTimerDoesNotReapprove() = scope.runTest {
        val vm = TimersViewModel(repo, MutableStateFlow(LiveSnapshot()))
        collectIn(vm.ui); advanceUntilIdle()
        vm.edit(Sample.timers.first { it.id == "t2" })
        vm.updateDraft { it.copy(name = "Renamed") }
        assertFalse(vm.draftNeedsArm())
        vm.save(); advanceUntilIdle()
        assertFalse(repo.calls.any { it.startsWith("approve") })
        assertEquals("t2" to null, repo.savedTimers.single().let { it.first to it.second.approval_token })
        // Changing the steps of a gated timer needs a new approval.
        vm.edit(Sample.timers.first { it.id == "t2" })
        vm.moveStep(0, 1)
        assertTrue(vm.draftNeedsArm())
    }

    @Test fun invalidDraftIsNotSaved() = scope.runTest {
        val vm = TimersViewModel(repo, MutableStateFlow(LiveSnapshot()))
        collectIn(vm.ui); advanceUntilIdle()
        vm.newTimer(); vm.save(); advanceUntilIdle()
        assertTrue(repo.savedTimers.isEmpty())
        assertNotNull(vm.ui.value.editor)
    }

    @Test fun draftConversions() {
        val d = TimerDraft(name = " Weekly ", kind = "weekly", time = "06:45", days = setOf(4, 0), steps = listOf(StepDraft("pc.wake", "phone1", 5)))
        val t = d.toTimerIn()
        assertEquals("Weekly", t.name)
        assertEquals(listOf(0, 4), t.schedule.days)
        assertNull("pc.wake never carries a target", t.steps.single().target)
        assertEquals(listOf("Pick a date"), d.copy(kind = "once", date = "").errors())
        assertEquals("2026-11-10T07:30", d.copy(kind = "once", date = "2026-11-10", time = "07:30").toTimerIn().schedule.at)
        val back = TimerDraft.from(Sample.timers[0])
        assertEquals("23:00", back.time)
        assertEquals(3, back.steps.size)
        assertTrue(TimerDraft(name = "x", steps = listOf(StepDraft("charge.on", null))).errors().any { "device" in it })
    }

    @Test fun liveTimersOverrideRest() = scope.runTest {
        val live = MutableStateFlow(LiveSnapshot())
        val vm = TimersViewModel(repo, live)
        collectIn(vm.ui); advanceUntilIdle()
        assertFalse(vm.ui.value.live)
        live.value = LiveSnapshot(LinkState.Connected(0), Sample.status, TimersDto(Sample.timers.take(1)))
        runCurrent()
        assertTrue(vm.ui.value.live)
        assertEquals(1, (vm.ui.value.timers as Load.Ready).data.size)
    }

    // ---- onboarding ----------------------------------------------------------------
    @Test fun onboardingValidatesTestsAndSaves() = scope.runTest {
        val store = InMemorySettingsStore(HubSettings(hubUrl = "", token = ""))
        val vm = OnboardingViewModel(repo, store) { flowOf(FoundHub("Pyra Claw Hub", "http://192.168.1.20:8787/", "mDNS")) }
        vm.test()
        assertNotNull(vm.ui.value.urlError); assertNotNull(vm.ui.value.tokenError)
        vm.setUrl("192.168.1.20"); vm.setToken(" abc ")
        vm.test(); advanceUntilIdle()
        assertTrue(vm.ui.value.test is TestState.Success)
        assertEquals("test http://192.168.1.20:8787/", repo.calls.last())
        vm.save()
        assertEquals(HubSettings("http://192.168.1.20:8787/", "abc", paired = true), store.current())
        vm.startScan(); advanceUntilIdle()
        assertEquals(1, vm.ui.value.found.size)
        assertFalse(vm.ui.value.scanning)
    }

    @Test fun onboardingShowsTokenError() = scope.runTest {
        repo.test = HubResult.Err(HubError.Unauthorized)
        val vm = OnboardingViewModel(repo, InMemorySettingsStore()) { emptyFlow() }
        vm.setUrl("http://pyra.local:8787"); vm.setToken("nope"); vm.test(); advanceUntilIdle()
        assertEquals(TestState.Failure(HubError.Unauthorized), vm.ui.value.test)
    }

    // ---- activity ------------------------------------------------------------------
    @Test fun activityFiltersAndSearch() = scope.runTest {
        val vm = ActivityViewModel(repo); advanceUntilIdle()
        assertEquals(7, vm.ui.value.visible.size)
        vm.setFilter(AuditFilter.Problems)
        assertEquals(listOf("rejected", "failed"), vm.ui.value.visible.map { it.event })
        vm.setFilter(AuditFilter.All); vm.setQuery("uhubctl")
        assertEquals("failed", vm.ui.value.visible.single().event)
        vm.setQuery(""); vm.setFilter(AuditFilter.Timers)
        assertEquals(2, vm.ui.value.visible.size)
    }

    // ---- droidcommand-AI envelope parsing (service) --------------------------------
    @Test fun envelopeParsing() {
        val c = HubConnectionService.parseEnvelope("""{"type":"remote.command","command":"charge.off","target":"phone2","args":{}}""")!!
        assertEquals("charge.off" to "phone2", c.command to c.target)
        val m = HubConnectionService.parseEnvelope("""{"type":"macro.step","macro_id":"m","step":0,"command":{"type":"remote.command","command":"pc.wake"}}""")!!
        assertEquals("pc.wake", m.command)
        assertNull(HubConnectionService.parseEnvelope("not json"))
        assertNull(HubConnectionService.parseEnvelope("""{"type":"remote.command"}"""))
    }
}
