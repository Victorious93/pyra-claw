package com.victorious.pyraclaw.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HubUrl {
    const val DEFAULT_PORT = 8787
    const val ESP32_PORT = 80

    /**
     * Turns what a person types ("192.168.1.20", "pyra.local:8787", "http://10.0.0.5:8787")
     * into a Retrofit base URL with scheme, port and trailing slash. Returns null if unusable.
     */
    fun normalize(input: String, defaultPort: Int = DEFAULT_PORT): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        val parsed = s.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        val hasExplicitPort = Regex("^[a-zA-Z]+://[^/]+:\\d+").containsMatchIn(s)
        val port = if (hasExplicitPort) parsed.port else defaultPort
        val path = parsed.encodedPath.trimEnd('/') + "/"
        return parsed.newBuilder().port(port).encodedPath(path).query(null).fragment(null).build().toString()
    }

    /** ESP32 node base URL: "pyra-esp32-wake-01.local" -> "http://pyra-esp32-wake-01.local/". */
    fun normalizeEsp32(input: String): String? = normalize(input, ESP32_PORT)

    /** ws:// URL for the hub's live status socket. The token goes in the query (hub contract). */
    fun webSocket(base: String): String =
        base.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/ws"
}
