package org.freenet.androidnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NodePolicyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val scheduleTick = action == ACTION_SCHEDULE_TICK
        val packageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val bootCompleted = action == Intent.ACTION_BOOT_COMPLETED
        if (!scheduleTick && !bootCompleted && !packageReplaced) {
            return
        }
        if (!AlphaDisclaimerAcceptance.isAccepted(context)) {
            Log.i(TAG, "Skipping automatic node restoration until the alpha disclaimer is accepted")
            return
        }
        NodePolicyRepository.initialize(context)
        val policy = NodePolicyRepository.state.value
        if (policy.suspendedByUser) return
        if (scheduleTick && policy.power != NodePowerPolicy.Schedule) return

        val wasActiveBeforeUpdate = packageReplaced &&
            context.getSharedPreferences(NodeBackup.PREFS, Context.MODE_PRIVATE)
                .getBoolean("was_active", false)

        if (!scheduleTick && !policy.automatic && !policy.startOnBoot && !wasActiveBeforeUpdate) {
            return
        }

        // If an automatic policy owns the schedule/power state, reconcile it
        // instead of forcing an explicit start that would reset Schedule windows.
        val serviceIntent = if (wasActiveBeforeUpdate && !policy.automatic) {
            NodeService.startNetworkIntent(context)
        } else {
            NodeService.reconcilePolicyIntent(context)
        }

        runCatching {
            context.startForegroundService(serviceIntent)
        }.onFailure { error ->
            Log.w(
                TAG,
                "Android did not permit the best-effort automatic node controller to start",
                error,
            )
            NodeRepository.publishAutomaticStartBlocked(
                "Android did not allow the automatic node start: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    companion object {
        private const val TAG = "FreenetPolicyReceiver"
        const val ACTION_SCHEDULE_TICK = "org.freenet.androidnode.action.SCHEDULE_TICK"
    }
}
