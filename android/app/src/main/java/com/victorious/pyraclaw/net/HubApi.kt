package com.victorious.pyraclaw.net

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PUT
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NodeDto(
    val id: String,
    val online: Boolean,
    val last_seen: Double,
    val status: Map<String, Any?> = emptyMap(),
)
data class StatusDto(val version: String, val uptime_s: Double, val mqtt: Boolean, val nodes: List<NodeDto>)
data class NodesDto(val nodes: List<NodeDto>)
data class AuditDto(val events: List<Map<String, Any?>>)
data class ApprovalDto(val command: String, val approval_token: String, val ttl_s: Int)
data class CommandResult(val id: String, val ok: Boolean, val result: Map<String, Any?>?)

/** droidcommand-AI fleet envelope (remote.command). */
data class RemoteCommand(
    val command: String,
    val target: String? = null,
    val args: Map<String, Any?> = emptyMap(),
    val approval_token: String? = null,
    val source: String = "pyra-claw-android",
    val id: String = UUID.randomUUID().toString(),
    val type: String = "remote.command",
)

// ---- v0.3 timers ----------------------------------------------------------------
data class StepDto(
    val command: String,
    val target: String? = null,
    val args: Map<String, Any?> = emptyMap(),
    val delay_s: Double = 0.0,
)
data class ScheduleDto(
    val kind: String = "manual", // once | daily | weekly | manual
    val at: String? = null, // once: ISO local datetime, e.g. 2026-11-01T07:30
    val time: String? = null, // daily/weekly: HH:MM
    val days: List<Int> = emptyList(), // weekly: 0=Mon..6=Sun
)
data class TimerDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val schedule: ScheduleDto,
    val steps: List<StepDto>,
    val preapproved: Boolean = false,
    val next_run: Double? = null,
    val last_run: Double? = null,
    val last_result: String? = null,
    val running: Boolean = false,
    val current_step: Int? = null,
)
data class TimerIn(
    val name: String,
    val enabled: Boolean,
    val schedule: ScheduleDto,
    val steps: List<StepDto>,
    val approval_token: String? = null,
)
data class TimersDto(val timers: List<TimerDto>?, val events: List<Map<String, Any?>>?)
data class DeviceDto(
    val id: String,
    val label: String?,
    val charge_backend: String?,
    val power_on_backend: String?,
    val power_off_backend: String?,
)
data class DevicesDto(val devices: List<DeviceDto>?)

interface HubApi {
    @GET("api/devices") suspend fun devices(): DevicesDto
    @GET("api/timers") suspend fun timers(): TimersDto
    @POST("api/timers") suspend fun createTimer(@Body t: TimerIn): TimerDto
    @PUT("api/timers/{id}") suspend fun updateTimer(@Path("id") id: String, @Body t: TimerIn): TimerDto
    @DELETE("api/timers/{id}") suspend fun deleteTimer(@Path("id") id: String): Map<String, Any?>
    @POST("api/timers/{id}/enable") suspend fun enableTimer(@Path("id") id: String): TimerDto
    @POST("api/timers/{id}/disable") suspend fun disableTimer(@Path("id") id: String): TimerDto
    @POST("api/timers/{id}/run") suspend fun runTimer(@Path("id") id: String): Map<String, Any?>

    @GET("api/status") suspend fun status(): StatusDto
    @GET("api/nodes") suspend fun nodes(): NodesDto
    @GET("api/audit") suspend fun audit(): AuditDto
    @POST("api/approve/{command}") suspend fun approve(@Path("command") command: String): ApprovalDto
    @POST("api/command") suspend fun command(@Body cmd: RemoteCommand): CommandResult

    companion object {
        fun create(baseUrl: String, token: String): HubApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                    )
                }
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(HubApi::class.java)
        }
    }
}
