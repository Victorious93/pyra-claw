package com.victorious.pyraclaw.fleet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.victorious.pyraclaw.service.HubConnectionService
import com.victorious.pyraclaw.service.ServiceBus

/**
 * STUB entry point for droidcommand-AI (EXTERNAL_NODE contract not finalized).
 *
 * Expects action [ACTION_COMMAND] with extra "envelope" = remote.command JSON, e.g.
 * {"type":"remote.command","command":"charge.off","target":"phone2","args":{}}
 * Protected by a signature permission, so only apps signed with the same key can send.
 * Approval-required commands still need an approval_token; the hub enforces this.
 */
class ExternalNodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val env = intent.getStringExtra(HubConnectionService.EXTRA_ENVELOPE) ?: return
        try {
            HubConnectionService.execute(context, env)
            ServiceBus.log("droidcommand-AI command received")
        } catch (e: IllegalStateException) {
            // Android 12+: cannot start an FGS from the background unless it is already running.
            ServiceBus.log("droidcommand-AI command dropped: service not running (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val ACTION_COMMAND = "ai.droidcommand.action.REMOTE_COMMAND"
    }
}
