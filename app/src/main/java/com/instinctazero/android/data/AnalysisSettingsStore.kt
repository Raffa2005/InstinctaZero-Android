package com.instinctazero.android.data

import android.content.Context
import com.instinctazero.android.model.AnalysisSettings

internal class AnalysisSettingsStore(context: Context) : SettingsStorage {
    private val preferences = context.getSharedPreferences("analysis_settings", Context.MODE_PRIVATE)

    override fun load(): AnalysisSettings = AnalysisSettings(
        nodes = preferences.getInt("nodes", 1_000).coerceIn(1, 100_000),
        multipv = preferences.getInt("multipv", 5).coerceIn(1, 8),
        profile = preferences.getString("profile", "exact-sycl") ?: "exact-sycl",
    )

    override fun save(settings: AnalysisSettings) {
        preferences.edit()
            .putInt("nodes", settings.nodes.coerceIn(1, 100_000))
            .putInt("multipv", settings.multipv.coerceIn(1, 8))
            .putString("profile", settings.profile)
            .apply()
    }
}
