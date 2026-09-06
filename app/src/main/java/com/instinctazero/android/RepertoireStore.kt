package com.instinctazero.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Private indexed corpus, loaded on demand. Source PGNs and the PC index are read-only. */
internal class RepertoireStore(context: Context) {
    private val file = File(context.filesDir, "mobile_repertoire.sqlite")
    private val prefs = context.getSharedPreferences("mobile_repertoire_preferences", Context.MODE_PRIVATE)
    private val editsFile = AtomicFile(File(context.filesDir, "mobile_repertoire_edits.json"))
    private val edits: JSONObject by lazy { runCatching { JSONObject(String(editsFile.readFully(), Charsets.UTF_8)) }.getOrDefault(JSONObject()) }

    companion object {
        const val MAX_BYTES = 80L * 1024 * 1024
        fun position(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")
        fun pathId(root: String, moves: List<String>): String = hash(("repertoire-path-v1\n${position(root)}\n${moves.joinToString(" ")}").toByteArray()).take(32)
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun install(input: InputStream, expected: String) {
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "Missing repertoire fingerprint" }
        val temporary = File(file.parentFile, "mobile_repertoire.download")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Repertoire is larger than 80 MiB" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Repertoire download is incomplete" }
            open(temporary).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
                db.rawQuery("SELECT value FROM meta WHERE key='schema_version'", null).use { require(it.moveToFirst() && it.getString(0) == "1") }
                db.rawQuery("SELECT id,name,side,pgn FROM repertoires LIMIT 1", null).use { require(it.moveToFirst()) }
                db.rawQuery("SELECT parent_id,path_id,uci,kind,theory,line_alternative,fen,comment,reason FROM nodes LIMIT 1", null).use { require(it.moveToFirst()) }
            }
            synchronized(this) {
                check(temporary.renameTo(file)) { "Unable to install repertoire download" }
                prefs.edit().putString("fingerprint", expected).apply()
            }
        } finally { temporary.delete() }
    }

    private fun open(path: File = file) = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
    @Synchronized fun settings(): String = prefs.getString("settings", "{}") ?: "{}"
    @Synchronized fun saveSettings(raw: String) {
        require(raw.length <= 32 * 1024)
        prefs.edit().putString("settings", JSONObject(raw).toString()).apply()
    }
    @Synchronized fun catalog(): JSONObject {
        val result = JSONObject().put("installed", file.isFile).put("fingerprint", prefs.getString("fingerprint", ""))
        val list = JSONArray()
        if (file.isFile) open().use { db ->
            db.rawQuery("SELECT id,name,side,pgn FROM repertoires ORDER BY id", null).use { rows ->
                while (rows.moveToNext()) list.put(JSONObject().put("id", rows.getString(0)).put("name", rows.getString(1))
                    .put("side", rows.getString(2)).put("file", rows.getString(3)))
            }
        }
        return result.put("repertoires", list)
    }

    private data class Node(val uci: String, val san: String, val kind: String, val theory: Boolean,
        val alternative: Boolean, val reason: String, val comment: String, val fen: String)
    private fun source(db: SQLiteDatabase, rep: String, root: String, moves: List<String>, children: Boolean = false): List<Node> {
        val query = if (children) "SELECT uci,san,kind,theory,line_alternative,reason,comment,fen FROM nodes WHERE parent_id IN (SELECT id FROM nodes WHERE repertoire_id=? AND path_id=?)"
            else "SELECT uci,san,kind,theory,line_alternative,reason,comment,fen FROM nodes WHERE repertoire_id=? AND path_id=?"
        return db.rawQuery(query, arrayOf(rep, pathId(root, moves))).use { rows ->
            buildList { while (rows.moveToNext()) add(Node(rows.getString(0) ?: "", rows.getString(1) ?: "", rows.getString(2), rows.getInt(3) == 1,
                rows.getInt(4) == 1, rows.getString(5) ?: "", rows.getString(6) ?: "", rows.getString(7))) }
        }
    }
    private fun overrides(rep: String): JSONObject = edits.optJSONObject(rep) ?: JSONObject()
    private fun ownMove(side: String, root: String, ply: Int): Boolean {
        val startsWhite = position(root).split(" ")[1] == "w"
        val white = if (ply % 2 == 1) startsWhite else !startsWhite
        return (side == "white") == white
    }
    private data class State(val known: Boolean, val theory: Boolean, val alternative: Boolean, val kind: String, val reason: String, val deviation: Int)
    private fun state(db: SQLiteDatabase, rep: String, side: String, root: String, moves: List<String>, memo: MutableMap<String, State> = mutableMapOf()): State {
        var active = true; var optional = false; var known = false; var kind = "unknown"; var reason = "Outside this repertoire"
        var deviation = 0
        val local = overrides(rep)
        for (ply in 0..moves.size) {
            val path = moves.take(ply)
            val cacheKey = path.joinToString(" ")
            memo[cacheKey]?.let { cached ->
                active = cached.theory; optional = cached.alternative; known = cached.known
                kind = cached.kind; reason = cached.reason; deviation = cached.deviation
            }
            if (memo.containsKey(cacheKey)) continue
            val nodes = source(db, rep, root, path)
            val edit = local.optJSONObject(pathId(root, path))
            known = nodes.isNotEmpty() || edit?.optBoolean("added") == true
            val baseActive = nodes.any { it.theory } || edit?.optBoolean("added") == true
            if (ply > 0 && active && (!baseActive || edit?.optString("kind") == "analysis")) deviation = ply
            active = active && baseActive && edit?.optString("kind") != "analysis"
            val best = nodes.firstOrNull { it.theory && !it.alternative } ?: nodes.firstOrNull { it.theory } ?: nodes.firstOrNull()
            optional = optional || edit?.optString("kind") == "alternative" || (best?.alternative == true)
            kind = if (active) { if (optional && ownMove(side, root, ply)) "alternative" else "repertoire" } else best?.kind?.takeUnless { it == "repertoire" || it == "alternative" } ?: "analysis"
            reason = if (edit?.optString("kind") == "analysis") "Excluded on this phone. The original remains unchanged."
                else if (edit?.optBoolean("added") == true) "Added on this phone."
                else best?.reason ?: "Outside the recorded move history."
            memo[cacheKey] = State(known, active, optional, kind, reason, deviation)
        }
        return State(known, active, optional, kind, reason, deviation)
    }
    private fun checkedMoves(request: JSONObject): List<String> {
        val raw = request.getJSONArray("history")
        require(raw.length() <= 512)
        return (0 until raw.length()).map { raw.getString(it).also { move -> require(move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) } }
    }
    @Synchronized fun lookup(request: JSONObject): JSONObject {
        val root = position(request.getString("root")); val moves = checkedMoves(request)
        val selected = request.getJSONArray("selected")
        require(selected.length() <= 16)
        val results = JSONArray()
        if (!file.isFile) return JSONObject().put("results", results)
        open().use { db ->
            for (index in 0 until selected.length()) {
                val rep = selected.getString(index)
                val identity = identity(db, rep)
                val memo = mutableMapOf<String, State>()
                val current = state(db, rep, identity.second, root, moves, memo)
                val children = source(db, rep, root, moves, true).groupBy { it.uci }.toMutableMap()
                val local = overrides(rep)
                local.keys().forEach { key ->
                    val edit = local.getJSONObject(key)
                    if (edit.optBoolean("added") && edit.optString("parent") == pathId(root, moves)) {
                        val uci = edit.getString("uci")
                        if (!children.containsKey(uci)) children[uci] = listOf(Node(uci, edit.getString("san"), "repertoire", true, false, "Added on this phone", "", edit.getString("fen")))
                    }
                }
                val lines = JSONArray()
                for ((uci, occurrences) in children) {
                    if (!uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) continue
                    val child = state(db, rep, identity.second, root, moves + uci, memo)
                    val best = occurrences.firstOrNull { it.theory && !it.alternative } ?: occurrences.firstOrNull { it.theory } ?: occurrences.first()
                    lines.put(JSONObject().put("uci", uci).put("san", best.san).put("kind", child.kind).put("theory", child.theory)
                        .put("alternative", child.alternative).put("own", ownMove(identity.second, root, moves.size + 1))
                        .put("reason", child.reason).put("comment", best.comment.take(4000)).put("edited", local.has(pathId(root, moves + uci))))
                }
                val candidates = if (!current.known) db.rawQuery("SELECT count(*) FROM nodes WHERE repertoire_id=? AND fen=? AND theory=1", arrayOf(rep, position(request.getString("fen")))).use { it.moveToFirst(); it.getInt(0) } else 0
                results.put(JSONObject().put("id", rep).put("name", identity.first).put("side", identity.second)
                    .put("known", current.known).put("theory", current.theory).put("alternative", current.alternative)
                    .put("kind", current.kind).put("reason", current.reason).put("deviation", current.deviation)
                    .put("candidates", candidates).put("moves", lines))
            }
        }
        return JSONObject().put("results", results)
    }
    private fun identity(db: SQLiteDatabase, rep: String): Pair<String, String> = db.rawQuery("SELECT name,side FROM repertoires WHERE id=?", arrayOf(rep)).use {
        require(it.moveToFirst()) { "Repertoire is not installed" }; it.getString(0) to it.getString(1)
    }
    @Synchronized fun edit(request: JSONObject) {
        val rep = request.getString("id"); val root = position(request.getString("root")); val moves = checkedMoves(request)
        require(moves.isNotEmpty()) { "Choose a move first" }
        val kind = request.getString("kind")
        require(kind in listOf("analysis", "alternative", "reset", "add"))
        val before = edits.toString()
        try {
            open().use { db ->
                val side = identity(db, rep).second
                val local = overrides(rep); edits.put(rep, local)
                val key = pathId(root, moves)
                if (kind == "reset") local.remove(key)
                else if (kind == "add") {
                    val entries = request.getJSONArray("entries")
                    require(entries.length() == moves.size)
                    for (ply in 1..moves.size) {
                        val path = moves.take(ply)
                        val original = source(db, rep, root, path)
                        require(state(db, rep, side, root, path.dropLast(1)).theory) { "This line crosses an informational or excluded move. Restore that branch first." }
                        if (original.isNotEmpty()) {
                            require(original.any { it.theory }) { "Informational source lines cannot be silently promoted. Edit and revalidate their PC annotations first." }
                            continue
                        }
                        val entry = entries.getJSONObject(ply - 1)
                        val addedKey = pathId(root, path)
                        if (!local.has(addedKey)) local.put(addedKey, JSONObject().put("added", true).put("kind", "repertoire")
                            .put("parent", pathId(root, path.dropLast(1))).put("uci", path.last()).put("san", entry.getString("san").take(16)).put("fen", entry.getString("fen").take(100)))
                    }
                } else {
                    if (kind == "alternative") {
                        require(ownMove(side, root, moves.size)) { "Only your repertoire colour introduces alternatives" }
                        require(state(db, rep, side, root, moves).theory) { "Only active theory can be an alternative" }
                    }
                    require(source(db, rep, root, moves).isNotEmpty() || local.optJSONObject(key)?.optBoolean("added") == true) { "Add this move before adjusting it" }
                    local.put(key, (local.optJSONObject(key) ?: JSONObject()).put("kind", kind))
                }
            }
            val raw = edits.toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= 4 * 1024 * 1024) { "Local edits have reached the 4 MiB limit" }
            val output = editsFile.startWrite()
            try { output.write(raw); editsFile.finishWrite(output) } catch (error: Exception) { editsFile.failWrite(output); throw error }
        } catch (error: Exception) {
            edits.keys().asSequence().toList().forEach(edits::remove)
            val old = JSONObject(before); old.keys().forEach { edits.put(it, old.get(it)) }
            throw error
        }
    }
}
