package org.freenet.androidnode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private const val LOOPBACK_PREFIX = "http://127.0.0.1:7509"

@Composable
internal fun SitesPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    initialSites: List<FreenetSite>? = null,
    nodeConnected: Boolean = true,
    onStartNode: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var sites by remember { mutableStateOf(initialSites ?: FreenetSites.load(context)) }
    var pendingSite by remember { mutableStateOf<FreenetSite?>(null) }
    var openWhenConnected by remember { mutableStateOf<FreenetSite?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    val open: (FreenetSite) -> Unit = { site ->
        if (site.url.startsWith(LOOPBACK_PREFIX)) {
            context.startActivity(SiteActivity.intent(context, site))
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sites = FreenetSites.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(nodeConnected) {
        if (nodeConnected) {
            openWhenConnected?.let { site ->
                openWhenConnected = null
                open(site)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.freenet_sites),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.close_dashboard))
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(sites) { index, site ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!site.url.startsWith(LOOPBACK_PREFIX) || nodeConnected) {
                                open(site)
                            } else {
                                pendingSite = site
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            site.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            editIndex = index
                            newName = site.name
                            newUrl = site.url
                        },
                    ) {
                        Text(stringResource(R.string.edit_site))
                    }
                    if (!site.predefined) {
                        TextButton(
                            onClick = { sites = FreenetSites.remove(context, site) },
                        ) {
                            Text(stringResource(R.string.remove_site))
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                newName = ""
                newUrl = ""
                showAdd = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.add_site))
        }
    }

    pendingSite?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingSite = null },
            title = { Text(stringResource(R.string.node_required_title)) },
            text = { Text(stringResource(R.string.node_required_message, site.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSite = null
                        openWhenConnected = site
                        onStartNode()
                    },
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSite = null }) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }

    val editingIndex = editIndex
    if (showAdd || editingIndex != null) {
        AlertDialog(
            onDismissRequest = {
                showAdd = false
                editIndex = null
                newName = ""
                newUrl = ""
            },
            title = {
                Text(
                    stringResource(
                        if (editingIndex != null) R.string.edit_site_title else R.string.add_site,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.site_name)) },
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text(stringResource(R.string.site_url)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newUrl.isNotBlank(),
                    onClick = {
                        sites = if (editingIndex != null) {
                            FreenetSites.update(context, editingIndex, newName, newUrl)
                        } else {
                            FreenetSites.add(context, newName, newUrl)
                        }
                        showAdd = false
                        editIndex = null
                        newName = ""
                        newUrl = ""
                    },
                ) {
                    Text(
                        stringResource(
                            if (editingIndex != null) {
                                R.string.config_save
                            } else {
                                R.string.add_site_confirm
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdd = false
                        editIndex = null
                        newName = ""
                        newUrl = ""
                    },
                ) {
                    Text(stringResource(R.string.config_close))
                }
            },
        )
    }
}
