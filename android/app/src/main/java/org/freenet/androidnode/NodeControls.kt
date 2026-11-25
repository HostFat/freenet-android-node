package org.freenet.androidnode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

@Composable
internal fun NodeControlStrip(
    state: NodeUiState,
    lastUpEpochMs: Long,
    resetMessage: String? = null,
    onStartNetwork: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    crashReportPending: Boolean = false,
    onSendCrashLog: () -> Unit = {},
    onDismissCrashLog: () -> Unit = {},
    onCopyFingerprint: (String) -> Unit,
    onUseSavedUdp: () -> Unit,
    onUseRandomUdp: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val status = networkStatusLabel(
            state.state,
            state.mode,
            state.peers,
            state.serviceActive,
            state.natHint,
        )
        Text(
            if (status == "Connected") {
                stringResource(R.string.node_status_connected, state.peers)
            } else {
                stringResource(R.string.node_status_label, status)
            },
        )
        if (status == "Connected") {
            Text(
                stringResource(R.string.peer_count_accuracy_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.last_up, formatLastUp(System.currentTimeMillis(), lastUpEpochMs)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.serviceActive && (state.bytesSent > 0 || state.bytesReceived > 0)) {
            Text(
                stringResource(
                    R.string.traffic_line,
                    formatTrafficBytes(state.bytesSent),
                    formatTrafficBytes(state.bytesReceived),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.identityFingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    fingerprint,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onCopyFingerprint(fingerprint) }) {
                    Text(stringResource(R.string.copy_fingerprint))
                }
            }
        }
        if (state.mode == NodeMode.Network && state.udpPort != null && state.udpPort > 0) {
            Text(
                stringResource(R.string.udp_port_display, state.udpPort),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.natHint?.let { hint ->
            Text(
                hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.udpPortInUse) {
            Text(
                stringResource(R.string.udp_port_in_use),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onUseSavedUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_saved))
                }
                OutlinedButton(
                    onClick = onUseRandomUdp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_random))
                }
            }
        } else if (
            state.lastNetworkError != null &&
            menuBlockReason(state.state, state.detail, state.lastNetworkError, state.udpPortInUse) == null
        ) {
            Text(
                text = state.lastNetworkError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (crashReportPending) {
            Button(
                onClick = onSendCrashLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.send_crash_log))
            }
            TextButton(
                onClick = onDismissCrashLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dismiss_crash_log))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.serviceActive) {
                Button(
                    enabled = NodeBackend.isAvailable() && state.state != NodePhase.Stopping,
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.state == NodePhase.Stopping) {
                            stringResource(R.string.stopping_node)
                        } else {
                            stringResource(R.string.stop_node)
                        },
                    )
                }
            } else {
                Button(
                    enabled = NodeBackend.isAvailable(),
                    onClick = onStartNetwork,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.start_network_node))
                }
            }
        }
        if (state.state.isRunning) {
            OutlinedButton(
                enabled = NodeBackend.isAvailable(),
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.restart_node))
            }
        }
        resetMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (showRiverChatInvite(state.state, state.mode)) {
            Text(
                text = stringResource(R.string.river_chat_invite_running),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = linkedUrlText(
                    stringResource(R.string.river_chat_invite_get, RIVER_CHAT_INVITE_URL),
                    RIVER_CHAT_INVITE_URL,
                    MaterialTheme.colorScheme.primary,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun linkedUrlText(full: String, url: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        val start = full.indexOf(url)
        if (start < 0) {
            append(full)
            return@buildAnnotatedString
        }
        append(full.substring(0, start))
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(url)
        }
        append(full.substring(start + url.length))
    }

@Composable
internal fun IntStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    valueLabel: String,
    onChange: (Int) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onChange(value - 1) },
            enabled = value > min,
        ) {
            Text("−")
        }
        Text(
            valueLabel,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(
            onClick = { onChange(value + 1) },
            enabled = value < max,
        ) {
            Text("+")
        }
    }
}

@Composable
internal fun PowerChoiceRow(
    selected: NodePowerPolicy,
    onSelect: (NodePowerPolicy) -> Unit,
) {
    val options = NodePowerPolicy.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy((-1).dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = selected == option
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isSelected) {
                        Text("✓", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        option.shortLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T> CompactChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
                label = {
                    Text(
                        label(option),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
internal fun PolicyControls(
    policies: NodePolicyState,
    onPowerPolicy: (NodePowerPolicy) -> Unit,
    onNetworkDataPolicy: (NetworkDataPolicy) -> Unit,
    onScheduleEveryHours: (Int) -> Unit,
    onScheduleOnMinutes: (Int) -> Unit,
    scheduleWaiting: Boolean,
    onConnectNow: () -> Unit,
    startOnBoot: Boolean,
    onStartOnBoot: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.node_runs_when), style = MaterialTheme.typography.titleMedium)
        PowerChoiceRow(
            selected = policies.power,
            onSelect = onPowerPolicy,
        )
        Text(
            stringResource(R.string.node_runs_when_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        if (policies.power == NodePowerPolicy.Schedule) {
            IntStepper(
                label = stringResource(R.string.schedule_wait_label),
                value = policies.scheduleEveryHours,
                min = ConnectionSchedule.MinHours,
                max = ConnectionSchedule.MaxHours,
                valueLabel = hourLabel(policies.scheduleEveryHours),
                onChange = onScheduleEveryHours,
            )
            IntStepper(
                label = stringResource(R.string.schedule_on_label),
                value = policies.scheduleOnMinutes,
                min = ConnectionSchedule.MinMinutes,
                max = ConnectionSchedule.MaxMinutes,
                valueLabel = minuteLabel(policies.scheduleOnMinutes),
                onChange = onScheduleOnMinutes,
            )
            Text(
                stringResource(R.string.schedule_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (scheduleWaiting) {
                Button(
                    onClick = onConnectNow,
                    enabled = NodeBackend.isAvailable(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.schedule_connect_now))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = startOnBoot,
                    onValueChange = onStartOnBoot,
                    role = Role.Checkbox,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = startOnBoot, onCheckedChange = null)
            Text(
                stringResource(R.string.start_on_boot),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            stringResource(R.string.start_on_boot_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(stringResource(R.string.network_data), style = MaterialTheme.typography.titleMedium)
        CompactChoiceRow(
            options = NetworkDataPolicy.entries,
            selected = policies.networkData,
            label = { it.shortLabel },
            onSelect = onNetworkDataPolicy,
        )
        Text(
            stringResource(R.string.network_data_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SettingsCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
internal fun ConnectionLimitField(
    value: Int,
    label: String,
    upperBound: Int,
    rangeText: String,
    onChange: (Int) -> Unit,
    ceiling: Int = ConnectionLimits.Ceiling,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val cap = minOf(upperBound, ceiling).coerceAtLeast(ConnectionLimits.Floor)
    LaunchedEffect(value) {
        if (!focused) {
            text = value.toString()
        }
    }

    fun commit() {
        val parsed = text.toIntOrNull()
        val next = (parsed ?: value).coerceIn(ConnectionLimits.Floor, cap)
        text = next.toString()
        if (next != value) {
            onChange(next)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { incoming ->
            val digits = incoming.filter { it.isDigit() }
            if (digits.isEmpty()) {
                text = ""
                return@OutlinedTextField
            }
            val parsed = digits.toLongOrNull() ?: return@OutlinedTextField
            if (parsed <= cap) {
                text = digits
                if (parsed >= ConnectionLimits.Floor) {
                    parsed.toInt().takeIf { it != value }?.let(onChange)
                }
            }
        },
        label = { Text(label) },
        supportingText = { Text(rangeText) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                val nowFocused = focusState.isFocused
                if (focused && !nowFocused) {
                    commit()
                }
                focused = nowFocused
            },
    )
}

@Composable
internal fun BackgroundLimitsPanel(
    snapshot: RestrictionSnapshot,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val needsFix = snapshot.look == RestrictionLook.NeedsFix
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = if (needsFix) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            },
        ) {
            Text(
                stringResource(
                    if (needsFix) R.string.battery_limits_on else R.string.battery_limits_off,
                ),
            )
        }
        Text(
            stringResource(R.string.battery_limits_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(oemTipRes(snapshot.vendor)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (snapshot.xiaomiAutostart != XiaomiAutostartHint.NotXiaomi) {
            Text(
                stringResource(
                    when (snapshot.xiaomiAutostart) {
                        XiaomiAutostartHint.Enabled -> R.string.xiaomi_autostart_enabled
                        XiaomiAutostartHint.Disabled -> R.string.xiaomi_autostart_disabled
                        else -> R.string.xiaomi_autostart_unknown
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.xiaomi_autostart_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onOpenGuide,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.oem_guide))
        }
    }
}

private fun oemTipRes(vendor: OemVendor): Int = when (vendor) {
    OemVendor.Xiaomi -> R.string.oem_tip_xiaomi
    OemVendor.Samsung -> R.string.oem_tip_samsung
    OemVendor.Huawei, OemVendor.Honor -> R.string.oem_tip_huawei
    OemVendor.Oppo, OemVendor.Realme -> R.string.oem_tip_oppo
    OemVendor.Vivo -> R.string.oem_tip_vivo
    OemVendor.OnePlus -> R.string.oem_tip_oneplus
    OemVendor.Asus -> R.string.oem_tip_asus
    OemVendor.Generic -> R.string.oem_tip_generic
}
