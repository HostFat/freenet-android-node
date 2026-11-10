package org.freenet.androidnode

import android.annotation.SuppressLint
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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import java.io.ByteArrayInputStream

/**
 * Shows one Freenet contract in a WebView. A dedicated Activity mirrors the
 * working Freenet mobile app and keeps the WebView out of Compose interop.
 */
class SiteActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var addButton: Button
    private var currentUrl = ""
    private var pageTitle = ""
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site)
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        pageTitle = intent.getStringExtra(EXTRA_NAME).orEmpty()
        currentUrl = url

        titleView = findViewById(R.id.site_title)
        statusView = findViewById(R.id.site_status)
        addButton = findViewById(R.id.site_add)
        titleView.text = pageTitle
        findViewById<Button>(R.id.site_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.site_browser).setOnClickListener {
            val url = webView.url ?: currentUrl
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        addButton.setOnClickListener {
            val root = contractRoot(currentUrl) ?: return@setOnClickListener
            FreenetSites.add(this, pageTitle.ifBlank { root }, root)
            updateAddButton()
        }

        webView = findViewById(R.id.site_webview)
        configureWebView()
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
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 7509) {
                    return false
                }
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val uri = request.url
                val network = uri.scheme == "http" || uri.scheme == "https"
                val loopback = uri.scheme == "http" &&
                    uri.host == "127.0.0.1" &&
                    uri.port == 7509
                return if (!network || loopback) {
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

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null) currentUrl = url
                pageLoaded = false
                statusView.visibility = View.GONE
                updateAddButton()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null) currentUrl = url
                pageLoaded = true
                view.evaluateJavascript("document.title") { raw ->
                    val title = raw?.trim('"')
                    if (!title.isNullOrBlank()) {
                        pageTitle = title
                        titleView.text = title
                    }
                    updateAddButton()
                }
                updateAddButton()
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
                    updateAddButton()
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
                val transport = resultMsg.obj as WebView.WebViewTransport
                val popup = WebView(this@SiteActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popupView: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        view.loadUrl(request.url.toString())
                        popup.destroy()
                        return true
                    }
                }
                transport.webView = popup
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

    private fun updateAddButton() {
        val root = contractRoot(currentUrl)
        val saved = root != null && FreenetSites.load(this).any {
            it.url.trimEnd('/') == root.trimEnd('/')
        }
        addButton.visibility = if (root != null && pageLoaded && !saved) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun contractRoot(url: String): String? =
        CONTRACT_ROOT.find(url)?.groupValues?.getOrNull(1)

    companion object {
        private const val TAG = "FreenetSite"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
        private val CONTRACT_ROOT = Regex(
            """^(http://127\.0\.0\.1:7509/v1/contract/web/[^/]+/)""",
        )

        internal fun intent(context: Context, site: FreenetSite): Intent =
            Intent(context, SiteActivity::class.java)
                .putExtra(EXTRA_URL, site.url)
                .putExtra(EXTRA_NAME, site.name)
    }
}
