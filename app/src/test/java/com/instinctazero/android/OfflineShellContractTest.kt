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
