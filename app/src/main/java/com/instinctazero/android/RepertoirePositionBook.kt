package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

/** Stable identities and compatibility value types; all live reads use RepertoireIndexedBook. */
internal object RepertoirePositionBook {
    fun readColumns(db: SQLiteDatabase, cancellation: CancellationSignal? = null): Set<String> =
        db.rawQuery("PRAGMA table_info(nodes)", null, cancellation).use { rows ->
            buildSet { while (rows.moveToNext()) { cancellation?.throwIfCanceled(); add(rows.getString(1)) } }
        }
    fun edgeKey(before: String, uci: String) = RepertoireStore.hash(
        "repertoire-edge-v1\n${RepertoireStore.position(before)}\n$uci".toByteArray()).take(32)
    fun commentKey(fen: String) = RepertoireStore.hash("repertoire-comment-v1\n${RepertoireStore.position(fen)}".toByteArray()).take(32)
    data class Edge(val uci: String, val san: String, val fen: String, val before: String,
        val active: Boolean, val optional: Boolean, val kind: String, val reason: String,
        val edited: Boolean, val reentry: Boolean = false)
    data class Addition(val before: String, val fen: String, val uci: String, val san: String, val anchored: Boolean = false, val ply: Int = 0)
}
