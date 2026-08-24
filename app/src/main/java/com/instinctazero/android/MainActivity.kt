package com.instinctazero.android

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local WebView shell for the legacy analysis UI. Web content is always packaged in the APK and
 * cannot make network requests. Its small, documented JavaScript bridge delegates only analysis
 * work to [NativeAnalysisBridge], which holds the paired-device bearer token in encrypted Android
 * storage and speaks only to the build-configured HTTPS gateway. The server selects its fixed,
 * exact-SYCL BT4 profile; profile choice is intentionally not part of the phone protocol.
 */
class MainActivity : ComponentActivity() {
    private lateinit var shellRoot: FrameLayout
    private lateinit var nativeLayer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var nativeBridge: NativeAnalysisBridge
    private val navigation = ShellNavigation()
    private val pairingCode = PairingCodeBuffer()
    private var webPageLoaded = false
    private var connectionMessage: String? = null
    private var archiveGames = JSONArray()
    private var archiveAccount = ""
    private var archiveLoading = false
    private var archiveMessage: String? = null
    private var pendingArchivedGame: JSONObject? = null

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled") // The bundled Chessground UI needs JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(false)
        nativeBridge = NativeAnalysisBridge(this)
        nativeBridge.cachedArchive()?.let(::applyArchivePayload)

        shellRoot = FrameLayout(this).apply { setBackgroundColor(SHELL_BACKGROUND) }
        nativeLayer = FrameLayout(this).apply { setBackgroundColor(SHELL_BACKGROUND) }

        webView = WebView(this).apply {
            // WebView Force Dark can recolour the legacy SVG piece sprites. The app theme also
            // opts out, but this guards WebView providers independently on API 29+.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            setBackgroundColor(0xff2b2b2b.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                // Only NativeAnalysisBridge may use the network.
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mediaPlaybackRequiresUserGesture = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> /* No downloads from the analysis shell. */ }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message,
                ): Boolean = false

                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            webViewClient = LocalAnalysisWebViewClient()
            // This object is only attached after all non-asset navigation has been blocked. No
            // token-returning method exists, and all callbacks are JSON-quoted before injection.
            addJavascriptInterface(nativeBridge, NativeAnalysisBridge.JS_OBJECT)
            visibility = View.GONE
        }
        nativeBridge.attachWebView(webView)

        shellRoot.addView(webView, matchFrame())
        shellRoot.addView(nativeLayer, matchFrame())
        setContentView(shellRoot)
        renderNativeScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleShellBack()
        })
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            if (navigation.screen == ShellScreen.ANALYSIS) setAnalysisActive(true)
            else {
                renderNativeScreen()
                if (navigation.screen == ShellScreen.HOME) refreshArchive()
            }
        }
    }

    override fun onPause() {
        // Closing the native calls closes the gateway response body and cancels the SSE search.
        if (::webView.isInitialized) setAnalysisActive(false)
        if (::nativeBridge.isInitialized) nativeBridge.cancelAll("backgrounded")
        if (pairingCode.busy) connectionMessage = null
        pairingCode.finishPair()
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::nativeBridge.isInitialized) nativeBridge.close()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface(NativeAnalysisBridge.JS_OBJECT)
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    internal fun showHomeScreen() {
        cancelPairingIfActive("left profile")
        navigation.showHome()
        setAnalysisActive(false)
        nativeBridge.cancelAll("left analysis")
        webView.visibility = View.GONE
        nativeLayer.visibility = View.VISIBLE
        renderNativeScreen()
        refreshArchive()
    }

    internal fun onBridgeConnectionState(payload: JSONObject) {
        connectionMessage = payload.optString("error").ifBlank { null }
        pairingCode.finishPair()
        if (payload.optBoolean("paired")) {
            pairingCode.clear()
            navigation.closeKeypad()
            refreshArchive()
        } else {
            archiveGames = JSONArray()
            archiveAccount = ""
        }
        if (navigation.screen != ShellScreen.ANALYSIS) renderNativeScreen()
    }

    private fun showProfileScreen() {
        navigation.showProfile()
        setAnalysisActive(false)
        nativeBridge.cancelAll("opened profile")
        webView.visibility = View.GONE
        nativeLayer.visibility = View.VISIBLE
        renderNativeScreen()
    }

    private fun showAnalysisScreen() {
        cancelPairingIfActive("left profile")
        navigation.showAnalysis()
        nativeLayer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        if (!webPageLoaded) webView.loadUrl(AnalysisWebPolicy.MAIN_PAGE_URL) else setAnalysisActive(true)
    }

    private fun refreshArchive() {
        if (archiveLoading || navigation.screen == ShellScreen.ANALYSIS || !nativeBridge.isPaired()) return
        archiveLoading = true
        archiveMessage = null
        if (::nativeLayer.isInitialized) renderNativeScreen()
        nativeBridge.refreshArchive()
    }

    internal fun onArchivePayload(payload: JSONObject?, error: String?) {
        archiveLoading = false
        archiveMessage = error
        payload?.let(::applyArchivePayload)
        if (navigation.screen == ShellScreen.HOME) renderNativeScreen()
    }

    private fun applyArchivePayload(payload: JSONObject) {
        archiveGames = payload.optJSONArray("games") ?: JSONArray()
        archiveAccount = payload.optString("account")
    }

    internal fun onArchivedGame(payload: JSONObject?, error: String?) {
        archiveMessage = error
        if (payload != null) {
            val game = payload.optJSONObject("game") ?: payload
            val account = archiveAccount.lowercase(Locale.ROOT)
            val white = playerName(game.optJSONObject("white")).lowercase(Locale.ROOT)
            game.put("mobile_orientation", if (account.isNotBlank() && account == white) "white" else "black")
            pendingArchivedGame = JSONObject().put("game", game)
            showAnalysisScreen()
            deliverPendingArchivedGame()
        } else if (navigation.screen == ShellScreen.HOME) renderNativeScreen()
    }

    private fun deliverPendingArchivedGame() {
        val payload = pendingArchivedGame ?: return
        if (!webPageLoaded) return
        pendingArchivedGame = null
        webView.evaluateJavascript(
            "window.InstinctaZero&&window.InstinctaZero.loadArchivedGame&&window.InstinctaZero.loadArchivedGame(${JSONObject.quote(payload.toString())});void 0;",
            null,
        )
        setAnalysisActive(true)
    }

    private fun setAnalysisActive(active: Boolean) {
        if (!webPageLoaded) return
        webView.evaluateJavascript(
            "window.InstinctaZero&&window.InstinctaZero.setAnalysisActive&&window.InstinctaZero.setAnalysisActive(${if (active) "true" else "false"});void 0;",
            null,
        )
    }

    private fun cancelPairingIfActive(reason: String) {
        if (!pairingCode.busy) return
        nativeBridge.cancelAll(reason)
        pairingCode.finishPair()
        connectionMessage = null
    }

    private fun handleShellBack() {
        val wasBusyKeypad = navigation.keypadOpen && pairingCode.busy
        when (navigation.onBack()) {
            ShellBackAction.RENDER_NATIVE -> {
                if (wasBusyKeypad) {
                    nativeBridge.cancelAll("pairing dismissed")
                    pairingCode.finishPair()
                    connectionMessage = null
                }
                renderNativeScreen()
            }
            ShellBackAction.EXIT -> finish()
            ShellBackAction.REQUEST_ANALYSIS_BACK -> webView.evaluateJavascript(
                "window.InstinctaZero&&window.InstinctaZero.handleAndroidBack?window.InstinctaZero.handleAndroidBack():false",
            ) { result -> if (result != "true") showHomeScreen() }
        }
    }

    private fun renderNativeScreen() {
        nativeLayer.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHELL_BACKGROUND)
        }
        page.addView(nativeHeader())
        when (navigation.screen) {
            ShellScreen.PROFILE -> page.addView(profileContent(), weighted())
            else -> page.addView(homeContent(), weighted())
        }
        nativeLayer.addView(page, matchFrame())
        if (navigation.drawerOpen) nativeLayer.addView(drawerOverlay(), matchFrame())
    }

    private fun nativeHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 10.dp, 0)
        setBackgroundColor(HEADER_BACKGROUND)
        val leading = shellButton(if (navigation.screen == ShellScreen.PROFILE) "‹" else "☰", 25f).apply {
            contentDescription = if (navigation.screen == ShellScreen.PROFILE) "Back to home" else "Open menu"
            setOnClickListener {
                if (navigation.screen == ShellScreen.PROFILE) showHomeScreen()
                else { navigation.openDrawer(); renderNativeScreen() }
            }
        }
        addView(leading, LinearLayout.LayoutParams(48.dp, 56.dp))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(com.instinctazero.android.R.drawable.instinctazero_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = null
        }, LinearLayout.LayoutParams(32.dp, 32.dp).apply { marginEnd = 8.dp })
        addView(TextView(this@MainActivity).apply {
            text = if (navigation.screen == ShellScreen.PROFILE) "Profile / PC" else "InstinctaZero"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, 56.dp, 1f))
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dp) }

    private fun homeContent(): View {
        val paired = nativeBridge.isPaired()
        if (!paired) return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 24.dp, 18.dp, 18.dp)
            addView(TextView(this@MainActivity).apply {
                text = "Chess analysis"
                setTextColor(Color.WHITE)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "A local study board with Leela lines and opening book. Pair your PC to load completed Lichess games."
                setTextColor(TEXT_MUTED)
                textSize = 15f
                setPadding(0, 6.dp, 0, 20.dp)
            })
            addView(shellCard("Analysis board", "Continue the board where you left it") { showAnalysisScreen() })
            addView(shellCard("Profile / PC", connectionSummary()) { showProfileScreen() }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 12.dp
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 4.dp, 6.dp, 10.dp)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = archiveAccount.ifBlank { "Completed games" }
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(8.dp, 4.dp, 4.dp, 4.dp)
                }, LinearLayout.LayoutParams(0, 44.dp, 1f))
                addView(shellButton(if (archiveLoading) "…" else "↻", 22f).apply {
                    contentDescription = "Refresh completed games"
                    isEnabled = !archiveLoading
                    setOnClickListener { refreshArchive() }
                }, LinearLayout.LayoutParams(48.dp, 44.dp))
            })
            archiveMessage?.let { message ->
                addView(TextView(this@MainActivity).apply {
                    text = message
                    setTextColor(ERROR_TEXT)
                    textSize = 13f
                    setPadding(8.dp, 0, 8.dp, 8.dp)
                })
            }
            if (archiveGames.length() == 0) {
                addView(TextView(this@MainActivity).apply {
                    text = if (archiveLoading) "Fetching completed games…" else "No completed games cached yet."
                    setTextColor(TEXT_MUTED)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(12.dp, 50.dp, 12.dp, 12.dp)
                })
                addView(shellCard("Analysis board", "Continue the board where you left it") { showAnalysisScreen() })
            } else {
                for (index in 0 until archiveGames.length()) {
                    archiveGames.optJSONObject(index)?.let { addView(gameRow(it)) }
                }
            }
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun gameRow(game: JSONObject): View {
        val white = playerName(game.optJSONObject("white"))
        val black = playerName(game.optJSONObject("black"))
        val whiteRating = playerRating(game.optJSONObject("white"))
        val blackRating = playerRating(game.optJSONObject("black"))
        val boardOrientation = if (archiveAccount.equals(white, ignoreCase = true)) "white" else "black"
        val title = listOf(
            game.optString("speed").replaceFirstChar { it.uppercase() },
            game.optString("variant").ifBlank { "Standard" }.replaceFirstChar { it.uppercase() },
            if (game.optBoolean("rated")) "Rated" else "Casual",
        ).filter(String::isNotBlank).joinToString(" · ")
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(game.optLong("last_move_at_ms")))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            setBackgroundColor(if (game.optInt("ply_count") % 2 == 0) 0xff292824.toInt() else 0xff302f2a.toInt())
            addView(GameThumbnailView(this@MainActivity, game.optString("preview_fen"), boardOrientation), LinearLayout.LayoutParams(108.dp, 108.dp).apply { marginEnd = 10.dp })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(nativeText(title, 14f, Color.WHITE, true))
                addView(nativeText(date, 11f, TEXT_MUTED).apply { setPadding(0, 1.dp, 0, 8.dp) })
                addView(nativeText("$white${ratingSuffix(whiteRating)}", 14f, 0xffdddddd.toInt(), true))
                addView(nativeText("$black${ratingSuffix(blackRating)}", 14f, 0xffdddddd.toInt(), true))
                addView(nativeText(gameResultText(game, white, black), 11f, if (game.optBoolean("analyzable")) 0xff9ca85b.toInt() else ERROR_TEXT).apply { setPadding(0, 7.dp, 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.isClickable = game.optBoolean("analyzable")
        row.isFocusable = row.isClickable
        row.alpha = if (row.isClickable) 1f else .68f
        if (row.isClickable) row.setOnClickListener {
            archiveMessage = "Loading game…"
            renderNativeScreen()
            nativeBridge.loadArchivedGame(game.optString("id"))
        }
        return row
    }

    private fun nativeText(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun playerName(player: JSONObject?): String = player?.optString("name")
        ?.ifBlank { player.optString("username") }?.ifBlank { "Anonymous" } ?: "Anonymous"

    private fun playerRating(player: JSONObject?): Int = player?.optInt("rating", 0) ?: 0
    private fun ratingSuffix(rating: Int) = if (rating > 0) "  $rating" else ""
    private fun gameResultText(game: JSONObject, white: String, black: String): String = when (game.optString("result")) {
        "1-0" -> "$white won"
        "0-1" -> "$black won"
        "1/2-1/2" -> "Draw"
        else -> game.optString("analysis_block_reason").ifBlank { "Completed" }
    }

    private fun profileContent(): View {
        val state = JSONObject(nativeBridge.getConnectionState())
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 4.dp, 10.dp, 2.dp)
            if (navigation.keypadOpen && !state.optBoolean("paired")) addView(pairingKeypad())
            else {
                addView(TextView(this@MainActivity).apply {
                    text = if (state.optBoolean("paired")) "PC paired" else "Connect your PC"
                    setTextColor(Color.WHITE)
                    textSize = 23f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = connectionMessage ?: if (state.optBoolean("paired"))
                        listOf(
                            archiveAccount.takeIf(String::isNotBlank),
                            state.optString("deviceName").ifBlank { "InstinctaZero Android" },
                            "Leela: exact SYCL · Intel iGPU",
                        ).filterNotNull().joinToString("\n")
                    else "Generate a pairing code on the InstinctaZero PC, then enter it using the keypad."
                    setTextColor(if (connectionMessage == null) TEXT_MUTED else ERROR_TEXT)
                    textSize = 15f
                    setPadding(0, 8.dp, 0, 22.dp)
                })
                addView(actionButton(if (state.optBoolean("paired")) "Disconnect" else "Enter pairing code") {
                    connectionMessage = null
                    if (state.optBoolean("paired")) nativeBridge.disconnectLocalFirst()
                    else { navigation.openKeypad(); renderNativeScreen() }
                })
            }
        }
    }

    private fun pairingKeypad(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = "Pair InstinctaZero PC"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@MainActivity).apply {
            text = connectionMessage ?: "Enter the 8-character code"
            setTextColor(if (connectionMessage == null || pairingCode.busy) TEXT_MUTED else ERROR_TEXT)
            textSize = 14f
            setPadding(0, 4.dp, 0, 10.dp)
        })
        val slots = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(PairingCodeBuffer.REQUIRED_LENGTH) { index ->
            slots.addView(TextView(this@MainActivity).apply {
                text = pairingCode.value.getOrNull(index)?.toString() ?: "·"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.MONOSPACE
                background = roundedBackground(0xff303030.toInt(), 1, 0xff666666.toInt(), 3)
            }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { setMargins(2.dp, 0, 2.dp, 0) })
        }
        addView(slots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 42.dp).apply { bottomMargin = 10.dp })
        PairingCodeBuffer.ALPHABET.chunked(ShellLayoutMetrics.KEYPAD_COLUMNS).forEach { rowCharacters ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowCharacters.forEach { character ->
                    addView(shellButton(character.toString(), 16f).apply {
                        backgroundTintList = ColorStateList.valueOf(0xff3d3d3d.toInt())
                        isEnabled = !pairingCode.busy
                        setOnClickListener { if (pairingCode.append(character)) renderNativeScreen() }
                    }, LinearLayout.LayoutParams(0, 46.dp, 1f).apply { setMargins(1.dp, 1.dp, 1.dp, 1.dp) })
                }
                repeat(ShellLayoutMetrics.KEYPAD_COLUMNS - rowCharacters.length) {
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 46.dp, 1f).apply {
                        setMargins(1.dp, 1.dp, 1.dp, 1.dp)
                    })
                }
            })
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("⌫") { if (pairingCode.erase()) renderNativeScreen() }.apply { isEnabled = !pairingCode.busy }, LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginEnd = 5.dp })
            addView(actionButton("Clear") { if (pairingCode.clear()) renderNativeScreen() }.apply { isEnabled = !pairingCode.busy }, LinearLayout.LayoutParams(0, 46.dp, 1f).apply { marginEnd = 5.dp })
            addView(actionButton("Pair") {
                if (!pairingCode.beginPair()) return@actionButton
                connectionMessage = "Pairing…"
                renderNativeScreen()
                nativeBridge.pairFromNative(pairingCode.value, nativeDeviceName())
            }.apply { isEnabled = pairingCode.complete && !pairingCode.busy }, LinearLayout.LayoutParams(0, 46.dp, 1.35f))
        }.also { it.setPadding(0, 8.dp, 0, 0) })
    }

    private fun drawerOverlay(): View = FrameLayout(this).apply {
        addView(View(this@MainActivity).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { navigation.closeDrawer(); renderNativeScreen() }
        }, matchFrame())
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 18.dp, 12.dp, 12.dp)
            setBackgroundColor(0xff252525.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "InstinctaZero"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(10.dp, 8.dp, 10.dp, 18.dp)
            })
            addView(drawerButton("Home") { showHomeScreen() })
            addView(drawerButton("Analysis board") { showAnalysisScreen() })
            addView(drawerButton("Profile / PC") { showProfileScreen() })
        }, FrameLayout.LayoutParams(292.dp, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
    }

    private fun drawerButton(label: String, action: () -> Unit): View = shellButton(label, 16f).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(14.dp, 0, 10.dp, 0)
        setOnClickListener { action() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp) }

    private fun shellCard(title: String, subtitle: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(18.dp, 17.dp, 18.dp, 17.dp)
        background = roundedBackground(0xff333333.toInt(), 1, 0xff4c4c4c.toInt(), 7)
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            setTextColor(TEXT_MUTED)
            textSize = 14f
            setPadding(0, 4.dp, 0, 0)
        })
        setOnClickListener { action() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }

    private fun actionButton(label: String, action: () -> Unit): Button = shellButton(label, 15f).apply {
        backgroundTintList = ColorStateList.valueOf(0xff4b4b4b.toInt())
        setOnClickListener { action() }
    }

    private fun shellButton(label: String, size: Float): Button = Button(this).apply {
        text = label
        textSize = size
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0
        minHeight = 0
        setPadding(8.dp, 0, 8.dp, 0)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    }

    private fun roundedBackground(fill: Int, strokeWidth: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(strokeWidth.dp, stroke)
        cornerRadius = radius.dp.toFloat()
    }

    private fun connectionSummary(): String = JSONObject(nativeBridge.getConnectionState()).let { state ->
        if (state.optBoolean("paired")) "Paired as ${state.optString("deviceName").ifBlank { "InstinctaZero Android" }}"
        else "Not paired · tap to connect"
    }

    private fun nativeDeviceName(): String {
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(36)
        return if (model.isBlank()) "InstinctaZero Android" else "InstinctaZero · $model"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchFrame() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun weighted() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private inner class LocalAnalysisWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            return if (AnalysisWebPolicy.isAllowedAssetUrl(url)) {
                assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()
            } else {
                blockedResponse()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !AnalysisWebPolicy.isAllowedMainFrameUrl(request.url.toString())

        override fun onPageFinished(view: WebView, url: String) {
            if (AnalysisWebPolicy.isAllowedMainFrameUrl(url)) {
                webPageLoaded = true
                setAnalysisActive(navigation.screen == ShellScreen.ANALYSIS)
                deliverPendingArchivedGame()
            }
        }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private val SHELL_BACKGROUND = 0xff2b2b2b.toInt()
        private val HEADER_BACKGROUND = 0xff222222.toInt()
        private val TEXT_MUTED = 0xffaaaaaa.toInt()
        private val ERROR_TEXT = 0xffff9e80.toInt()
    }
}

/** Pure URL policy, kept Android-free so ordinary JVM tests cover the security boundary. */
internal object AnalysisWebPolicy {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val ASSET_PREFIX = "/assets/analysis/"
    const val MAIN_PAGE_URL = "$ORIGIN${ASSET_PREFIX}index.html"
    private val gatewayOrigin: URI = URI(BuildConfig.LEELA_GATEWAY_ORIGIN)

    fun isAllowedMainFrameUrl(rawUrl: String): Boolean = rawUrl == MAIN_PAGE_URL

    fun isAllowedAssetUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("appassets.androidplatform.net", ignoreCase = true) &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith(ASSET_PREFIX) && uri.rawPath.length > ASSET_PREFIX.length &&
            '%' !in uri.rawPath && "/../" !in uri.rawPath && "/./" !in uri.rawPath
    } catch (_: Exception) {
        false
    }

    fun isAllowedNativeGatewayUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl)
        val fixedRoute = uri.rawPath in setOf(
            "/api/mobile/v1/pair/claim",
            "/api/mobile/v1/session",
            "/api/mobile/v1/sync",
            "/api/mobile/v1/study/analysis/stream",
            "/api/mobile/v1/study/explorer",
        ) && uri.rawQuery == null
        val gamesList = uri.rawPath == "/api/mobile/v1/games" &&
            (uri.rawQuery == null || Regex("limit=100(?:&cursor=[A-Za-z0-9_-]{1,256})?").matches(uri.rawQuery))
        val gameDetail = Regex("/api/mobile/v1/games/[A-Za-z0-9]{8,16}").matches(uri.rawPath) &&
            uri.rawQuery == null
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(gatewayOrigin.host, ignoreCase = true) &&
            uri.port == gatewayOrigin.port && uri.userInfo == null &&
            uri.rawFragment == null && (fixedRoute || gamesList || gameDetail)
    } catch (_: Exception) {
        false
    }
}

/**
 * The complete public JavaScript ABI. All methods return a request id immediately; results are
 * sent to the local page as:
 *
 * - `window.InstinctaZero.onNativeAnalysis(id, payloadJson)`
 * - `window.InstinctaZero.onNativeExplorer(id, payloadJson)`
 * - `window.InstinctaZero.onNativeConnectionState(payloadJson)`
 *
 * `payloadJson` is a JSON string, never a bearer token. The page should use Abort-like behaviour
 * by calling [cancelAnalysis] on board/tab/background changes. Analysis requests carry a
 * legal UCI history and search limits. The server alone selects its fixed exact-SYCL profile.
 */
class NativeAnalysisBridge(private val activity: MainActivity) {
    companion object {
        const val JS_OBJECT = "InstinctaZeroNative"
        private const val TOKEN_KEY = "paired_device_token"
        private const val DEVICE_NAME_KEY = "paired_device_name"
        private const val MAX_REQUEST_JSON = 16 * 1024
        private const val MAX_SETTINGS_JSON = 2 * 1024
        private const val MAX_STUDY_JSON = 256 * 1024
        private const val MAX_ARCHIVE_JSON = 2 * 1024 * 1024
        private val BOOK_SPEEDS = listOf("bullet", "blitz", "rapid", "classical", "correspondence")
        private val BOOK_RATINGS = listOf(1600, 1800, 2000, 2200, 2500)
    }

    private val executor = Executors.newCachedThreadPool()
    private val calls = ConcurrentHashMap<String, PendingCall>()
    @Volatile private var webView: WebView? = null
    private val restHttp = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    // The backend heartbeat is every 10 seconds. A read timeout on this long-lived response can
    // race engine startup or ordinary network jitter, so only explicit request/lifecycle
    // cancellation terminates analysis. This client shares the bounded connect/write settings.
    private val streamHttp = restHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val encryptedPreferences by lazy {
        val key = MasterKey.Builder(activity).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            activity,
            "study_gateway_credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val uiPreferences by lazy {
        activity.getSharedPreferences("study_ui_settings", Context.MODE_PRIVATE)
    }
    private val studyPreferences by lazy {
        activity.getSharedPreferences("local_study_state", Context.MODE_PRIVATE)
    }
    private val archivePreferences by lazy {
        activity.getSharedPreferences("completed_game_cache", Context.MODE_PRIVATE)
    }
    private val gatewayBase: HttpUrl = BuildConfig.LEELA_GATEWAY_ORIGIN.toHttpUrlChecked()

    fun attachWebView(value: WebView) {
        webView = value
    }

    fun getConnectionState(): String = connectionState().toString()
    fun isPaired(): Boolean = encryptedPreferences.contains(TOKEN_KEY)
    fun cachedArchive(): JSONObject? = runCatching {
        archivePreferences.getString("games_v1", null)?.let(::JSONObject)
    }.getOrNull()

    /** Typed UI preferences only; this is not a generic WebView key-value store. */
    @JavascriptInterface
    fun getUiSettings(): String = uiSettings().toString()

    @JavascriptInterface
    fun saveUiSettings(rawSettings: String?): String = try {
        require(rawSettings != null && rawSettings.length <= MAX_SETTINGS_JSON) { "Invalid settings." }
        val requested = JSONObject(rawSettings)
        val nodes = requested.optInt("nodes", uiSettings().getInt("nodes")).coerceIn(100, 100_000)
        val arrowCount = requested.optInt("arrowCount", uiSettings().getInt("arrowCount")).coerceIn(1, 8)
        val leelaEnabled = requested.optBoolean("leelaEnabled", uiSettings().getBoolean("leelaEnabled"))
        val arrowsEnabled = requested.optBoolean("arrowsEnabled", uiSettings().getBoolean("arrowsEnabled"))
        val appearance = requested.optString("appearance", uiSettings().getString("appearance"))
        val bookSource = requested.optString("bookSource", uiSettings().getString("bookSource"))
        require(bookSource in setOf("masters", "lichess")) { "Invalid opening-book source." }
        val bookSpeeds = normalizedStringSelection(
            requested.optJSONArray("bookSpeeds") ?: uiSettings().getJSONArray("bookSpeeds"),
            BOOK_SPEEDS,
        )
        val bookRatings = normalizedIntSelection(
            requested.optJSONArray("bookRatings") ?: uiSettings().getJSONArray("bookRatings"),
            BOOK_RATINGS,
        )
        require(appearance in setOf("brown", "blue", "green", "grey")) { "Invalid board appearance." }
        check(uiPreferences.edit()
            .putInt("nodes", nodes)
            .putInt("arrowCount", arrowCount)
            .putBoolean("leelaEnabled", leelaEnabled)
            .putBoolean("arrowsEnabled", arrowsEnabled)
            .putString("appearance", appearance)
            .putString("bookSource", bookSource)
            .putString("bookSpeeds", JSONArray(bookSpeeds).toString())
            .putString("bookRatings", JSONArray(bookRatings).toString())
            .commit()) { "Could not save settings." }
        uiSettings().toString()
    } catch (_: Exception) {
        // Return the last known-good typed state; callers never receive arbitrary stored data.
        uiSettings().toString()
    }

    @JavascriptInterface
    fun getStudyState(): String = studyPreferences.getString("state_v1", "{}") ?: "{}"

    @JavascriptInterface
    fun saveStudyState(rawState: String?): Boolean = try {
        require(rawState != null && rawState.length <= MAX_STUDY_JSON) { "Study is too large." }
        val parsed = JSONObject(rawState)
        require(parsed.optInt("v") == 1) { "Unsupported study state." }
        val cursor = parsed.optJSONArray("cursor") ?: JSONArray()
        require(cursor.length() <= 512) { "Study cursor is too long." }
        check(studyPreferences.edit().putString("state_v1", parsed.toString()).commit())
        true
    } catch (_: Exception) {
        false
    }

    /** Native-only pairing entry point. The bearer can never cross into WebView JavaScript. */
    fun pairFromNative(code: String?, deviceName: String?): String = newRequestId().also { id ->
        val safeCode = code?.trim()?.uppercase().orEmpty()
        val safeName = deviceName?.trim().orEmpty().take(64)
        if (safeCode.length != PairingCodeBuffer.REQUIRED_LENGTH ||
            safeCode.any { it !in PairingCodeBuffer.ALPHABET } || safeName.isEmpty()
        ) {
            emitConnectionError("Invalid pairing details.")
        } else {
            val pending = PendingCall()
            calls[id] = pending
            executor.execute { pairOnWorker(id, pending, safeCode, safeName) }
        }
    }

    fun refreshArchive(): String = newRequestId().also { id ->
        val pending = PendingCall()
        calls[id] = pending
        executor.execute { refreshArchiveOnWorker(id, pending) }
    }

    fun loadArchivedGame(gameId: String): String = newRequestId().also { id ->
        if (!gameId.matches(Regex("[A-Za-z0-9]{8,16}"))) {
            activity.runOnUiThread { activity.onArchivedGame(null, "Invalid stored game.") }
            return@also
        }
        val pending = PendingCall()
        calls[id] = pending
        executor.execute { loadArchivedGameOnWorker(id, pending, gameId) }
    }

    /** Starts the authorized Leela SSE stream from standard chess initial position. */
    @JavascriptInterface
    fun startAnalysis(requestJson: String?): String = newRequestId().also { id ->
        val pending = PendingCall()
        calls[id] = pending
        executor.execute {
            val request = parseStudyRequest(requestJson, id, "analysis")
            if (request == null) calls.remove(id, pending) else streamAnalysis(id, pending, request)
        }
    }

    @JavascriptInterface
    fun cancelAnalysis(requestId: String?): Boolean {
        val pending = requestId?.let(calls::remove) ?: return false
        pending.cancel()
        return true
    }

    @JavascriptInterface
    fun requestExplorer(requestJson: String?): String = newRequestId().also { id ->
        val pending = PendingCall()
        calls[id] = pending
        executor.execute {
            val request = parseStudyRequest(requestJson, id, "explorer")
            if (request == null) calls.remove(id, pending) else requestExplorerOnWorker(id, pending, request)
        }
    }

    @JavascriptInterface
    fun leaveAnalysis() {
        activity.runOnUiThread { activity.showHomeScreen() }
    }

    /** Forget locally first; remote self-revocation is deliberately best-effort. */
    fun disconnectLocalFirst() {
        val token = encryptedPreferences.getString(TOKEN_KEY, null)
        cancelAll("disconnected")
        encryptedPreferences.edit().remove(TOKEN_KEY).remove(DEVICE_NAME_KEY).commit()
        archivePreferences.edit().clear().commit()
        publishConnectionState(connectionState())
        if (token.isNullOrBlank()) return
        executor.execute {
            runCatching {
                restHttp.newCall(
                    Request.Builder()
                        .url(apiUrl("session"))
                        .header("Authorization", "Bearer $token")
                        .delete()
                        .build(),
                ).execute().use { /* Local state remains disconnected for every response. */ }
            }
        }
    }

    fun cancelAll(reason: String) {
        calls.entries.toList().forEach { (id, pending) ->
            calls.remove(id, pending)
            pending.cancel()
        }
    }

    fun close() {
        cancelAll("destroyed")
        executor.shutdownNow()
        restHttp.dispatcher.executorService.shutdown()
        restHttp.connectionPool.evictAll()
    }

    private fun pairOnWorker(id: String, pending: PendingCall, code: String, deviceName: String) {
        val body = JSONObject().put("code", code).put("device_name", deviceName).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = restHttp.newCall(
            Request.Builder().url(apiUrl("pair/claim")).post(body).build(),
        )
        if (!pending.attach(call)) {
            calls.remove(id, pending)
            return
        }
        try {
            call.execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw GatewayException(response.code, responseError(response, payload))
                val token = JSONObject(payload).optString("token")
                if (token.isBlank() || token.length > 512) throw IOException("Pairing response had no valid token.")
                encryptedPreferences.edit()
                    .putString(TOKEN_KEY, token)
                    .putString(DEVICE_NAME_KEY, deviceName)
                    .commit()
                publishConnectionState(connectionState())
            }
        } catch (error: Exception) {
            if (!call.isCanceled()) emitConnectionError(error.safeMessage(), error.gatewayCode())
        } finally {
            calls.remove(id, pending)
        }
    }

    private fun refreshArchiveOnWorker(id: String, pending: PendingCall) {
        val token = encryptedPreferences.getString(TOKEN_KEY, null)
        if (token.isNullOrBlank()) {
            calls.remove(id, pending)
            activity.runOnUiThread { activity.onArchivePayload(null, "Pair the analysis PC first.") }
            return
        }
        try {
            executeJson(
                pending,
                Request.Builder().url(apiUrl("sync"))
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
                256 * 1024,
            )
            for (attempt in 0 until 60) {
                if (pending.isCanceled()) throw IOException("Request cancelled.")
                val status = executeJson(
                    pending,
                    Request.Builder().url(apiUrl("sync"))
                        .header("Authorization", "Bearer $token")
                        .get().build(),
                    256 * 1024,
                ).optJSONObject("sync")?.optString("status").orEmpty()
                if (status !in setOf("running", "starting")) break
                Thread.sleep(250)
            }
            val session = executeJson(
                pending,
                Request.Builder().url(apiUrl("session"))
                    .header("Authorization", "Bearer $token")
                    .get().build(),
                256 * 1024,
            )
            val allGames = JSONArray()
            var cursor: String? = null
            for (pageIndex in 0 until 100) {
                val gamesUrl = apiUrl("games").newBuilder()
                    .addQueryParameter("limit", "100")
                    .apply { cursor?.let { addQueryParameter("cursor", it) } }
                    .build()
                check(AnalysisWebPolicy.isAllowedNativeGatewayUrl(gamesUrl.toString()))
                val page = executeJson(
                    pending,
                    Request.Builder().url(gamesUrl)
                        .header("Authorization", "Bearer $token")
                        .get().build(),
                    MAX_ARCHIVE_JSON,
                )
                val items = page.optJSONArray("games") ?: JSONArray()
                for (index in 0 until items.length()) allGames.put(items.getJSONObject(index))
                cursor = page.optString("next_cursor").ifBlank { null }
                if (cursor == null) break
            }
            val result = JSONObject()
                .put("account", session.optJSONObject("account")?.optString("username").orEmpty())
                .put("games", allGames)
                .put("next_cursor", cursor)
            require(result.toString().length <= MAX_ARCHIVE_JSON) { "Completed-game cache is too large." }
            check(archivePreferences.edit().putString("games_v1", result.toString()).commit())
            if (!pending.isCanceled()) activity.runOnUiThread { activity.onArchivePayload(result, null) }
        } catch (error: Exception) {
            if (!pending.isCanceled()) activity.runOnUiThread {
                activity.onArchivePayload(null, error.safeMessage())
            }
        } finally {
            calls.remove(id, pending)
        }
    }

    private fun loadArchivedGameOnWorker(id: String, pending: PendingCall, gameId: String) {
        val token = encryptedPreferences.getString(TOKEN_KEY, null)
        if (token.isNullOrBlank()) {
            calls.remove(id, pending)
            activity.runOnUiThread { activity.onArchivedGame(null, "Pair the analysis PC first.") }
            return
        }
        try {
            val payload = executeJson(
                pending,
                Request.Builder().url(apiUrl("games/$gameId"))
                    .header("Authorization", "Bearer $token")
                    .get().build(),
                MAX_ARCHIVE_JSON,
            )
            if (!pending.isCanceled()) activity.runOnUiThread { activity.onArchivedGame(payload, null) }
        } catch (error: Exception) {
            if (!pending.isCanceled()) activity.runOnUiThread {
                activity.onArchivedGame(null, error.safeMessage())
            }
        } finally {
            calls.remove(id, pending)
        }
    }

    private fun executeJson(pending: PendingCall, request: Request, maximumBytes: Int): JSONObject {
        val call = restHttp.newCall(request)
        if (!pending.attach(call)) throw IOException("Request cancelled.")
        return call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.length > maximumBytes) throw IOException("Gateway response is too large.")
            if (!response.isSuccessful) throw GatewayException(response.code, responseError(response, body))
            JSONObject(body)
        }
    }

    private fun streamAnalysis(id: String, pending: PendingCall, request: JSONObject) {
        val call = authorizedPost("study/analysis/stream", request, streaming = true) ?: run {
            if (!pending.isCanceled()) {
                emit("onNativeAnalysis", id, errorPayload("Pair this device before requesting analysis."))
            }
            calls.remove(id, pending)
            return
        }
        if (!pending.attach(call)) {
            calls.remove(id, pending)
            return
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw GatewayException(response.code, responseError(response, response.body?.string().orEmpty()))
                }
                val source = response.body?.source() ?: throw IOException("Analysis gateway returned no stream.")
                var event = "message"
                val data = StringBuilder()
                fun dispatchFrame() {
                    if (data.isEmpty()) return
                    // Each gateway data block is JSON. The event is retained in a wrapper so the
                    // page does not need to parse raw SSE framing.
                    val wrapped = JSONObject()
                        .put("event", event)
                        .put("data", JSONObject(data.toString().trim()))
                    emit("onNativeAnalysis", id, wrapped.toString())
                    event = "message"
                    data.setLength(0)
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart()).append('\n')
                        line.isEmpty() -> dispatchFrame()
                    }
                }
                dispatchFrame()
            }
        } catch (error: Exception) {
            if (!call.isCanceled()) emit("onNativeAnalysis", id, errorPayload(error.safeMessage(), error.gatewayCode()))
        } finally {
            calls.remove(id, pending)
        }
    }

    private fun requestExplorerOnWorker(id: String, pending: PendingCall, request: JSONObject) {
        val call = authorizedPost("study/explorer", request) ?: run {
            if (!pending.isCanceled()) {
                emit("onNativeExplorer", id, errorPayload("Pair this device before requesting the book."))
            }
            calls.remove(id, pending)
            return
        }
        if (!pending.attach(call)) {
            calls.remove(id, pending)
            return
        }
        try {
            call.execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw GatewayException(response.code, responseError(response, payload))
                if (payload.length > 512 * 1024) throw IOException("Opening-book response is too large.")
                // Reject malformed values before they cross the native/web boundary.
                JSONObject(payload)
                emit("onNativeExplorer", id, payload)
            }
        } catch (error: Exception) {
            if (!call.isCanceled()) emit("onNativeExplorer", id, errorPayload(error.safeMessage(), error.gatewayCode()))
        } finally {
            calls.remove(id, pending)
        }
    }

    private fun authorizedPost(path: String, payload: JSONObject, streaming: Boolean = false): Call? {
        val token = encryptedPreferences.getString(TOKEN_KEY, null) ?: return null
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        return (if (streaming) streamHttp else restHttp).newCall(
            Request.Builder()
                .url(apiUrl(path))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json, text/event-stream")
                .post(body)
                .build(),
        )
    }

    private fun parseStudyRequest(raw: String?, id: String, target: String): JSONObject? = try {
        require(raw != null && raw.length <= MAX_REQUEST_JSON) { "Invalid study request." }
        val parsed = JSONObject(raw)
        val history = parsed.optJSONArray("history") ?: JSONArray()
        require(history.length() <= 512) { "Invalid study position." }
        val normalizedHistory = JSONArray()
        for (index in 0 until history.length()) {
            val move = history.getString(index)
            require(move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "Invalid move history." }
            normalizedHistory.put(move)
        }
        // The server reconstructs and validates this sequence from standard chess initial state.
        // Deliberately do not forward a client FEN, even if an older local page still includes one.
        JSONObject().put("history", normalizedHistory).apply {
            if (parsed.has("game_id")) {
                val gameId = parsed.getString("game_id")
                require(gameId.matches(Regex("[A-Za-z0-9]{8,16}"))) { "Invalid stored game." }
                put("game_id", gameId)
            }
            if (target == "analysis") {
                put("nodes", parsed.optInt("nodes", 1000).coerceIn(1, 100_000))
            } else {
                val source = parsed.optString("source", "masters")
                require(source == "masters" || source == "lichess") { "Invalid opening-book source." }
                put("source", source)
                if (source == "lichess") {
                    parsed.optJSONArray("speeds")?.let {
                        put("speeds", JSONArray(normalizedStringSelection(it, BOOK_SPEEDS)))
                    }
                    parsed.optJSONArray("ratings")?.let {
                        put("ratings", JSONArray(normalizedIntSelection(it, BOOK_RATINGS)))
                    }
                }
            }
        }
    } catch (error: Exception) {
        emit(if (target == "analysis") "onNativeAnalysis" else "onNativeExplorer", id, errorPayload(error.safeMessage()))
        null
    }

    private fun apiUrl(path: String): HttpUrl {
        val fullPath = "api/mobile/v1/$path"
        val builder = gatewayBase.newBuilder().encodedPath("/").addPathSegments(fullPath)
        val url = builder.build()
        check(AnalysisWebPolicy.isAllowedNativeGatewayUrl(url.toString())) { "Refusing non-study gateway route." }
        return url
    }

    private fun connectionState() = JSONObject().let { state ->
        val paired = encryptedPreferences.contains(TOKEN_KEY)
        state.put("paired", paired)
            .put("state", if (paired) "paired" else "unpaired")
            .put("deviceName", encryptedPreferences.getString(DEVICE_NAME_KEY, ""))
    }

    private fun uiSettings() = JSONObject()
        .put("nodes", uiPreferences.getInt("nodes", 1000))
        .put("arrowCount", uiPreferences.getInt("arrowCount", 8).coerceIn(1, 8))
        .put("leelaEnabled", uiPreferences.getBoolean("leelaEnabled", true))
        .put("arrowsEnabled", uiPreferences.getBoolean("arrowsEnabled", true))
        .put("appearance", uiPreferences.getString("appearance", "brown"))
        .put("bookSource", uiPreferences.getString("bookSource", "lichess"))
        .put("bookSpeeds", storedArray("bookSpeeds", "[]"))
        .put("bookRatings", storedArray("bookRatings", "[]"))

    private fun storedArray(key: String, fallback: String): JSONArray = runCatching {
        JSONArray(uiPreferences.getString(key, fallback))
    }.getOrElse { JSONArray(fallback) }

    private fun normalizedStringSelection(values: JSONArray, allowed: List<String>): List<String> {
        val selected = mutableSetOf<String>()
        for (index in 0 until values.length()) {
            val value = values.getString(index)
            require(value in allowed) { "Invalid opening-book filter." }
            selected += value
        }
        return allowed.filter(selected::contains)
    }

    private fun normalizedIntSelection(values: JSONArray, allowed: List<Int>): List<Int> {
        val selected = mutableSetOf<Int>()
        for (index in 0 until values.length()) {
            val value = values.getInt(index)
            require(value in allowed) { "Invalid opening-book filter." }
            selected += value
        }
        return allowed.filter(selected::contains)
    }

    private fun publishConnectionState(state: JSONObject) {
        activity.runOnUiThread { activity.onBridgeConnectionState(state) }
        emit("onNativeConnectionState", null, state.toString())
    }

    private fun emitConnectionError(message: String, code: Int? = null) = publishConnectionState(
        connectionState().put("error", message).apply { code?.let { put("code", it) } },
    )

    private fun emit(callback: String, requestId: String?, payload: String) {
        val invocation = buildString {
            append("window.InstinctaZero&&window.InstinctaZero.")
            append(callback)
            append("&&window.InstinctaZero.")
            append(callback)
            append('(')
            if (requestId != null) append(JSONObject.quote(requestId)).append(',')
            append(JSONObject.quote(payload))
            append(");void 0;")
        }
        activity.runOnUiThread {
            // A callback may race with Activity destruction; simply discard it then.
            if (!activity.isFinishing && !activity.isDestroyed) webView?.evaluateJavascript(invocation, null)
        }
    }

    private fun errorPayload(message: String, code: Int? = null) = JSONObject()
        .put("event", "error")
        .put("message", message)
        .apply { code?.let { put("code", it) } }
        .toString()

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private fun Throwable.safeMessage(): String = message?.take(180) ?: "Unable to contact the analysis gateway."

    private fun Throwable.gatewayCode(): Int? = (this as? GatewayException)?.code

    private fun responseError(response: Response, payload: String): String {
        val message = runCatching { JSONObject(payload).optString("error") }.getOrDefault("").trim()
        return (message.ifBlank { "Gateway returned ${response.code}." }).take(180)
    }

    private class GatewayException(val code: Int, message: String) : IOException(message)

    /**
     * Registered before a request id crosses the JavaScript boundary. Cancellation therefore
     * remains authoritative even when it races request parsing or OkHttp Call construction.
     */
    private class PendingCall {
        private var canceled = false
        private var call: Call? = null

        @Synchronized
        fun attach(value: Call): Boolean {
            if (canceled) {
                value.cancel()
                return false
            }
            call = value
            return true
        }

        @Synchronized
        fun cancel() {
            canceled = true
            call?.cancel()
        }

        @Synchronized
        fun isCanceled(): Boolean = canceled
    }
}

private fun String.toHttpUrlChecked(): HttpUrl {
    val uri = URI(this)
    require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.port in 1..65535 &&
        uri.userInfo == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
        uri.rawQuery == null && uri.rawFragment == null) { "Invalid configured HTTPS gateway." }
    return HttpUrl.Builder().scheme("https").host(requireNotNull(uri.host)).port(uri.port).build()
}
