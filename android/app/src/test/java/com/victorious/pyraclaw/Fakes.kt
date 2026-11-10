package com.victorious.pyraclaw

import com.victorious.pyraclaw.data.Approval
import com.victorious.pyraclaw.data.AuditEvent
import com.victorious.pyraclaw.data.HubError
import com.victorious.pyraclaw.data.HubRepository
import com.victorious.pyraclaw.data.HubResult
import com.victorious.pyraclaw.net.CommandResult
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.net.NodeDto
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.ScheduleDto
import com.victorious.pyraclaw.net.StatusDto
import com.victorious.pyraclaw.net.StepDto
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.net.TimerIn
import com.victorious.pyraclaw.net.TimersDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sample data used by tests and the screenshot renders. Not real hub output. */
object Sample {
    const val NOW_MS = 1794250080000L // 2026-11-09 18:48 UTC // fixed clock for screenshots
    private val now = NOW_MS / 1000.0
    val nodes = listOf(
        NodeDto(
            "esp32-wake-01", true, now - 4,
            JsonObject(mapOf("ip" to JsonPrimitive("192.168.1.42"), "fw" to JsonPrimitive("0.2.0"), "rssi" to JsonPrimitive(-58), "time_synced" to JsonPrimitive(true))),
        ),
        NodeDto("esp32-rack-02", false, now - 3 * 3600, JsonObject(mapOf("ip" to JsonPrimitive("192.168.1.57"), "fw" to JsonPrimitive("0.2.0")))),
    )
    val status = StatusDto("0.3.0", "0.3", 3 * 86_400.0 + 5 * 3600, true, nodes)
    val devices = listOf(
        DeviceDto("phone1", "Nexus 5X #1", "uhubctl", "uhubctl", "adb"),
        DeviceDto("phone2", "Nexus 5X #2", "uhubctl", "uhubctl", "adb"),
        DeviceDto("phone3", "Nexus 6P", "adb", null, null),
    )
    val timers = listOf(
        TimerDto(
            "t1", "Night charge rotation", true, ScheduleDto("daily", time = "23:00"),
            listOf(StepDto("charge.off", "phone1"), StepDto("charge.on", "phone2", delay_s = 30.0), StepDto("charge.on", "phone3", delay_s = 600.0)),
            next_run = now + 4 * 3600 + 12 * 60, last_run = now - 20 * 3600, last_result = "ok",
        ),
        TimerDto(
            "t2", "Weekday shutdown", true, ScheduleDto("weekly", time = "01:30", days = listOf(0, 1, 2, 3, 4)),
            listOf(StepDto("device.power_off", "phone3"), StepDto("charge.off", "phone3", delay_s = 60.0)),
            preapproved = true, next_run = now + 6 * 3600 + 42 * 60, last_result = null,
        ),
        TimerDto(
            "t3", "Morning wake", true, ScheduleDto("manual"),
            listOf(StepDto("pc.wake"), StepDto("charge.on", "phone1", delay_s = 10.0)),
            running = true, current_step = 1,
        ),
    )
    val timerEvents = listOf(
        JsonObject(mapOf("ts" to JsonPrimitive(now - 60), "name" to JsonPrimitive("Morning wake"), "event" to JsonPrimitive("step_ok"), "step" to JsonPrimitive(0), "result" to JsonPrimitive("ok"))),
        JsonObject(mapOf("ts" to JsonPrimitive(now - 30), "name" to JsonPrimitive("Morning wake"), "event" to JsonPrimitive("timer_started"))),
    )
    val audit = listOf(
        AuditEvent(now - 20, "executed", "pc.wake", mapOf("id" to "a1")),
        AuditEvent(now - 21, "accepted", "pc.wake", mapOf("source" to "victorsuite")),
        AuditEvent(now - 300, "rejected", "pc.power_pulse", mapOf("reason" to "no_approval")),
        AuditEvent(now - 900, "approval_issued", "pc.power_pulse", emptyMap()),
        AuditEvent(now - 3600, "timer_preapproved", null, mapOf("timer_id" to "t2", "name" to "Weekday shutdown")),
        AuditEvent(now - 7200, "timer_created", null, mapOf("timer_id" to "t1", "name" to "Night charge rotation")),
        AuditEvent(now - 7300, "failed", "charge.on", mapOf("error" to "uhubctl: port 3 not found", "target" to "phone3")),
    )
}

/** Scriptable in-memory hub. Records every call. */
class FakeHubRepository(var nowMs: () -> Long = { 0L }) : HubRepository {
    val calls = mutableListOf<String>()
    val commands = mutableListOf<RemoteCommand>()
    val savedTimers = mutableListOf<Pair<String?, TimerIn>>()
    var status: HubResult<StatusDto> = HubResult.Ok(Sample.status)
    var commandResult: HubResult<CommandResult> = HubResult.Ok(
        CommandResult("x", true, JsonObject(mapOf("wol_bytes" to JsonPrimitive(102), "relayed" to JsonPrimitive(true)))),
    )
    var approveResult: ((String) -> HubResult<Approval>)? = null
    var timers: HubResult<TimersDto> = HubResult.Ok(TimersDto(Sample.timers, Sample.timerEvents))
    var devices: HubResult<List<DeviceDto>> = HubResult.Ok(Sample.devices)
    var audit: HubResult<List<AuditEvent>> = HubResult.Ok(Sample.audit)
    var test: HubResult<StatusDto> = HubResult.Ok(Sample.status)

    override suspend fun testConnection(baseUrl: String, token: String) = test.also { calls += "test $baseUrl" }
    override suspend fun status() = status.also { calls += "status" }
    override suspend fun audit() = audit.also { calls += "audit" }
    override suspend fun devices() = devices.also { calls += "devices" }
    override suspend fun timers() = timers.also { calls += "timers" }
    override suspend fun saveTimer(id: String?, body: TimerIn): HubResult<TimerDto> {
        calls += "save $id"; savedTimers += id to body
        return HubResult.Ok(TimerDto(id ?: "new", body.name, body.enabled, body.schedule, body.steps, preapproved = body.approval_token != null))
    }
    override suspend fun deleteTimer(id: String): HubResult<Unit> { calls += "delete $id"; return HubResult.Ok(Unit) }
    override suspend fun setTimerEnabled(id: String, enabled: Boolean): HubResult<TimerDto> {
        calls += "enable $id $enabled"; return HubResult.Ok(Sample.timers.first { it.id == id }.copy(enabled = enabled))
    }
    override suspend fun runTimer(id: String): HubResult<Unit> { calls += "run $id"; return HubResult.Ok(Unit) }
    override suspend fun approve(command: String): HubResult<Approval> {
        calls += "approve $command"
        return approveResult?.invoke(command) ?: HubResult.Ok(Approval(command, "tok-$command", 30, nowMs() + 29_000))
    }
    override suspend fun command(cmd: RemoteCommand): HubResult<CommandResult> {
        calls += "command ${cmd.command}"; commands += cmd; return commandResult
    }

    companion object {
        val unreachable = HubResult.Err(HubError.Unreachable("failed to connect to /192.168.1.20:8787"))
    }
}
