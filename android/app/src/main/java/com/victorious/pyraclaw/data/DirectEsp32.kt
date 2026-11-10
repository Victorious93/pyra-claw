package com.victorious.pyraclaw.data

import com.victorious.pyraclaw.net.HubJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** `GET /health` on the ESP32 node (firmware 0.2+; `can_wake`, `ntp`, `usb` since 0.4). */
@Serializable
data class DirectHealthDto(
    val ok: Boolean = false,
    val node_id: String? = null,
    val fw: String? = null,
    val protocol: String? = null,
    val time_synced: Boolean? = null,
    val mqtt: Boolean? = null,
    val ip: String? = null,
    val can_wake: Boolean? = null,
)

/** Result of a direct `POST /wake`. */
@Serializable
data class DirectWakeDto(val ok: Boolean = false, val sent: String? = null)

/**
 * Talks to an ESP32 node's standalone HTTP API with no Pi in between (docs/PROTOCOL.md).
 * Wake only: power pulses stay hub-only on purpose (see Help > Direct ESP32 mode).
 */
interface DirectEsp32 {
    suspend fun health(baseUrl: String): HubResult<DirectHealthDto>
    suspend fun wake(baseUrl: String, token: String): HubResult<DirectWakeDto>
}

class DefaultDirectEsp32(baseClient: OkHttpClient) : DirectEsp32 {
    // The node is on the LAN: fail fast so the Wake button never hangs.
    private val client = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun health(baseUrl: String): HubResult<DirectHealthDto> = io {
        val req = Request.Builder().url(baseUrl + "health").get().build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw DirectHttp(r.code, body)
            HubJson.decodeFromString(DirectHealthDto.serializer(), body)
        }
    }

    override suspend fun wake(baseUrl: String, token: String): HubResult<DirectWakeDto> = io {
        val req = Request.Builder().url(baseUrl + "wake")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw DirectHttp(r.code, body)
            HubJson.decodeFromString(DirectWakeDto.serializer(), body)
        }
    }

    private class DirectHttp(val code: Int, val body: String) : IOException("HTTP $code")

    private suspend fun <T> io(block: () -> T): HubResult<T> = withContext(Dispatchers.IO) {
        try {
            HubResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DirectHttp) {
            HubResult.Err(HubError.Esp32Refused(e.code, errField(e.body)))
        } catch (e: InterruptedIOException) {
            HubResult.Err(HubError.Esp32Unreachable("timed out"))
        } catch (e: IOException) {
            HubResult.Err(HubError.Esp32Unreachable(e.message ?: e.javaClass.simpleName))
        } catch (e: SerializationException) {
            HubResult.Err(HubError.BadResponse("ESP32: " + e.message?.take(120)))
        } catch (e: IllegalArgumentException) {
            HubResult.Err(HubError.Esp32Unreachable("bad address: ${e.message}"))
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Firmware errors look like {"ok":false,"err":"rate_limited"}. */
        fun errField(body: String): String? = runCatching {
            (HubJson.parseToJsonElement(body).jsonObject["err"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: body.take(80).ifBlank { null }
    }
}

/** True when a hub failure means "the Pi isn't there", the only case where direct mode kicks in. */
fun HubError.isHubDown(): Boolean =
    this is HubError.Unreachable || this is HubError.Timeout || this is HubError.NotConfigured
