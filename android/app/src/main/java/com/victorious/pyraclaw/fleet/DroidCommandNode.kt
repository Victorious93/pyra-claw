package com.victorious.pyraclaw.fleet

import android.content.Context
import android.content.Intent

/**
 * STUB: registers Pyra Claw as a droidcommand-AI EXTERNAL_NODE.
 * Commands arriving from the fleet use the remote.command / macro.step envelope
 * and are forwarded verbatim to the hub's /api/command endpoint.
 */
object DroidCommandNode {
    const val NODE_TYPE = "EXTERNAL_NODE"
    const val NODE_ID = "pyra-claw"
    const val ACTION_REGISTER = "ai.droidcommand.action.REGISTER_NODE"
    val CAPABILITIES = listOf("hub.status", "node.list", "pc.wake", "pc.power_pulse")
    val APPROVAL_REQUIRED = setOf("pc.power_pulse")

    fun register(context: Context) {
        val intent = Intent(ACTION_REGISTER).apply {
            putExtra("node_id", NODE_ID)
            putExtra("node_type", NODE_TYPE)
            putExtra("envelopes", arrayOf("remote.command", "macro.step"))
            putExtra("capabilities", CAPABILITIES.toTypedArray())
            putExtra("approval_required", APPROVAL_REQUIRED.toTypedArray())
        }
        // TODO: target droidcommand-AI package explicitly once its contract is finalized.
        context.sendBroadcast(intent)
    }
}
