package org.freenet.androidnode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    var askSite by remember { mutableStateOf<FreenetSite?>(null) }
    var openWith by remember { mutableStateOf(FreenetSites.openWith(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newViewer by remember { mutableStateOf(SiteViewer.INHERIT) }
    val listState = rememberLazyListState()

    val openInApp: (FreenetSite) -> Unit = { site ->
        context.startActivity(SiteActivity.intent(context, site))
    }
    val openInBrowser: (FreenetSite) -> Unit = { site ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
    }
    val handleOpen: (FreenetSite) -> Unit = { site ->
        if (!site.url.startsWith(LOOPBACK_PREFIX)) {
            openInBrowser(site)
        } else {
            val viewer = when {
                site.viewer == SiteViewer.APP -> SiteViewer.APP
                site.viewer == SiteViewer.BROWSER -> SiteViewer.BROWSER
                openWith == OpenWith.APP -> SiteViewer.APP
                openWith == OpenWith.BROWSER -> SiteViewer.BROWSER
                else -> null
            }
            when (viewer) {
                SiteViewer.APP -> openInApp(site)
                SiteViewer.BROWSER -> openInBrowser(site)
                else -> askSite = site
            }
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
                handleOpen(site)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.open_with),
                style = MaterialTheme.typography.bodySmall,
            )
            FilterChip(
                selected = openWith == OpenWith.APP,
                onClick = {
                    openWith = OpenWith.APP
                    FreenetSites.setOpenWith(context, OpenWith.APP)
                },
                label = { Text(stringResource(R.string.open_in_app)) },
            )
            FilterChip(
                selected = openWith == OpenWith.BROWSER,
                onClick = {
                    openWith = OpenWith.BROWSER
                    FreenetSites.setOpenWith(context, OpenWith.BROWSER)
                },
                label = { Text(stringResource(R.string.open_in_browser)) },
            )
            FilterChip(
                selected = openWith == OpenWith.ASK,
                onClick = {
                    openWith = OpenWith.ASK
                    FreenetSites.setOpenWith(context, OpenWith.ASK)
                },
                label = { Text(stringResource(R.string.open_ask)) },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                itemsIndexed(sites) { index, site ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!site.url.startsWith(LOOPBACK_PREFIX)) {
                                    openInBrowser(site)
                                } else if (!nodeConnected) {
                                    pendingSite = site
                                } else {
                                    handleOpen(site)
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
                                newViewer = site.viewer
                            },
                        ) {
                            Text(stringResource(R.string.edit_site))
                        }
                        TextButton(
                            onClick = { sites = FreenetSites.remove(context, site) },
                        ) {
                            Text(stringResource(R.string.remove_site))
                        }
                    }
                }
            }
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val visibleItems = layoutInfo.visibleItemsInfo.size
            if (totalItems > visibleItems && visibleItems > 0) {
                val thumbFraction = (visibleItems.toFloat() / totalItems).coerceIn(0.08f, 1f)
                val scrollFraction = (
                    listState.firstVisibleItemIndex.toFloat() /
                        (totalItems - visibleItems).coerceAtLeast(1)
                    ).coerceIn(0f, 1f)
                val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(6.dp)
                        .padding(vertical = 2.dp),
                ) {
                    val thumbHeight = (size.height * thumbFraction)
                        .coerceIn(20.dp.toPx(), size.height)
                    val offset = (size.height - thumbHeight) * scrollFraction
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(size.width - 3.dp.toPx(), offset),
                        size = Size(3.dp.toPx(), thumbHeight),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    newName = ""
                    newUrl = ""
                    newViewer = SiteViewer.INHERIT
                    showAdd = true
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.add_site))
            }
            OutlinedButton(
                onClick = { sites = FreenetSites.restoreDefaults(context) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.restore_defaults))
            }
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

    askSite?.let { site ->
        var rememberChoice by remember(site) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askSite = null },
            title = { Text(stringResource(R.string.open_with_site)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.open_site_message, site.name))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberChoice,
                            onCheckedChange = { rememberChoice = it },
                        )
                        Text(stringResource(R.string.remember_choice))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askSite = null
                        if (rememberChoice) {
                            sites = FreenetSites.setViewer(context, site, SiteViewer.APP)
                        }
                        openInApp(site)
                    },
                ) {
                    Text(stringResource(R.string.open_in_app))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askSite = null
                        if (rememberChoice) {
                            sites = FreenetSites.setViewer(context, site, SiteViewer.BROWSER)
                        }
                        openInBrowser(site)
                    },
                ) {
                    Text(stringResource(R.string.open_in_browser))
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
                newViewer = SiteViewer.INHERIT
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
                    Text(
                        stringResource(R.string.open_with_site),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newViewer == SiteViewer.INHERIT,
                            onClick = { newViewer = SiteViewer.INHERIT },
                            label = { Text(stringResource(R.string.site_viewer_default)) },
                        )
                        FilterChip(
                            selected = newViewer == SiteViewer.APP,
                            onClick = { newViewer = SiteViewer.APP },
                            label = { Text(stringResource(R.string.open_in_app)) },
                        )
                        FilterChip(
                            selected = newViewer == SiteViewer.BROWSER,
                            onClick = { newViewer = SiteViewer.BROWSER },
                            label = { Text(stringResource(R.string.open_in_browser)) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newUrl.isNotBlank(),
                    onClick = {
                        sites = if (editingIndex != null) {
                            FreenetSites.update(
                                context,
                                editingIndex,
                                newName,
                                newUrl,
                                newViewer,
                            )
                        } else {
                            FreenetSites.add(context, newName, newUrl, newViewer)
                        }
                        showAdd = false
                        editIndex = null
                        newName = ""
                        newUrl = ""
                        newViewer = SiteViewer.INHERIT
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
                        newViewer = SiteViewer.INHERIT
                    },
                ) {
                    Text(stringResource(R.string.config_close))
                }
            },
        )
    }
}
