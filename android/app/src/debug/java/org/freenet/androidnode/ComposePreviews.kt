package org.freenet.androidnode

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true, widthDp = 380, heightDp = 820, name = "Dashboard stopped")
@Composable
fun DashboardStoppedPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DashboardPanel(
                state = NodeUiState(
                    state = NodePhase.Stopped,
                    detail = "Node stopped. Start a node from the menu.",
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820, name = "Dashboard running")
@Composable
fun DashboardRunningPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DashboardPanel(
                state = NodeUiState(
                    state = NodePhase.RunningNetwork,
                    mode = NodeMode.Network,
                    detail = "Freenet node is running",
                    peers = 12,
                    bytesSent = 1_240_000,
                    bytesReceived = 4_800_000,
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820, name = "Freenet sites")
@Composable
fun FreenetSitesPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SitesPanel(
                onClose = {},
                initialSites = FreenetSites.defaults + FreenetSite(
                    name = "My contract",
                    url = "http://127.0.0.1:7509/v1/contract/web/example/",
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 820, name = "For nerds")
@Composable
fun DiagnosticsPreview() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DiagnosticsPanel(
                onClose = {},
                onIdentityRestored = {},
                onEditConfig = {},
                onOpenExternal = {},
                policies = NodePolicyState(),
                udpPortInUse = false,
                notifyUdpBusy = false,
                onNotifyUdpBusy = {},
                minConnections = 20,
                maxConnections = 80,
                onSaveConnectionLimits = { _, _ -> },
                notifyConnected = true,
                notifyStopped = true,
                onNotifyConnected = {},
                onNotifyStopped = {},
                includePrereleases = true,
                onIncludePrereleases = {},
                onSaveUdpPortSettings = { _, _ -> },
                onUseSavedUdp = {},
                onUseRandomUdp = {},
                onStartLocal = {},
                onReset = {},
                canStartLocal = true,
                autoRestartOnCrash = true,
                onAutoRestartOnCrash = {},
                restoreMessage = null,
                onRestoreBackup = {},
            )
        }
    }
}
