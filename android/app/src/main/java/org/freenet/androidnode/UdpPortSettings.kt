package org.freenet.androidnode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun UdpPortSettings(
    policies: NodePolicyState,
    udpPortInUse: Boolean,
    onSave: (UdpPortMode, Int) -> Unit,
    onUseSaved: () -> Unit,
    onUseRandom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var draftMode by remember { mutableStateOf(policies.udpPortMode) }
    var draftPort by remember { mutableStateOf(policies.udpPort) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(policies.udpPortMode, policies.udpPort) {
        draftMode = policies.udpPortMode
        draftPort = policies.udpPort
    }
    val dirty = udpPortSettingsAreDirty(
        draftMode,
        draftPort,
        policies.udpPortMode,
        policies.udpPort,
    )
    val savedPort = ConfigToml.parseInt(ConfigToml.read(context), ConfigToml.NETWORK_PORT_KEY)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.udp_port), style = MaterialTheme.typography.titleMedium)
        CompactChoiceRow(
            options = UdpPortMode.entries,
            selected = draftMode,
            label = { it.shortLabel },
            onSelect = { draftMode = it },
        )
        Text(
            stringResource(
                when (draftMode) {
                    UdpPortMode.Saved -> R.string.udp_port_saved_hint
                    UdpPortMode.Custom -> R.string.udp_port_custom_hint
                    UdpPortMode.Random -> R.string.udp_port_random_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (draftMode == UdpPortMode.Saved && savedPort != null) {
            Text(
                stringResource(R.string.udp_port_current, savedPort),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (draftMode == UdpPortMode.Custom) {
            ConnectionLimitField(
                value = draftPort,
                label = stringResource(R.string.udp_port),
                upperBound = UdpPorts.Ceiling,
                rangeText = stringResource(R.string.udp_port_range),
                ceiling = UdpPorts.Ceiling,
                onChange = { draftPort = UdpPorts.coerce(it) },
            )
        }
        Button(
            onClick = {
                focusManager.clearFocus()
                onSave(draftMode, draftPort)
            },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.save_udp_port))
        }
        if (udpPortInUse) {
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
                    onClick = onUseSaved,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_saved))
                }
                OutlinedButton(
                    onClick = onUseRandom,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.udp_use_random))
                }
            }
        }
    }
}
