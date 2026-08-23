package com.instinctazero.android

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * A deliberately small offline shell.  The WebView has no JavaScript bridge and only receives
 * files packaged under [OfflineWebPolicy.ASSET_PREFIX].  Keeping that policy here (rather than
 * trusting the page's CSP alone) makes an accidental future external link fail closed.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this),
            )
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled") // The bundled Chessground UI needs JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not leave a debugging socket enabled in a release build.
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(false)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ ->
                // Deliberately ignore downloads: this local analysis screen has no external I/O.
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message,
                ): Boolean = false

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
            webViewClient = OfflineWebViewClient()
        }

        setContentView(webView)
        // We intentionally do not restore WebView history/state: the only valid main frame is
        // this exact bundled document.
        webView.loadUrl(OfflineWebPolicy.MAIN_PAGE_URL)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class OfflineWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (OfflineWebPolicy.isAllowedAssetUrl(url)) {
                return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
            }
            return blockedResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !OfflineWebPolicy.isAllowedMainFrameUrl(request.url.toString())
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}

/** Pure URL policy, kept Android-free so it is covered by ordinary JVM tests. */
internal object OfflineWebPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val ASSET_PREFIX = "/assets/analysis/"
    const val MAIN_PAGE_URL = "$ORIGIN${ASSET_PREFIX}index.html"

    fun isAllowedMainFrameUrl(rawUrl: String): Boolean = rawUrl == MAIN_PAGE_URL

    fun isAllowedAssetUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("appassets.androidplatform.net", ignoreCase = true) &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawPath.startsWith(ASSET_PREFIX) &&
            uri.rawPath.length > ASSET_PREFIX.length &&
            '%' !in uri.rawPath &&
            "/../" !in uri.rawPath &&
            "/./" !in uri.rawPath
    } catch (_: Exception) {
        false
    }
}
