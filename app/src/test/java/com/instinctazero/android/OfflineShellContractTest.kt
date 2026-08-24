package com.instinctazero.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the fixed-gateway analysis shell. They run as plain JVM tests so the
 * security-critical URL decision is tested without a device or a real WebView implementation.
 */
class OfflineShellContractTest {
    @Test
    fun onlyTheExactBundledDocumentMayBecomeTheMainFrame() {
        assertTrue(AnalysisWebPolicy.isAllowedMainFrameUrl(AnalysisWebPolicy.MAIN_PAGE_URL))
        assertFalse(AnalysisWebPolicy.isAllowedMainFrameUrl("https://appassets.androidplatform.net/assets/analysis/"))
        assertFalse(AnalysisWebPolicy.isAllowedMainFrameUrl("https://appassets.androidplatform.net/assets/analysis/index.html#x"))
        assertFalse(AnalysisWebPolicy.isAllowedMainFrameUrl("https://lichess.org/analysis"))
    }

    @Test
    fun bundledAssetsAreTheOnlyLoadableResources() {
        assertTrue(AnalysisWebPolicy.isAllowedAssetUrl(AnalysisWebPolicy.MAIN_PAGE_URL))
        assertTrue(AnalysisWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/pieces/wK.svg"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("http://appassets.androidplatform.net/assets/analysis/index.html"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("https://evil.example/assets/analysis/index.html"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/licenses/GPL-3.0.txt"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/../licenses/GPL-3.0.txt"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/index.html?track=1"))
        assertFalse(AnalysisWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/%2e%2e/licenses/GPL-3.0.txt"))
    }

    @Test
    fun packagedShellOnlyAllowsTheConfiguredHttpsGateway() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val page = projectFile("src/main/assets/analysis/index.html").readText()
        val css = projectFile("src/main/assets/analysis/analysis.css").readText()
        val activity = projectFile("src/main/java/com/instinctazero/android/MainActivity.kt").readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(page.contains("connect-src 'none'"))
        assertTrue(page.contains("default-src 'self'"))
        assertTrue(page.contains("name=\"color-scheme\" content=\"dark\""))
        assertTrue(css.contains("color-scheme:only dark"))
        assertTrue(css.contains("forced-color-adjust:none"))
        assertTrue(activity.contains("WebView.setWebContentsDebuggingEnabled(false)"))
        assertTrue(activity.contains("blockNetworkLoads = true"))
        assertTrue(activity.contains("setAlgorithmicDarkeningAllowed(settings, false)"))
        assertTrue(manifest.contains("android:forceDarkAllowed=\"false\""))
        assertTrue(activity.contains("setDownloadListener"))
        assertTrue(activity.contains("onPermissionRequest"))
        assertTrue(activity.contains("getUiSettings"))
        assertTrue(activity.contains("saveUiSettings"))
        assertTrue(activity.contains("requested.optInt(\"arrowCount\""))
        assertTrue(activity.contains(".putInt(\"arrowCount\", arrowCount)"))
        assertTrue(activity.contains(".put(\"arrowCount\", uiPreferences.getInt(\"arrowCount\", 8).coerceIn(1, 8))"))
        assertFalse(activity.contains("requested.optInt(\"multipv\""))
        assertFalse(activity.contains(".putInt(\"multipv\""))
        assertFalse(activity.contains("put(\"multipv\", parsed.optInt"))
        assertTrue(activity.contains("readTimeout(30, TimeUnit.SECONDS)"))
        assertTrue(activity.contains("readTimeout(0, TimeUnit.MILLISECONDS)"))
        assertTrue(activity.contains("streaming = true"))
        assertTrue(activity.contains("nativeBridge.cancelAll(\"backgrounded\")"))
        assertTrue(activity.contains("calls[id] = pending"))
        assertTrue(activity.contains("if (!pending.attach(call))"))
        assertTrue(activity.contains("value.cancel()"))
        assertFalse(activity.contains("exact-sycl"))
        assertFalse(activity.contains("experimental-hetero-int8"))
    }

    @Test
    fun onlyPairingAndStudyRoutesOnTheConfiguredHttpsOriginMayUseNativeNetwork() {
        val origin = "https://rafael-ms-7e34.tail273ae6.ts.net:8443"
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/pair/claim"))
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/session"))
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/analysis/stream"))
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/explorer"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("http://rafael-ms-7e34.tail273ae6.ts.net:8443/api/mobile/v1/study/analysis/stream"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("https://rafael-ms-7e34.tail273ae6.ts.net/api/mobile/v1/study/analysis/stream"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("https://rafael-ms-7e34.tail273ae6.ts.net:443/api/mobile/v1/study/analysis/stream"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/games"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/unknown"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("https://evil.example:8443/api/mobile/v1/study/analysis/stream"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/analysis/stream?x=1"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/../games"))
        assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$origin/api/mobile/v1/study/%2e%2e/games"))
    }

    @Test
    fun nativeStudyBodiesAreReducedToTheEndpointSpecificContract() {
        val activity = projectFile("src/main/java/com/instinctazero/android/MainActivity.kt").readText()
        val start = activity.indexOf("private fun parseStudyRequest")
        val end = activity.indexOf("private fun apiUrl", start)
        assertTrue(start >= 0 && end > start)
        val normalizer = activity.substring(start, end)

        assertTrue(normalizer.contains("JSONObject().put(\"history\", normalizedHistory)"))
        assertTrue(normalizer.contains("if (target == \"analysis\")"))
        assertTrue(normalizer.contains("put(\"nodes\", parsed.optInt(\"nodes\", 1000).coerceIn(1, 100_000))"))
        assertTrue(normalizer.contains("put(\"source\", source)"))
        assertTrue(normalizer.contains("if (source == \"lichess\")"))
        assertTrue(normalizer.contains("put(\"speeds\", JSONArray(normalizedStringSelection(it, BOOK_SPEEDS)))"))
        assertTrue(normalizer.contains("put(\"ratings\", JSONArray(normalizedIntSelection(it, BOOK_RATINGS)))"))
        assertFalse(normalizer.contains("put(\"multipv\""))
        assertFalse(normalizer.contains("put(\"fen\""))
    }

    @Test
    fun pairingIsNativeTouchOnlyAndDisconnectForgetsLocallyBeforeRemoteRevoke() {
        val activity = projectFile("src/main/java/com/instinctazero/android/MainActivity.kt").readText()
        val page = projectFile("src/main/assets/analysis/index.html").readText()
        val controller = projectFile("src/main/assets/analysis/analysis.js").readText()
        val disconnectStart = activity.indexOf("fun disconnectLocalFirst()")
        val disconnectEnd = activity.indexOf("fun cancelAll", disconnectStart)
        val disconnect = activity.substring(disconnectStart, disconnectEnd)

        assertTrue(activity.contains("fun pairFromNative"))
        assertFalse(activity.contains("@JavascriptInterface\n    fun pair("))
        assertFalse(activity.contains("EditText"))
        assertFalse(Regex("<input[^>]+type=[\\\"'](?:text|number)[\\\"']", RegexOption.IGNORE_CASE).containsMatchIn(page + controller))
        assertFalse(controller.contains("Pair code"))
        assertFalse(controller.contains("data-pair"))
        assertTrue(disconnect.indexOf("remove(TOKEN_KEY)") < disconnect.indexOf("executor.execute"))
        assertTrue(disconnect.contains(".url(apiUrl(\"session\"))"))
        assertTrue(disconnect.contains(".delete()"))
        assertFalse(disconnect.contains("putString(TOKEN_KEY"))
    }

    @Test
    fun launchAndNativeNavigationDoNotFetchGamesOrSynchroniseAccounts() {
        val activity = projectFile("src/main/java/com/instinctazero/android/MainActivity.kt").readText()
        assertTrue(activity.contains("private val navigation = ShellNavigation()"))
        assertTrue(activity.contains("renderNativeScreen()"))
        assertTrue(activity.contains("visibility = View.GONE"))
        assertTrue(activity.contains("if (!webPageLoaded) webView.loadUrl"))
        assertFalse(activity.contains("/games"))
        assertFalse(activity.contains("syncAccount"))
    }

    @Test
    fun obsoleteAccountAndRemoteEngineSourcesAreNotPartOfTheAppSourceTree() {
        val sourceRoot = projectFile("src/main/java/com/instinctazero/android")
        val obsolete = listOf(
            "AppPreferences.kt",
            "InstinctaViewModel.kt",
            "InstinctaZeroApplication.kt",
            "data/MobileApiClient.kt",
            "data/AnalysisStreamClient.kt",
            "data/InstinctaRepository.kt",
            "security/SecureSessionStore.kt",
        )
        obsolete.forEach { relative ->
            assertFalse("obsolete source remains: $relative", File(sourceRoot, relative).exists())
        }
    }

    private fun projectFile(relative: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, relative),
            File(workingDirectory, "app/$relative"),
        ).firstOrNull { it.isFile || it.isDirectory }
            ?: error("Missing project path from ${workingDirectory.absolutePath}: $relative")
    }
}
