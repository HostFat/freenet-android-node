package org.freenet.androidnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NodePolicyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduleTick = intent.action == ACTION_SCHEDULE_TICK
        if (!scheduleTick &&
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
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
        if (!scheduleTick && !policy.automatic && !policy.startOnBoot) return

        runCatching {
            context.startForegroundService(NodeService.reconcilePolicyIntent(context))
        }.onFailure { error ->
            Log.w(
                TAG,
                "Android did not permit the best-effort automatic node controller to start",
                error,
            )
        }
    }

    companion object {
        private const val TAG = "FreenetPolicyReceiver"
        const val ACTION_SCHEDULE_TICK = "org.freenet.androidnode.action.SCHEDULE_TICK"
    }
}
