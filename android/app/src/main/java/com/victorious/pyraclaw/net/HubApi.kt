package com.victorious.pyraclaw.net

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.UUID

/** One JSON config for REST, WebSocket frames and tests. Unknown hub fields are ignored. */
val HubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class HealthDto(val ok: Boolean = false)

@Serializable
data class NodeDto(
    val id: String,
    val online: Boolean = false,
    val last_seen: Double = 0.0,
    val status: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class StatusDto(
    val version: String = "?",
    val protocol: String? = null,
    val uptime_s: Double = 0.0,
    val mqtt: Boolean = false,
    val nodes: List<NodeDto> = emptyList(),
)

@Serializable
data class NodesDto(val nodes: List<NodeDto> = emptyList())

@Serializable
data class AuditDto(val events: List<JsonObject> = emptyList())

@Serializable
data class ApprovalDto(val command: String, val approval_token: String, val ttl_s: Int = 30)

@Serializable
data class CommandResult(val id: String = "", val ok: Boolean = false, val result: JsonElement? = null)

/** droidcommand-AI fleet envelope (remote.command). */
@Serializable
data class RemoteCommand(
    val command: String,
    val target: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val approval_token: String? = null,
    val source: String = "pyra-claw-android",
    val id: String = UUID.randomUUID().toString(),
    val type: String = "remote.command",
)

// ---- v0.3 timers + devices -------------------------------------------------------
@Serializable
data class StepDto(
    val command: String,
    val target: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val delay_s: Double = 0.0,
)

@Serializable
data class ScheduleDto(
    val kind: String = "manual", // once | daily | weekly | manual
    val at: String? = null, // once: ISO local datetime, e.g. 2026-11-01T07:30
    val time: String? = null, // daily/weekly: HH:MM
    val days: List<Int> = emptyList(), // weekly: 0=Mon..6=Sun
)

@Serializable
data class TimerDto(
    val id: String,
    val name: String = "Timer",
    val enabled: Boolean = true,
    val schedule: ScheduleDto = ScheduleDto(),
    val steps: List<StepDto> = emptyList(),
    val preapproved: Boolean = false,
    val next_run: Double? = null,
    val last_run: Double? = null,
    val last_result: String? = null,
    val running: Boolean = false,
    val current_step: Int? = null,
)

@Serializable
data class TimerIn(
    val name: String,
    val enabled: Boolean,
    val schedule: ScheduleDto,
    val steps: List<StepDto>,
    val approval_token: String? = null,
)

@Serializable
data class TimersDto(val timers: List<TimerDto> = emptyList(), val events: List<JsonObject> = emptyList())

@Serializable
data class DeviceDto(
    val id: String,
    val label: String? = null,
    val charge_backend: String? = null,
    val power_on_backend: String? = null,
    val power_off_backend: String? = null,
)

@Serializable
data class DevicesDto(val devices: List<DeviceDto> = emptyList())

/** One `/ws` frame: the status object plus the timers snapshot. */
@Serializable
data class WsFrame(
    val version: String = "?",
    val protocol: String? = null,
    val uptime_s: Double = 0.0,
    val mqtt: Boolean = false,
    val nodes: List<NodeDto> = emptyList(),
    val timers: TimersDto? = null,
) {
    fun status() = StatusDto(version, protocol, uptime_s, mqtt, nodes)
}

/** Pyra Claw hub REST surface (docs/PROTOCOL.md v0.3). Auth is added by the OkHttp client. */
interface HubApi {
    @GET("health") suspend fun health(): HealthDto
    @GET("api/status") suspend fun status(): StatusDto
    @GET("api/nodes") suspend fun nodes(): NodesDto
    @GET("api/audit") suspend fun audit(): AuditDto
    @POST("api/approve/{command}") suspend fun approve(@Path("command") command: String): ApprovalDto
    @POST("api/command") suspend fun command(@Body cmd: RemoteCommand): CommandResult

    @GET("api/devices") suspend fun devices(): DevicesDto
    @GET("api/timers") suspend fun timers(): TimersDto
    @POST("api/timers") suspend fun createTimer(@Body t: TimerIn): TimerDto
    @PUT("api/timers/{id}") suspend fun updateTimer(@Path("id") id: String, @Body t: TimerIn): TimerDto
    @DELETE("api/timers/{id}") suspend fun deleteTimer(@Path("id") id: String): JsonObject
    @POST("api/timers/{id}/enable") suspend fun enableTimer(@Path("id") id: String): TimerDto
    @POST("api/timers/{id}/disable") suspend fun disableTimer(@Path("id") id: String): TimerDto
    @POST("api/timers/{id}/run") suspend fun runTimer(@Path("id") id: String): JsonObject

    companion object {
        fun create(baseUrl: String, client: OkHttpClient): HubApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(HubJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HubApi::class.java)
    }
}
