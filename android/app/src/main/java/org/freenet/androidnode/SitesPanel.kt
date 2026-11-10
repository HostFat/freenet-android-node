package org.freenet.androidnode

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val LOOPBACK_PREFIX = "http://127.0.0.1:7509"
private val CONTRACT_ROOT = Regex(
    """^(http://127\.0\.0\.1:7509/v1/contract/web/[^/]+/)""",
)

@Composable
internal fun SitesPanel(
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    initialSites: List<FreenetSite>? = null,
) {
    val context = LocalContext.current
    var sites by remember { mutableStateOf(initialSites ?: FreenetSites.load(context)) }
    var openSite by remember { mutableStateOf<FreenetSite?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    openSite?.let { site ->
        FreenetSiteView(
            site = site,
            modifier = modifier,
            onClose = { openSite = null },
            onAdd = { name, url -> sites = FreenetSites.add(context, name, url) },
        )
        return
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
                Text(stringResource(R.string.config_close))
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
                            if (site.url.startsWith(LOOPBACK_PREFIX)) {
                                openSite = site
                            } else {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(site.url)),
                                )
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

@Composable
private fun FreenetSiteView(
    site: FreenetSite,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var currentUrl by remember(site) { mutableStateOf(site.url) }
    var pageTitle by remember(site) { mutableStateOf(site.name) }
    var pageLoaded by remember(site) { mutableStateOf(false) }
    val contractRoot = CONTRACT_ROOT.find(currentUrl)?.groupValues?.getOrNull(1)

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
            Text(pageTitle, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onClose) {
                Text(stringResource(R.string.config_close))
            }
        }
        if (contractRoot != null && pageLoaded) {
            Button(
                onClick = { onAdd(pageTitle, contractRoot) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_this_site))
            }
        }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        settings.safeBrowsingEnabled = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) currentUrl = url
                            pageLoaded = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) currentUrl = url
                            pageLoaded = true
                            view?.evaluateJavascript("document.title") { raw ->
                                val title = raw?.trim('"')
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            if (uri.scheme == "http" &&
                                uri.host == "127.0.0.1" &&
                                uri.port == 7509
                            ) {
                                return false
                            }
                            runCatching {
                                view?.context?.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri),
                                )
                            }
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val uri = request.url
                            val loopback = uri.scheme == "http" &&
                                uri.host == "127.0.0.1" &&
                                uri.port == 7509
                            return if (loopback) {
                                null
                            } else {
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) pageLoaded = false
                        }
                    }
                    loadUrl(site.url)
                }
            },
        )
    }
}
