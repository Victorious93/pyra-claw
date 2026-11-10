package com.victorious.pyraclaw.ui

import com.victorious.pyraclaw.net.ScheduleDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

object Fmt {
    /** "just now", "42 s ago", "5 min ago", "3 h ago", "2 d ago" / "in 3 h 12 min". */
    fun relative(tsSeconds: Double?, nowMs: Long): String {
        if (tsSeconds == null || tsSeconds <= 0.0) return "never"
        val diff = (nowMs - (tsSeconds * 1000).toLong()) / 1000
        val future = diff < 0
        val s = abs(diff)
        val body = when {
            s < 5 -> return if (future) "now" else "just now"
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h" + ((s % 3600) / 60).let { if (it > 0 && s < 36_000) " $it min" else "" }
            else -> "${s / 86_400} d"
        }
        return if (future) "in $body" else "$body ago"
    }

    fun clock(tsSeconds: Double?): String =
        if (tsSeconds == null) "—" else SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date((tsSeconds * 1000).toLong()))

    fun time(tsSeconds: Double): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date((tsSeconds * 1000).toLong()))

    fun day(tsSeconds: Double): String = SimpleDateFormat("EEE d MMM", Locale.US).format(Date((tsSeconds * 1000).toLong()))

    fun uptime(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }

    fun schedule(s: ScheduleDto): String = when (s.kind) {
        "once" -> "Once · " + (s.at?.replace('T', ' ') ?: "?")
        "daily" -> "Every day · ${s.time ?: "?"}"
        "weekly" -> {
            val d = s.days.sorted()
            val days = when (d) {
                listOf(0, 1, 2, 3, 4) -> "Weekdays"
                listOf(5, 6) -> "Weekends"
                (0..6).toList() -> "Every day"
                else -> d.joinToString(" ") { DAY_NAMES.getOrElse(it) { "?" } }
            }
            "$days · ${s.time ?: "?"}"
        }
        else -> "Manual · run on demand"
    }

    fun delay(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s <= 0 -> "no wait"
            s < 60 -> "wait ${s}s"
            s % 60 == 0L && s < 3600 -> "wait ${s / 60} min"
            s < 3600 -> "wait ${s / 60}m ${s % 60}s"
            else -> "wait ${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}

/** Friendly names for hub commands. */
object Commands {
    val label = mapOf(
        "pc.wake" to "Wake PC",
        "pc.power_pulse" to "Press PC power button",
        "charge.on" to "Charging on",
        "charge.off" to "Charging off",
        "device.power_on" to "Power on",
        "device.power_off" to "Power off",
        "hub.status" to "Hub status",
        "node.list" to "List nodes",
        "timer.arm" to "Arm timer",
    )
    /** Commands the hub accepts in timer steps (hub KNOWN_COMMANDS minus read-only ones). */
    val STEP_COMMANDS = listOf("charge.on", "charge.off", "device.power_on", "device.power_off", "pc.wake")
    /** Hub APPROVAL_REQUIRED (docs/PROTOCOL.md). */
    val GATED = setOf("pc.power_pulse", "device.power_off")
    val NEEDS_TARGET = setOf("charge.on", "charge.off", "device.power_on", "device.power_off")

    fun name(c: String) = label[c] ?: c
}
