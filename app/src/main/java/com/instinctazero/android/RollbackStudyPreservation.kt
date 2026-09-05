package com.instinctazero.android

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONObject

/** The simple UI must never rewrite the richer v0.6 study format or its active-state copy. */
internal object RollbackStudyPreservation {
    private const val SIMPLE_PREFS = "simple_analysis_after_rollback_v1"
    private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Synchronized fun open(context: Context): SharedPreferences {
        val simple = context.getSharedPreferences(SIMPLE_PREFS, Context.MODE_PRIVATE)
        if (simple.getBoolean("seeded", false)) return simple

        // v0.6 migrated the preceding simple board to this chapter. Read only: do not
        // recover/rename AtomicFile sidecars or modify the library during rollback.
        val directory = File(context.filesDir, "study_library")
        val legacy = listOf("legacy-analysis.json", "legacy-analysis.json.bak")
            .firstNotNullOfOrNull { name ->
                runCatching {
                    val file = File(directory, name)
                    if (file.isFile && file.length() <= 1024 * 1024) compatible(file.readText()) else null
                }.getOrNull()
            }
        val previous = context.getSharedPreferences("local_study_state", Context.MODE_PRIVATE)
            .getString("state_v1", null)
        val seed = legacy ?: compatible(previous)
        val edit = simple.edit().putBoolean("seeded", true)
        if (seed != null) edit.putString("state_v1", seed)
        edit.commit()
        return simple
    }

    private fun compatible(raw: String?): String? = runCatching {
        if (raw == null) return null
        val state = JSONObject(raw)
        val storedGame = state.optString("gameId").matches(Regex("[A-Za-z0-9]{8,16}"))
        // Imported arbitrary roots are preserved but cannot be analyzed correctly by
        // the old standard-start gateway client. Leave them out of the simple board.
        if (state.optInt("v") == 1 && (state.optString("initialFen") == START_FEN || storedGame)) raw else null
    }.getOrNull()
}
