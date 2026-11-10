package com.victorious.pyraclaw.data

/** Typed outcome of every hub call. The UI never sees raw exceptions. */
sealed interface HubResult<out T> {
    data class Ok<T>(val value: T) : HubResult<T>
    data class Err(val error: HubError) : HubResult<Nothing>
}

inline fun <T, R> HubResult<T>.map(f: (T) -> R): HubResult<R> = when (this) {
    is HubResult.Ok -> HubResult.Ok(f(value))
    is HubResult.Err -> this
}

fun <T> HubResult<T>.getOrNull(): T? = (this as? HubResult.Ok)?.value
fun <T> HubResult<T>.errorOrNull(): HubError? = (this as? HubResult.Err)?.error

/**
 * Everything that can go wrong talking to the hub, mapped from HTTP codes in docs/PROTOCOL.md.
 * [title] is a short headline, [hint] tells the user what to do next.
 */
sealed class HubError(val title: String, val hint: String, open val detail: String? = null) {
    data object NotConfigured : HubError(
        "No hub paired", "Open Settings > Change hub and enter your hub URL and token.",
    )
    data class Unreachable(override val detail: String?) : HubError(
        "Can't reach the hub",
        "Check the Pi is powered, the phone is on the same Wi-Fi or USB tether, and the URL and port (8787) are right.",
        detail,
    )
    data class Timeout(override val detail: String?) : HubError(
        "The hub didn't answer in time", "The Pi may be busy or the network is slow. Try again.", detail,
    )
    data object Unauthorized : HubError(
        "Token rejected (401)",
        "The auth token doesn't match the hub's auth_token in /etc/pyra-claw/config.toml.",
    )
    data class ApprovalRejected(override val detail: String?) : HubError(
        "Approval rejected (403)",
        "The approval token was missing, expired (30 s) or already used. Request a new approval.",
        detail,
    )
    data class NotFound(override val detail: String?) : HubError(
        "Not found (404)", "It may have been deleted on the hub. Pull to refresh.", detail,
    )
    data class Conflict(override val detail: String?) : HubError(
        "Busy (409)", "That timer is already running. Wait for it to finish.", detail,
    )
    data class DeviceFailed(override val detail: String?) : HubError(
        "Device backend failed (502)",
        "The hub couldn't drive this device. Check its backend in the hub config ([devices.<id>]).",
        detail,
    )
    data class Unavailable(override val detail: String?) : HubError(
        "Hub not ready (503)",
        "Something on the hub isn't configured, e.g. node.hub_token for power pulses.",
        detail,
    )
    data class Invalid(val code: Int, override val detail: String?) : HubError(
        "Request refused ($code)", "The hub didn't accept the request as sent.", detail,
    )
    data class Server(val code: Int, override val detail: String?) : HubError(
        "Hub error ($code)", "Check the hub log: journalctl -u pyra-hub -n 50", detail,
    )
    data class BadResponse(override val detail: String?) : HubError(
        "Unexpected reply", "The hub answered with something this app doesn't understand. Is it a Pyra Claw hub?",
        detail,
    )

    // ---- Direct ESP32 mode (no Pi) ---------------------------------------------------------
    data object DirectNotConfigured : HubError(
        "Direct ESP32 not set up", "Settings > Direct ESP32: enter the node address and its wake_token.",
    )
    data class Esp32Unreachable(override val detail: String?) : HubError(
        "Can't reach the hub or the ESP32",
        "Check the ESP32 is powered and on the same Wi-Fi, and its address (pyra-<node_id>.local or IP).",
        detail,
    )
    data class Esp32Refused(val code: Int, override val detail: String?) : HubError(
        when (code) {
            401 -> "ESP32 rejected the token (401)"
            429 -> "ESP32 rate limit (429)"
            503 -> "ESP32 has no token provisioned (503)"
            else -> "ESP32 error ($code)"
        },
        when (code) {
            401 -> "Use the node's wake_token (NODE_WAKE_TOKEN in /etc/pyra-claw/node-credentials)."
            429 -> "The ESP32 allows one wake every 10 s. Wait and try again."
            503 -> "Provision wake_token (or hub_token) on the ESP32 over USB serial."
            else -> "See docs/PROTOCOL.md, ESP32 standalone HTTP API."
        },
        detail,
    )

    /** One line for snackbars. */
    val message: String get() = detail?.takeIf { it.isNotBlank() }?.let { "$title: $it" } ?: title
}
