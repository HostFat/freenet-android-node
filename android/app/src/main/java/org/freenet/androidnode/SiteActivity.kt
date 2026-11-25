package org.freenet.androidnode

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import org.json.JSONArray
import java.io.ByteArrayInputStream

/**
 * Shows one Freenet contract in a WebView. A dedicated Activity keeps the
 * WebView out of Compose interop and restricts navigation to the local node.
 */
class SiteActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private var currentUrl = ""
    private var pageTitle = ""
    private var pageLoaded = false
    private var canAddSite = false
    private var savedUrls: Set<String> = emptySet()
    private var popup: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site)
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        val initialUri = runCatching { Uri.parse(url) }.getOrNull()
        if (initialUri == null || !isAllowedLoopbackUri(initialUri)) {
            finish()
            return
        }
        pageTitle = intent.getStringExtra(EXTRA_NAME).orEmpty()
        currentUrl = url

        titleView = findViewById(R.id.site_title)
        statusView = findViewById(R.id.site_status)
        backButton = findViewById(R.id.site_back)
        forwardButton = findViewById(R.id.site_forward)
        savedUrls = FreenetSites.load(this).map { it.url.trimEnd('/') }.toSet()
        titleView.text = pageTitle
        titleView.setOnClickListener { showFullTitle() }
        backButton.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        forwardButton.setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<Button>(R.id.site_refresh).setOnClickListener {
            if (webView.url == null) {
                webView.loadUrl(currentUrl)
            } else {
                webView.reload()
            }
        }
        findViewById<Button>(R.id.site_menu).setOnClickListener { button ->
            val menu = PopupMenu(this, button)
            if (canAddSite) {
                menu.menu.add(0, MENU_ADD, 0, getString(R.string.add_this_site))
            }
            menu.menu.add(0, MENU_BROWSER, 1, getString(R.string.open_in_browser))
            menu.menu.add(0, MENU_CLOSE, 2, getString(R.string.config_close))
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ADD -> addCurrentSite()
                    MENU_BROWSER -> {
                        val target = runCatching { Uri.parse(webView.url ?: currentUrl) }.getOrNull()
                        if (target != null && isHttpOrHttps(target)) {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                        }
                    }
                    MENU_CLOSE -> finish()
                }
                true
            }
            menu.show()
        }

        webView = findViewById(R.id.site_webview)
        configureWebView()
        updateHistoryButtons()
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (isAllowedLoopbackUri(uri)) {
                    return false
                }
                if (request.hasGesture() && isHttpOrHttps(uri)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                val network = scheme == "http" || scheme == "https"
                return if (!network || isAllowedLoopbackUri(uri)) {
                    null
                } else {
                    blockedResponse()
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null) currentUrl = url
                pageLoaded = false
                statusView.visibility = View.GONE
                updateSiteActions()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateHistoryButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null) currentUrl = url
                pageLoaded = true
                view.evaluateJavascript("document.title") { raw ->
                    val title = decodeJsStringResult(raw)
                    if (!title.isNullOrBlank()) {
                        pageTitle = title
                        titleView.text = title
                    }
                    updateSiteActions()
                }
                updateSiteActions()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    pageLoaded = false
                    statusView.text = getString(
                        R.string.site_load_error,
                        "${error.errorCode}: ${error.description}",
                        currentUrl,
                    )
                    statusView.visibility = View.VISIBLE
                    updateSiteActions()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                this@SiteActivity.popup?.destroy()
                val popupView = WebView(this@SiteActivity)
                this@SiteActivity.popup = popupView
                popupView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        childView: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val uri = request.url
                        when {
                            isAllowedLoopbackUri(uri) -> view.loadUrl(uri.toString())
                            isHttpOrHttps(uri) -> runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        }
                        popupView.destroy()
                        if (this@SiteActivity.popup === popupView) {
                            this@SiteActivity.popup = null
                        }
                        return true
                    }

                    override fun shouldInterceptRequest(
                        childView: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = blockedResponse()
                }
                transport.webView = popupView
                resultMsg.sendToTarget()
                return true
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, message.message())
                }
                return true
            }
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun updateSiteActions() {
        updateHistoryButtons()
        val root = contractRoot(currentUrl)
        canAddSite = root != null && pageLoaded && root.trimEnd('/') !in savedUrls
    }

    private fun updateHistoryButtons() {
        if (!::webView.isInitialized) return
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
        backButton.alpha = if (backButton.isEnabled) 1f else 0.35f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.35f
    }

    private fun addCurrentSite() {
        val root = contractRoot(currentUrl) ?: return
        FreenetSites.add(this, pageTitle.ifBlank { root }, root)
        savedUrls = savedUrls + root.trimEnd('/')
        updateSiteActions()
    }

    private fun showFullTitle() {
        val title = pageTitle.ifBlank { titleView.text?.toString().orEmpty() }
        if (title.isBlank()) return
        AlertDialog.Builder(this)
            .setMessage(title)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun contractRoot(url: String): String? =
        CONTRACT_ROOT.find(url)?.groupValues?.getOrNull(1)

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        popup?.destroy()
        popup = null
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FreenetSite"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private const val MENU_ADD = 0
        private const val MENU_BROWSER = 1
        private const val MENU_CLOSE = 2
        private val CONTRACT_ROOT = Regex(
            """^(http://127\.0\.0\.1:7509/v1/contract/web/[^/]+/)""",
        )

        internal fun isAllowedLoopbackUri(uri: Uri): Boolean =
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host == "127.0.0.1" &&
                uri.port == 7509

        internal fun isHttpOrHttps(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase()
            return scheme == "http" || scheme == "https"
        }

        internal fun decodeJsStringResult(raw: String?): String? {
            if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return null
            return runCatching {
                val arr = JSONArray("[$raw]")
                if (arr.isNull(0)) null else arr.optString(0)
            }.getOrElse { raw.trim('"') }
        }

        internal fun intent(context: Context, site: FreenetSite): Intent =
            Intent(context, SiteActivity::class.java)
                .putExtra(EXTRA_URL, site.url)
                .putExtra(EXTRA_NAME, site.name)
    }
}
