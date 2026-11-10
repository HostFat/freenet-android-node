package org.freenet.androidnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Runs after the APK is replaced. The installer kills the app but not the
 * child node process, so terminate the orphan and restart a node that was
 * active before the update.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Start the service immediately; it moves to the foreground first and
        // then terminates any orphan on a background thread.
        val prefs = context.getSharedPreferences("node_runtime", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("was_active", false)) return
        val mode = prefs.getString("mode", "network")
        val action = if (mode == "local") {
            NodeService.ACTION_START_LOCAL
        } else {
            NodeService.ACTION_START_NETWORK
        }
        runCatching {
            context.startForegroundService(
                Intent(context, NodeService::class.java).setAction(action),
            )
        }
    }
}
