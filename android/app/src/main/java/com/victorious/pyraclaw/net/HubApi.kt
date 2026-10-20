package com.victorious.pyraclaw.net

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
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

interface HubApi {
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
