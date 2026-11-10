package com.victorious.pyraclaw.data

import com.victorious.pyraclaw.net.ApprovalDto
import com.victorious.pyraclaw.net.CommandResult
import com.victorious.pyraclaw.net.DeviceDto
import com.victorious.pyraclaw.net.HubApi
import com.victorious.pyraclaw.net.HubJson
import com.victorious.pyraclaw.net.RemoteCommand
import com.victorious.pyraclaw.net.StatusDto
import com.victorious.pyraclaw.net.TimerDto
import com.victorious.pyraclaw.net.TimerIn
import com.victorious.pyraclaw.net.TimersDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** One parsed `/api/audit` entry. */
data class AuditEvent(
    val ts: Double,
    val event: String,
    val command: String?,
    val fields: Map<String, String>,
) {
    companion object {
        fun from(o: JsonObject): AuditEvent {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val fields = o.filterKeys { it !in setOf("ts", "event", "command") }
                .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
            return AuditEvent(str("ts")?.toDoubleOrNull() ?: 0.0, str("event") ?: "?", str("command"), fields)
        }
    }
}

/** Approval token plus the wall-clock moment it stops being valid on the phone side. */
data class Approval(val command: String, val token: String, val ttlS: Int, val expiresAtMs: Long)

interface HubRepository {
    /** Checks an arbitrary URL/token pair without saving it (onboarding). */
    suspend fun testConnection(baseUrl: String, token: String): HubResult<StatusDto>
    suspend fun status(): HubResult<StatusDto>
    suspend fun audit(): HubResult<List<AuditEvent>>
    suspend fun devices(): HubResult<List<DeviceDto>>
    suspend fun timers(): HubResult<TimersDto>
    suspend fun saveTimer(id: String?, body: TimerIn): HubResult<TimerDto>
    suspend fun deleteTimer(id: String): HubResult<Unit>
    suspend fun setTimerEnabled(id: String, enabled: Boolean): HubResult<TimerDto>
    suspend fun runTimer(id: String): HubResult<Unit>
    suspend fun approve(command: String): HubResult<Approval>
    suspend fun command(cmd: RemoteCommand): HubResult<CommandResult>
}

/**
 * Retrofit-backed repository. Reads URL + token from [settings] on every call, so a re-pair takes
 * effect immediately. Every exception becomes a [HubError]; coroutine cancellation is rethrown.
 */
class DefaultHubRepository(
    private val settings: SettingsStore,
    private val baseClient: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) : HubRepository {
    private var cached: Triple<String, String, HubApi>? = null

    private fun apiFor(url: String, token: String): HubApi {
        cached?.let { (u, t, api) -> if (u == url && t == token) return api }
        val client = baseClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
            .build()
        return HubApi.create(url, client).also { cached = Triple(url, token, it) }
    }

    private suspend fun <T> call(block: suspend (HubApi) -> T): HubResult<T> {
        val s = settings.current()
        if (!s.isConfigured) return HubResult.Err(HubError.NotConfigured)
        return guard { block(apiFor(s.hubUrl, s.token)) }
    }

    override suspend fun testConnection(baseUrl: String, token: String): HubResult<StatusDto> {
        val api = HubApi.create(baseUrl, baseClient.newBuilder()
            .addInterceptor { c -> c.proceed(c.request().newBuilder().header("Authorization", "Bearer $token").build()) }
            .build())
        // /health has no auth: tells "wrong address" apart from "wrong token".
        when (val h = guard { api.health() }) {
            is HubResult.Err -> return h
            is HubResult.Ok -> if (!h.value.ok) return HubResult.Err(HubError.BadResponse("/health did not return ok"))
        }
        return guard { api.status() }
    }

    override suspend fun status() = call { it.status() }
    override suspend fun audit() = call { api -> api.audit().events.map(AuditEvent::from).sortedByDescending { it.ts } }
    override suspend fun devices() = call { it.devices().devices }
    override suspend fun timers() = call { it.timers() }
    override suspend fun saveTimer(id: String?, body: TimerIn) =
        call { if (id == null) it.createTimer(body) else it.updateTimer(id, body) }
    override suspend fun deleteTimer(id: String) = call { it.deleteTimer(id); Unit }
    override suspend fun setTimerEnabled(id: String, enabled: Boolean) =
        call { if (enabled) it.enableTimer(id) else it.disableTimer(id) }
    override suspend fun runTimer(id: String) = call { it.runTimer(id); Unit }
    override suspend fun approve(command: String) = call { api ->
        val a: ApprovalDto = api.approve(command)
        // Count down from receipt minus 1 s of slack so the phone never shows a token the hub already expired.
        Approval(a.command, a.approval_token, a.ttl_s, now() + (a.ttl_s - 1).coerceAtLeast(1) * 1000L)
    }
    override suspend fun command(cmd: RemoteCommand) = call { it.command(cmd) }

    private suspend fun <T> guard(block: suspend () -> T): HubResult<T> = withContext(Dispatchers.IO) {
        try {
            HubResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HubResult.Err(mapError(e))
        }
    }

    companion object {
        fun mapError(e: Throwable): HubError = when (e) {
            is HttpException -> fromHttp(e.code(), e.response()?.errorBody()?.string())
            is SocketTimeoutException -> HubError.Timeout(e.message)
            is InterruptedIOException -> HubError.Timeout(e.message)
            is IOException -> HubError.Unreachable(e.message ?: e.javaClass.simpleName)
            is SerializationException, is IllegalArgumentException -> HubError.BadResponse(e.message?.take(160))
            else -> HubError.BadResponse("${e.javaClass.simpleName}: ${e.message}".take(160))
        }

        /** FastAPI errors look like {"detail": "..."}; keep the detail for the user. */
        fun fromHttp(code: Int, body: String?): HubError {
            val detail = body?.let {
                runCatching { HubJson.parseToJsonElement(it).jsonObject["detail"]?.let { d -> (d as? JsonPrimitive)?.contentOrNull ?: d.toString() } }
                    .getOrNull() ?: it.take(160)
            }?.takeIf { it.isNotBlank() }
            return when (code) {
                401 -> HubError.Unauthorized
                403 -> HubError.ApprovalRejected(detail)
                404 -> HubError.NotFound(detail)
                409 -> HubError.Conflict(detail)
                502 -> HubError.DeviceFailed(detail)
                503 -> HubError.Unavailable(detail)
                400, 422 -> HubError.Invalid(code, detail)
                else -> HubError.Server(code, detail)
            }
        }
    }
}

/** Small helpers for reading command results. */
fun CommandResult.field(name: String): String? =
    (result as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }

fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
