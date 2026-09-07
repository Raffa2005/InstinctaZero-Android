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
    private val positionCache = RepertoireLookupCache()
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
        const val MAX_BYTES = 128L * 1024 * 1024
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
                    require(total <= MAX_BYTES) { "Repertoire is larger than 128 MiB" }
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
                positionCache.clear()
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
        finally { positionCache.clear() }
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

    private fun overrides(rep: String): JSONObject = edits.optJSONObject(rep) ?: JSONObject()
    private fun checkedMoves(request: JSONObject): List<String> {
        val raw = request.getJSONArray("history")
        require(raw.length() <= 512)
        return (0 until raw.length()).map { raw.getString(it).also { move -> require(move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) } }
    }
    private fun positionHistory(request: JSONObject, moves: List<String>): List<String> {
        val entries = request.optJSONArray("entries") ?: return if(moves.isEmpty())listOf(position(request.getString("root"))) else emptyList()
        if (entries.length()!=moves.size) return emptyList()
        return listOf(position(request.getString("root"))) + (0 until entries.length()).map {
            position(entries.getJSONObject(it).getString("fen").also { fen -> require(fen.length<=100) })
        }
    }
    @Synchronized fun lookup(request: JSONObject): JSONObject {
        val moves = checkedMoves(request); val fen = position(request.getString("fen"))
        val history = positionHistory(request,moves)
        val selected = request.getJSONArray("selected")
        require(selected.length() <= 16)
        val results = JSONArray()
        if (!file.isFile) return JSONObject().put("results", results)
        open().use { db ->
            for (index in 0 until selected.length()) {
                val rep = selected.getString(index)
                val book by lazy { RepertoirePositionBook(db,rep,overrides(rep)) }
                val current = positionCache.get(rep,fen) ?: run {
                    val identity = identity(db,rep)
                    book.status(fen).put("id",rep).put("name",identity.first).put("side",identity.second)
                        .put("moves",JSONArray(book.edges(fen).map { book.moveJson(it,identity.second) }))
                        .also { positionCache.put(rep,fen,it) }
                }
                // Identical positions may have different departure points and missing local
                // prefixes. Recompute these from this request, even on a position-cache hit.
                val anchor = if(current.getBoolean("theory")) -1 else history.indexOfLast(book::active)
                val additions = if(current.getBoolean("theory"))emptyList() else book.additions(history,moves,request.optJSONArray("entries") ?: JSONArray(),anchor)
                if (!current.getBoolean("theory") && moves.isNotEmpty()) {
                    current.put("deviation",if(anchor>=0)anchor+1 else 1)
                }
                results.put(current.put("can_add",!additions.isNullOrEmpty()).put("add_count",additions?.size ?: 0))
            }
        }
        return JSONObject().put("results",results)
    }
    private fun identity(db: SQLiteDatabase, rep: String): Pair<String, String> = db.rawQuery("SELECT name,side FROM repertoires WHERE id=?", arrayOf(rep)).use {
        require(it.moveToFirst()) { "Repertoire is not installed" }; it.getString(0) to it.getString(1)
    }
    @Synchronized fun edit(request: JSONObject) {
        val rep = request.getString("id"); val moves = checkedMoves(request)
        require(moves.isNotEmpty()) { "Choose a move first" }
        val kind = request.getString("kind")
        require(kind in listOf("analysis", "alternative", "reset", "add"))
        val before = edits.toString()
        val previousLocal = JSONObject(overrides(rep).toString())
        var repertoireName = rep
        var removedAddition = false
        try {
            open().use { db ->
                val identity = identity(db, rep); repertoireName = identity.first
                val local = overrides(rep); edits.put(rep, local)
                val book = RepertoirePositionBook(db,rep,local)
                if (kind == "add") {
                    val entries = request.getJSONArray("entries")
                    require(entries.length() == moves.size)
                    val additions = book.additions(positionHistory(request,moves),moves,entries)
                    require(additions!=null) { "This extension crosses an informational or excluded move. Restore the excluded move or review its PC annotations first." }
                    require(additions.isNotEmpty()) { "This line is already in the repertoire." }
                    for (addition in additions) {
                        local.put(RepertoirePositionBook.edgeKey(addition.before,addition.uci),JSONObject().put("added",true).put("scope","position")
                            .put("kind","repertoire").put("before",addition.before).put("fen",addition.fen).put("uci",addition.uci).put("san",addition.san))
                    }
                } else {
                    // The adjustment menu describes an outgoing move from request.fen, even
                    // when that move was discovered through a different source move order.
                    val beforeFen = position(request.getString("fen")); val uci = moves.last()
                    val key = RepertoirePositionBook.edgeKey(beforeFen,uci)
                    val edge = book.edges(beforeFen).find { it.uci==uci }
                    if (kind=="reset") {
                        removedAddition = local.optJSONObject(key)?.optBoolean("added") == true || edge?.nodes?.any { it.added } == true
                        local.remove(key)
                        edge?.nodes?.forEach { if(local.optJSONObject(it.path)?.optString("scope")!="position")local.remove(it.path) }
                    } else {
                        require(edge!=null) { "Add this move before adjusting it" }
                        if (kind == "alternative") {
                            require(beforeFen.split(' ')[1] == if(identity.second=="white")"w" else "b") { "Only your repertoire colour introduces alternatives" }
                            require(edge.active) { "Only active theory can be an alternative" }
                        }
                        local.put(key,(local.optJSONObject(key) ?: JSONObject()).put("scope","position").put("kind",kind)
                            .put("before",beforeFen).put("fen",edge.fen).put("uci",uci).put("san",edge.san))
                    }
                }
            }
            val after = overrides(rep)
            val keys = (previousLocal.keys().asSequence().toList() + after.keys().asSequence().toList()).toSortedSet()
            val changes = JSONArray()
            for (key in keys) if (canonical(previousLocal.opt(key)) != canonical(after.opt(key))) {
                changes.put(JSONObject().put("path", key).put("before", previousLocal.opt(key) ?: JSONObject.NULL))
            }
            if (changes.length() == 0) { restoreEdits(before); return } // A no-op must not consume the previous undo.
            require(changes.length() <= 512) { "This edit affects too many saved entries to undo safely." }
            val label = when (kind) {
                "add" -> if (changes.length() == 1) "Added move" else "Added ${changes.length()} moves"
                "analysis" -> "Excluded branch"
                "alternative" -> "Made optional alternative"
                else -> if (removedAddition) "Removed local addition" else "Restored original label"
            }
            val record = JSONObject().put("v", 1).put("token", UUID.randomUUID().toString())
                .put("repertoire", rep).put("name", repertoireName).put("label", label)
                .put("had_repertoire", JSONObject(before).has(rep)).put("changes", changes).put("after_hash", fingerprint(edits))
            persistEdits(record)
            undoRecord = record
        } catch (error: Exception) {
            restoreEdits(before)
            throw error
        } finally { positionCache.clear() }
    }
}
