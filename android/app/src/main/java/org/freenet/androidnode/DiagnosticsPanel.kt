package org.freenet.androidnode

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onIdentityRestored: () -> Unit,
    onEditConfig: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf("Collecting diagnostics…") }
    var logs by remember { mutableStateOf("Collecting logs…") }
    var showLogs by remember { mutableStateOf(false) }
    var identityMessage by remember { mutableStateOf<String?>(null) }
    val exportIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            identityMessage = IdentityBackup.export(context, uri)
        }
    }
    val importIdentity = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val message = IdentityBackup.importFrom(context, uri)
            identityMessage = message
            if (identityRestoreSucceeded(message)) {
                onIdentityRestored()
            }
        }
    }
    var logsExportMessage by remember { mutableStateOf<String?>(null) }
    val exportLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            logsExportMessage = writeTextToUri(context, uri, logs)
        }
    }
    val reportScope = rememberCoroutineScope()
    var showReportDialog by remember { mutableStateOf(false) }
    var reportComment by remember { mutableStateOf("") }
    var reportSending by remember { mutableStateOf(false) }
    var reportCode by remember { mutableStateOf<String?>(null) }
    var reportError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = withContext(Dispatchers.Default) { diagnosticSnapshot() }
            logs = withContext(Dispatchers.Default) { formatRecentLogs() }
            delay(2_000)
        }
    }

    BackHandler(enabled = showLogs) {
        showLogs = false
    }

    if (showLogs) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { copyToClipboard(context, logs) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.copy_all_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { exportLogs.launch("freenet-node-logs.txt") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.export_logs), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { showLogs = false }) {
                    Text(stringResource(R.string.config_close))
                }
            }
            logsExportMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            ScrollableMonospaceBox(
                text = logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.config_close))
        }
        Text(
            stringResource(R.string.config_toml_heading),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onEditConfig,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.edit_config_short))
            }
            OutlinedButton(
                onClick = onOpenExternal,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(R.string.open_config_editor_short),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            stringResource(R.string.config_open_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.identity_heading),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { exportIdentity.launch("freenet-identity-backup.zip") },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_identity_short))
            }
            OutlinedButton(
                onClick = { importIdentity.launch(arrayOf("application/zip", "*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.restore_identity_short))
            }
        }
        Text(
            stringResource(R.string.identity_backup_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        identityMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = { showLogs = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.recent_logs))
        }
        OutlinedButton(
            onClick = {
                reportComment = presetServiceReportComment(appVersionName(context))
                reportCode = null
                reportError = null
                reportSending = false
                showReportDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.send_service_report))
        }
        if (showReportDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!reportSending) showReportDialog = false
                },
                title = { Text(stringResource(R.string.send_service_report_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.send_service_report_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val code = reportCode
                        if (code != null) {
                            Text(stringResource(R.string.send_service_report_code, code))
                        } else {
                            OutlinedTextField(
                                value = reportComment,
                                onValueChange = { reportComment = it },
                                enabled = !reportSending,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                            )
                            reportError?.let { error ->
                                Text(
                                    stringResource(R.string.send_service_report_failed, error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (reportSending) {
                                Text(
                                    stringResource(R.string.send_service_report_sending),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    val code = reportCode
                    if (code != null) {
                        TextButton(onClick = { copyToClipboard(context, code) }) {
                            Text(stringResource(R.string.copy_report_code))
                        }
                    } else {
                        TextButton(
                            enabled = !reportSending,
                            onClick = {
                                reportSending = true
                                reportError = null
                                val comment = reportComment
                                reportScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        ServiceReport.upload(context, comment)
                                    }
                                    reportSending = false
                                    result.fold(
                                        onSuccess = { reportCode = it },
                                        onFailure = { error ->
                                            reportError = error.message ?: error.toString()
                                        },
                                    )
                                }
                            },
                        ) {
                            Text(stringResource(R.string.send_service_report_send))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !reportSending,
                        onClick = { showReportDialog = false },
                    ) {
                        Text(stringResource(R.string.config_close))
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.diagnostics_json), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { copyToClipboard(context, snapshot) }) {
                Text(stringResource(R.string.copy_json))
            }
        }
        ScrollableMonospaceBox(
            text = snapshot,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun ScrollableMonospaceBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp)
                    .verticalScroll(scroll)
                    .padding(8.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(8.dp)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                val trackPx = with(density) { maxHeight.toPx() }
                val canScroll = scroll.maxValue > 0
                val visibleFraction = if (!canScroll) {
                    1f
                } else {
                    (scroll.viewportSize.toFloat() /
                        (scroll.viewportSize + scroll.maxValue).toFloat())
                        .coerceIn(0.12f, 1f)
                }
                val thumbPx = (trackPx * visibleFraction).coerceAtLeast(with(density) { 24.dp.toPx() })
                val yPx = if (!canScroll) {
                    0f
                } else {
                    scroll.value.toFloat() / scroll.maxValue.toFloat() * (trackPx - thumbPx)
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, yPx.toInt()) }
                        .width(3.dp)
                        .height(with(density) { thumbPx.toDp() })
                        .background(
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}
