package com.instinctazero.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the intentionally offline v0.2 shell.  They run as plain JVM tests so the
 * security-critical URL decision is tested without a device or a real WebView implementation.
 */
class OfflineShellContractTest {
    @Test
    fun onlyTheExactBundledDocumentMayBecomeTheMainFrame() {
        assertTrue(OfflineWebPolicy.isAllowedMainFrameUrl(OfflineWebPolicy.MAIN_PAGE_URL))
        assertFalse(OfflineWebPolicy.isAllowedMainFrameUrl("https://appassets.androidplatform.net/assets/analysis/"))
        assertFalse(OfflineWebPolicy.isAllowedMainFrameUrl("https://appassets.androidplatform.net/assets/analysis/index.html#x"))
        assertFalse(OfflineWebPolicy.isAllowedMainFrameUrl("https://lichess.org/analysis"))
    }

    @Test
    fun bundledAssetsAreTheOnlyLoadableResources() {
        assertTrue(OfflineWebPolicy.isAllowedAssetUrl(OfflineWebPolicy.MAIN_PAGE_URL))
        assertTrue(OfflineWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/pieces/wK.svg"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("http://appassets.androidplatform.net/assets/analysis/index.html"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("https://evil.example/assets/analysis/index.html"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/licenses/GPL-3.0.txt"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/../licenses/GPL-3.0.txt"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/index.html?track=1"))
        assertFalse(OfflineWebPolicy.isAllowedAssetUrl("https://appassets.androidplatform.net/assets/analysis/%2e%2e/licenses/GPL-3.0.txt"))
    }

    @Test
    fun packagedShellHasNoInternetPermissionAndThePageForbidsConnections() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val page = projectFile("src/main/assets/analysis/index.html").readText()
        val activity = projectFile("src/main/java/com/instinctazero/android/MainActivity.kt").readText()

        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("uses-permission"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(page.contains("connect-src 'none'"))
        assertTrue(page.contains("default-src 'self'"))
        assertTrue(activity.contains("WebView.setWebContentsDebuggingEnabled(false)"))
        assertTrue(activity.contains("blockNetworkLoads = true"))
        assertTrue(activity.contains("setDownloadListener"))
        assertTrue(activity.contains("onPermissionRequest"))
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
