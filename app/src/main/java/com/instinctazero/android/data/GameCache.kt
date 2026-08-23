package com.instinctazero.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small offline mirror. The PC remains the full game archive and source of truth. */
internal class GameCache(
    context: Context,
    private val json: Json = MobileApiClient.defaultJson(),
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), GameStorage {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE games (
                id TEXT PRIMARY KEY NOT NULL,
                created_at_ms INTEGER NOT NULL,
                payload TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE leela_values (
                game_id TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX games_newest ON games(created_at_ms DESC, id DESC)")
        db.execSQL(
            """CREATE TABLE game_details (
                id TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS leela_values")
        db.execSQL("DROP TABLE IF EXISTS game_details")
        db.execSQL("DROP TABLE IF EXISTS games")
        onCreate(db)
    }

    @Synchronized
    override fun storeGames(games: List<GameSummaryDto>) {
        val finished = games.filter { it.status.lowercase() !in ACTIVE_STATUSES }
        writableDatabase.beginTransaction()
        try {
            for (game in finished) {
                val values = ContentValues().apply {
                    put("id", game.id)
                    put("created_at_ms", game.createdAtMillis)
                    put("payload", json.encodeToString(game))
                }
                writableDatabase.insertWithOnConflict(
                    "games",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            writableDatabase.execSQL(
                """DELETE FROM games WHERE id NOT IN (
                    SELECT id FROM games ORDER BY created_at_ms DESC, id DESC LIMIT $MAX_GAMES
                )""".trimIndent(),
            )
            writableDatabase.execSQL("DELETE FROM game_details WHERE id NOT IN (SELECT id FROM games)")
            writableDatabase.execSQL("DELETE FROM leela_values WHERE game_id NOT IN (SELECT id FROM games)")
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    override fun loadGames(limit: Int): List<GameSummaryDto> {
        val result = mutableListOf<GameSummaryDto>()
        readableDatabase.query(
            "games",
            arrayOf("payload"),
            null,
            null,
            null,
            null,
            "created_at_ms DESC, id DESC",
            limit.coerceIn(1, MAX_GAMES).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                decode<GameSummaryDto>(cursor.getString(0))?.let(result::add)
            }
        }
        return result
    }

    @Synchronized
    override fun storeGame(game: GameDetailDto) {
        if (game.status.lowercase() in ACTIVE_STATUSES) return
        val values = ContentValues().apply {
            put("id", game.id)
            put("payload", json.encodeToString(game))
        }
        writableDatabase.insertWithOnConflict(
            "game_details",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun loadGame(gameId: String): GameDetailDto? {
        readableDatabase.query(
            "game_details",
            arrayOf("payload"),
            "id = ?",
            arrayOf(gameId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) decode(cursor.getString(0)) else null
        }
    }

    @Synchronized
    override fun storeValues(gameId: String, values: LeelaValuesDto) {
        if (values.values.isEmpty()) return
        val row = ContentValues().apply {
            put("game_id", gameId)
            put("payload", json.encodeToString(values.copy(cached = true)))
        }
        writableDatabase.insertWithOnConflict(
            "leela_values",
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun loadValues(gameId: String): LeelaValuesDto? {
        readableDatabase.query(
            "leela_values",
            arrayOf("payload"),
            "game_id = ?",
            arrayOf(gameId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return if (cursor.moveToFirst()) decode(cursor.getString(0)) else null
        }
    }

    @Synchronized
    override fun clear() {
        writableDatabase.delete("leela_values", null, null)
        writableDatabase.delete("game_details", null, null)
        writableDatabase.delete("games", null, null)
    }

    private inline fun <reified T> decode(value: String): T? = try {
        json.decodeFromString<T>(value)
    } catch (_: SerializationException) {
        null
    }

    companion object {
        private const val DATABASE_NAME = "instinctazero_mobile_cache.db"
        private const val DATABASE_VERSION = 2
        private const val MAX_GAMES = 250
        private val ACTIVE_STATUSES = setOf("created", "started")
    }
}
