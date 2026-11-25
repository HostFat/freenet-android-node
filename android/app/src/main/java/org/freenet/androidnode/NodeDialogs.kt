package org.freenet.androidnode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal sealed class PendingRestart {
    data class Connections(val min: Int, val max: Int) : PendingRestart()
    data object ConfigFile : PendingRestart()
    data object UdpPort : PendingRestart()
    data object Identity : PendingRestart()
}

@Composable
internal fun AlphaDisclaimerDialog(onAccept: () -> Unit) {
    var riskAccepted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.alpha_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.alpha_unofficial), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.alpha_not_optimized))
                Text(stringResource(R.string.alpha_risks))
                Text(stringResource(R.string.alpha_risks_list))
                Text(stringResource(R.string.alpha_early))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = riskAccepted,
                            role = Role.Checkbox,
                            onValueChange = { riskAccepted = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = riskAccepted,
                        onCheckedChange = null,
                    )
                    Text(
                        stringResource(R.string.alpha_accept_risks),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = riskAccepted,
            ) {
                Text(stringResource(R.string.alpha_accept))
            }
        },
    )
}

@Composable
internal fun ChangelogDialog(line: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.changelog_title)) },
        text = { Text(line) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss_changelog))
            }
        },
    )
}

@Composable
internal fun RestartPromptDialog(
    restart: PendingRestart,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restart_node_title)) },
        text = {
            Text(
                when (restart) {
                    is PendingRestart.Connections -> stringResource(
                        R.string.restart_node_message,
                        restart.min,
                        restart.max,
                    )
                    PendingRestart.ConfigFile -> stringResource(R.string.restart_config_message)
                    PendingRestart.UdpPort -> stringResource(R.string.restart_udp_port_message)
                    PendingRestart.Identity -> stringResource(R.string.restart_identity_message)
                },
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.restart_node_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.restart_node_deny))
            }
        },
    )
}

@Composable
internal fun CrashReportDialog(
    comment: String,
    onCommentChange: (String) -> Unit,
    uploadState: CrashUploadUiState,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!uploadState.sending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.send_crash_log_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.send_crash_log_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
                val sentId = uploadState.sentId
                if (sentId != null) {
                    Text(stringResource(R.string.send_crash_log_sent, sentId))
                } else {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = onCommentChange,
                        enabled = !uploadState.sending,
                        label = { Text(stringResource(R.string.crash_comment_label)) },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    uploadState.error?.let { error ->
                        Text(
                            stringResource(R.string.send_crash_log_failed, error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        enabled = !uploadState.sending,
                        onClick = onDismiss,
                    ) {
                        Text(stringResource(R.string.config_close))
                    }
                    if (uploadState.sentId == null) {
                        Button(
                            enabled = !uploadState.sending,
                            onClick = onSend,
                        ) {
                            Text(stringResource(R.string.send_crash_log_send))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_node_title)) },
        text = { Text(stringResource(R.string.reset_node_message)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.reset_node_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.restart_node_deny))
            }
        },
    )
}

@Composable
internal fun NoEditorDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_config_external)) },
        text = { Text(stringResource(R.string.config_no_editor)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.config_close))
            }
        },
    )
}

@Composable
internal fun ExternalGuideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_guide_title)) },
        text = { Text(stringResource(R.string.external_guide_body)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.external_guide_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.external_guide_cancel))
            }
        },
    )
}
