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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@Composable
internal fun DashboardPanel(state: NodeUiState, modifier: Modifier = Modifier) {
    val running = state.state.isRunning
    if (running) {
        CoreDashboardWebView(
            reloadKey = "${state.mode}:${state.completedStartCycles}",
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if ((state.state == NodePhase.Starting || state.state == NodePhase.Stopping)) {
                    CircularProgressIndicator()
                }
                val blockReason = menuBlockReason(
                    status = state.state,
                    detail = state.detail,
                    lastNetworkError = state.lastNetworkError,
                    udpPortInUse = state.udpPortInUse,
                )
                Text(blockReason ?: state.detail)
                Text(
                    stringResource(R.string.dashboard_local_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CoreDashboardWebView(reloadKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loading by remember(reloadKey) { mutableStateOf(true) }
    var error by remember(reloadKey) { mutableStateOf<String?>(null) }
    val webView = remember(reloadKey) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = LoopbackDashboardClient(
                onLoading = {
                    loading = true
                    error = null
                },
                onReady = {
                    loading = false
                    error = null
                },
                onError = {
                    loading = false
                    error = it
                },
            )
            loadUrl(DASHBOARD_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        error?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.dashboard_not_ready))
                Text(message, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        loading = true
                        error = null
                        webView.reload()
                    },
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

private class LoopbackDashboardClient(
    private val onLoading: () -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            onLoading()
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            view?.evaluateJavascript(DASHBOARD_SCROLL_LOCK_JS, null)
            onReady()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        if (shouldOpenInExternalBrowser(request.url, request.isForMainFrame, request.hasGesture())) {
            val result = runCatching {
                view?.context?.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    ?: error("WebView context is unavailable")
            }
            if (result.isFailure) {
                onError("No browser is available to open this link")
            }
            return true
        }
        return !isAllowedDashboardUri(request.url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        if (scheme == "http" && !isAllowedDashboardUri(request.url)) {
            return blockedResponse()
        }
        if (scheme == "https" && !isAllowedDashboardSubresource(request.url)) {
            return blockedResponse()
        }
        return null
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            onError(error.description?.toString() ?: "WebView could not load the dashboard")
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked non-loopback request",
        emptyMap(),
        ByteArrayInputStream("Blocked by the Android dashboard allowlist".toByteArray()),
    )
}

private fun isAllowedDashboardUri(value: String): Boolean =
    runCatching { isAllowedDashboardUri(Uri.parse(value)) }.getOrDefault(false)

private fun isAllowedDashboardUri(uri: Uri): Boolean =
    uri.scheme.equals("http", ignoreCase = true) &&
        uri.host == DASHBOARD_HOST &&
        uri.port == DASHBOARD_PORT

private const val DASHBOARD_SCROLL_LOCK_JS = """
(function() {
  if (window.__freenetAndroidScrollLock) return;
  window.__freenetAndroidScrollLock = true;
  var y = window.scrollY || 0;
  var fromUser = true;
  window.addEventListener('scroll', function() {
    if (fromUser) y = window.scrollY || 0;
  }, {passive: true});
  function pin() {
    fromUser = false;
    window.scrollTo(0, y);
    requestAnimationFrame(function() {
      window.scrollTo(0, y);
      fromUser = true;
    });
  }
  var root = document.querySelector('main') || document.documentElement;
  new MutationObserver(function() { pin(); }).observe(root, {childList: true, subtree: true});
})();
"""

private fun isAllowedDashboardSubresource(uri: Uri): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host == DASHBOARD_LOGO_HOST &&
        uri.path == DASHBOARD_LOGO_PATH &&
        (uri.port == -1 || uri.port == 443)

internal fun shouldOpenInExternalBrowser(
    uri: Uri,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
): Boolean {
    if (!isForMainFrame || !hasGesture) return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return !isAllowedDashboardUri(uri) || uri.path?.startsWith(HOSTED_APP_PATH_PREFIX) == true
}

private const val DASHBOARD_HOST = "127.0.0.1"
private const val DASHBOARD_PORT = 7509
internal const val DASHBOARD_URL = "http://$DASHBOARD_HOST:$DASHBOARD_PORT/"
private const val DASHBOARD_LOGO_HOST = "freenet.org"
private const val DASHBOARD_LOGO_PATH = "/freenet_logo.svg"
private const val HOSTED_APP_PATH_PREFIX = "/v1/contract/web/"
