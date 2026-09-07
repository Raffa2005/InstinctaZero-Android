package com.instinctazero.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Private indexed corpus, loaded on demand. Source PGNs and the PC index are read-only. */
internal class RepertoireStore(context: Context) {
    private val file = File(context.filesDir, "mobile_repertoire.sqlite")
    private val prefs = context.getSharedPreferences("mobile_repertoire_preferences", Context.MODE_PRIVATE)
    private val editsFile = AtomicFile(File(context.filesDir, "mobile_repertoire_edits.json"))
    private var undoRecord: JSONObject? = null
    private val edits: JSONObject by lazy {
        val saved = runCatching { JSONObject(String(editsFile.readFully(), Charsets.UTF_8)) }.getOrDefault(JSONObject())
        val record = saved.remove("_undo") as? JSONObject
        // Older versions can still read the repertoire keys. If one changed the edits without
        // updating the journal, do not present a stale undo after upgrading again.
        undoRecord = record?.takeIf { runCatching {
            it.optInt("v") == 1 && listOf("token", "repertoire", "name", "label").all { key -> it.getString(key).isNotBlank() } &&
                it.getJSONArray("changes").let { changes -> changes.length() in 1..512 && (0 until changes.length()).all { i ->
                    val change = changes.getJSONObject(i)
                    change.getString("path").matches(Regex("[a-f0-9]{32}")) && change.has("before") && (change.isNull("before") || change.optJSONObject("before") != null)
                } } && it.optString("after_hash") == fingerprint(saved)
        }.getOrDefault(false) }
        saved
    }

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
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun fingerprint(value: JSONObject): String = hash(canonical(value).toByteArray(Charsets.UTF_8))
    private fun restoreEdits(raw: String) {
        edits.keys().asSequence().toList().forEach(edits::remove)
        val old = JSONObject(raw); old.keys().forEach { edits.put(it, old.get(it)) }
    }
    private fun persistEdits(record: JSONObject?) {
        val content = edits.toString()
        require(content.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Local edits have reached the 4 MiB limit" }
        val saved = JSONObject(content)
        if (record != null) saved.put("_undo", record)
        val output = editsFile.startWrite()
        try { output.write(saved.toString().toByteArray(Charsets.UTF_8)); editsFile.finishWrite(output) }
        catch (error: Exception) { editsFile.failWrite(output); throw error }
    }
    @Synchronized fun undoInfo(): JSONObject? {
        edits // Initialize the journal together with the edits on first access.
        return undoRecord?.let { JSONObject().put("token", it.getString("token"))
            .put("repertoire", it.getString("repertoire")).put("name", it.getString("name")).put("label", it.getString("label")) }
    }
    @Synchronized fun undo(token: String) {
        edits
        val record = undoRecord ?: throw IllegalStateException("No repertoire change to undo.")
        require(token == record.getString("token")) { "The last repertoire change has changed. Check the undo label and try again." }
        check(record.getString("after_hash") == fingerprint(edits)) { "The repertoire changed outside this undo step." }
        val before = edits.toString()
        try {
            val rep = record.getString("repertoire")
            val local = overrides(rep)
            val changes = record.getJSONArray("changes")
            for (i in 0 until changes.length()) {
                val change = changes.getJSONObject(i); val key = change.getString("path")
                if (change.isNull("before")) local.remove(key)
                else local.put(key, JSONObject(change.getJSONObject("before").toString()))
            }
            if (local.length() == 0 && !record.optBoolean("had_repertoire")) edits.remove(rep) else edits.put(rep, local)
            // State and the consumed undo record commit in one AtomicFile transaction.
            persistEdits(null)
            undoRecord = null
        } catch (error: Exception) { restoreEdits(before); throw error }
    }
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
        return result.put("repertoires", list).put("undo", undoInfo() ?: JSONObject.NULL)
    }

    private data class Node(val uci: String, val san: String, val kind: String, val theory: Boolean,
        val alternative: Boolean, val reason: String, val comment: String, val fen: String)
    private fun comments(nodes: List<Node>): JSONArray = JSONArray(nodes.map { it.comment }.filter { it.isNotBlank() }.distinct())

    /** A position badge is discovery only, never permission to reactivate the actual history.
     * Only another active, locally unmasked source path qualifies as a transposition match.
     */
    private fun positionMatches(db: SQLiteDatabase, rep: String, fen: String, currentPath: String): Int {
        val local = overrides(rep)
        val excluded = local.keys().asSequence().filter { local.getJSONObject(it).optString("kind") == "analysis" }.toSet()
        fun unmasked(id: Long): Boolean {
            if (excluded.isEmpty()) return true
            var next = id
            repeat(513) {
                if (next == 0L) return true
                db.rawQuery("SELECT path_id,parent_id FROM nodes WHERE id=?", arrayOf(next.toString())).use { row ->
                    if (!row.moveToFirst() || row.getString(0) in excluded) return false
                    next = if (row.isNull(1)) 0 else row.getLong(1)
                }
            }
            return false
        }
        val eligible = mutableSetOf<String>()
        db.rawQuery("SELECT id,path_id FROM nodes WHERE repertoire_id=? AND fen=? AND theory=1", arrayOf(rep, position(fen))).use { rows ->
            while (rows.moveToNext()) {
                val key = rows.getString(1)
                if (key != currentPath && key !in eligible && unmasked(rows.getLong(0))) eligible.add(key)
            }
        }
        // Local extensions participate too, but a removed/excluded ancestor cannot become a
        // back door into theory merely because its descendant still has a stored FEN.
        fun activeLocalPath(key: String): Boolean {
            var next = key
            repeat(513) {
                if (next in excluded) return false
                val edit = local.optJSONObject(next)
                if (edit?.optBoolean("added") == true) next = edit.optString("parent")
                else {
                    db.rawQuery("SELECT id FROM nodes WHERE repertoire_id=? AND path_id=? AND theory=1", arrayOf(rep, next)).use { rows ->
                        while (rows.moveToNext()) if (unmasked(rows.getLong(0))) return true
                    }
                    return false
                }
            }
            return false
        }
        local.keys().forEach { key ->
            val edit = local.getJSONObject(key)
            if (key != currentPath && edit.optBoolean("added") && position(edit.optString("fen")) == position(fen) && activeLocalPath(key)) eligible.add(key)
        }
        return eligible.size
    }
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
    private fun children(db: SQLiteDatabase, rep: String, root: String, moves: List<String>): Map<String, List<Node>> {
        val result = source(db, rep, root, moves, true).groupBy { it.uci }.toMutableMap()
        val parent = pathId(root, moves)
        val local = overrides(rep)
        local.keys().forEach { key ->
            val edit = local.getJSONObject(key)
            if (edit.optBoolean("added") && edit.optString("parent") == parent) {
                val uci = edit.getString("uci")
                if (!result.containsKey(uci)) result[uci] = listOf(Node(uci, edit.getString("san"), "repertoire", true, false, "Added on this phone", "", edit.getString("fen")))
            }
        }
        return result.filterKeys { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) }
    }
    /** Call only for an active parent. Informational tails do not extend its theory. */
    private fun hasTheoryChild(db: SQLiteDatabase, rep: String, root: String, moves: List<String>): Boolean {
        val local = overrides(rep)
        return children(db, rep, root, moves).any { (uci, occurrences) ->
            occurrences.any { it.theory } && local.optJSONObject(pathId(root, moves + uci))?.optString("kind") != "analysis"
        }
    }
    /** -1 is blocked, 0 already present, otherwise the number of missing moves to add.
     * Check the whole path so neither a last-ply exclusion nor a source information bridge
     * can be silently promoted. The same check powers the UI and the atomic write.
     */
    private fun extensionSize(db: SQLiteDatabase, rep: String, root: String, moves: List<String>): Int {
        val local = overrides(rep)
        if (source(db, rep, root, emptyList()).none { it.theory } || local.optJSONObject(pathId(root, emptyList()))?.optString("kind") == "analysis") return -1
        var missing = 0
        for (ply in 1..moves.size) {
            val path = moves.take(ply)
            val original = source(db, rep, root, path)
            val edit = local.optJSONObject(pathId(root, path))
            if (edit?.optString("kind") == "analysis" || (original.isNotEmpty() && original.none { it.theory })) return -1
            if (original.isEmpty() && edit?.optBoolean("added") != true) missing++
        }
        return missing
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
                val currentNodes = source(db, rep, root, moves)
                val children = children(db, rep, root, moves)
                val local = overrides(rep)
                val lines = JSONArray()
                var hasContinuation = false
                for ((uci, occurrences) in children) {
                    if (!uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) continue
                    val child = state(db, rep, identity.second, root, moves + uci, memo)
                    val best = occurrences.firstOrNull { it.theory && !it.alternative } ?: occurrences.firstOrNull { it.theory } ?: occurrences.first()
                    hasContinuation = hasContinuation || child.theory
                    lines.put(JSONObject().put("uci", uci).put("san", best.san).put("kind", child.kind).put("theory", child.theory)
                        .put("alternative", child.alternative).put("own", ownMove(identity.second, root, moves.size + 1))
                        .put("deviation", child.deviation)
                        .put("end_of_line", child.theory && !hasTheoryChild(db, rep, root, moves + uci))
                        .put("position_match", !child.theory && positionMatches(db, rep, best.fen, pathId(root, moves + uci)) > 0)
                        .put("reason", child.reason).put("comment", best.comment).put("comments", comments(occurrences))
                        .put("edited", local.has(pathId(root, moves + uci))))
                }
                val candidates = if (!current.theory) positionMatches(db, rep, request.getString("fen"), pathId(root, moves)) else 0
                val additions = if (current.theory) 0 else extensionSize(db, rep, root, moves)
                results.put(JSONObject().put("id", rep).put("name", identity.first).put("side", identity.second)
                    .put("known", current.known).put("theory", current.theory).put("alternative", current.alternative)
                    .put("kind", current.kind).put("reason", current.reason).put("deviation", current.deviation)
                    .put("candidates", candidates).put("position_match", candidates > 0)
                    .put("end_of_line", current.theory && !hasContinuation)
                    .put("can_add", additions > 0).put("add_count", maxOf(0, additions))
                    .put("comments", comments(currentNodes)).put("moves", lines))
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
        val previousLocal = JSONObject(overrides(rep).toString())
        var repertoireName = rep
        try {
            open().use { db ->
                val identity = identity(db, rep)
                val side = identity.second; repertoireName = identity.first
                val local = overrides(rep); edits.put(rep, local)
                val key = pathId(root, moves)
                if (kind == "reset") local.remove(key)
                else if (kind == "add") {
                    val entries = request.getJSONArray("entries")
                    require(entries.length() == moves.size)
                    val additions = extensionSize(db, rep, root, moves)
                    require(additions >= 0) { "This line crosses an informational or excluded move. Restore the excluded branch or review its PC annotations first." }
                    require(additions > 0) { "This line is already in the repertoire." }
                    for (ply in 1..moves.size) {
                        val path = moves.take(ply)
                        val original = source(db, rep, root, path)
                        if (original.isNotEmpty()) continue
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
            val after = overrides(rep)
            val keys = (previousLocal.keys().asSequence().toList() + after.keys().asSequence().toList()).toSortedSet()
            val changes = JSONArray()
            for (key in keys) if (canonical(previousLocal.opt(key)) != canonical(after.opt(key))) {
                changes.put(JSONObject().put("path", key).put("before", previousLocal.opt(key) ?: JSONObject.NULL))
            }
            if (changes.length() == 0) { restoreEdits(before); return } // A no-op must not consume the previous undo.
            val label = when (kind) {
                "add" -> if (changes.length() == 1) "Added move" else "Added ${changes.length()} moves"
                "analysis" -> "Excluded branch"
                "alternative" -> "Made optional alternative"
                else -> if (previousLocal.optJSONObject(pathId(root, moves))?.optBoolean("added") == true) "Removed local addition" else "Restored original label"
            }
            val record = JSONObject().put("v", 1).put("token", UUID.randomUUID().toString())
                .put("repertoire", rep).put("name", repertoireName).put("label", label)
                .put("had_repertoire", JSONObject(before).has(rep)).put("changes", changes).put("after_hash", fingerprint(edits))
            persistEdits(record)
            undoRecord = record
        } catch (error: Exception) {
            restoreEdits(before)
            throw error
        }
    }
}
