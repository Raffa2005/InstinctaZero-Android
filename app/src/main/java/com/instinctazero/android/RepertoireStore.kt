package com.instinctazero.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import android.os.CancellationSignal
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
    private val markerCache = RepertoireLookupCache(512*1024,1024)
    private val activityCache = RepertoireActivityCache()
    private fun clearCaches() { positionCache.clear();markerCache.clear();activityCache.clear() }
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
        const val START_POSITION = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
        fun position(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")
        fun pathId(root: String, moves: List<String>): String = hash(("repertoire-path-v1\n${position(root)}\n${moves.joinToString(" ")}").toByteArray()).take(32)
        fun hash(bytes: ByteArray): String {
            val hex="0123456789abcdef"
            return buildString(64) { for(byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val value=byte.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])
            } }
        }
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
                clearCaches()
                prefs.edit().putString("fingerprint", expected).apply()
            }
        } finally { temporary.delete() }
    }

    private fun open(path: File = file) = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
    private fun source() = if(file.isFile) open() else null
    private fun localLibrary() = edits.optJSONObject("_local_repertoires") ?: JSONObject()
    private fun localRoot(rep: String) = localLibrary().optJSONObject(rep)?.optString("root")
    /** Metadata shares the atomic edits file, but creating/renaming an empty book does
     * not discard the user's last reversible line/comment edit. */
    @Synchronized fun saveRepertoire(request: JSONObject): JSONObject {
        val name = request.getString("name").trim()
        require(name.isNotEmpty() && name.length<=80) { "Use a repertoire name of 1–80 characters." }
        val before = edits.toString(); val previousUndo = undoRecord?.toString()
        try {
            val library = localLibrary()
            val requestedId = request.optString("id")
            val id = requestedId.ifBlank { "local_" + UUID.randomUUID().toString().replace("-","") }
            val item = if(requestedId.isNotBlank()) library.optJSONObject(id)
                ?: throw IllegalArgumentException("Only phone-created repertoires can be renamed here.")
            else {
                require(library.length()<64) { "You can create up to 64 repertoires on this phone." }
                val side = request.getString("side")
                require(side in listOf("white","black")) { "Choose White or Black." }
                JSONObject().put("side",side).put("root",START_POSITION)
            }
            library.put(id,item.put("name",name)); edits.put("_local_repertoires",library)
            if(undoRecord?.optString("repertoire")==id) undoRecord?.put("name",name)
            undoRecord?.put("after_hash",fingerprint(edits)); persistEdits(undoRecord)
            return catalog().put("created_id",id)
        } catch(error: Exception) {
            restoreEdits(before); undoRecord = previousUndo?.let(::JSONObject); throw error
        } finally { clearCaches() }
    }
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
        finally { clearCaches() }
    }
    @Synchronized fun settings(): String = edits.optJSONObject("_settings")?.toString() ?: prefs.getString("settings", "{}") ?: "{}"
    @Synchronized fun saveSettings(raw: String) {
        require(raw.length <= 32 * 1024)
        val before=edits.toString();val oldUndo=undoRecord?.toString()
        try {
            edits.put("_settings",JSONObject(raw));undoRecord?.put("after_hash",fingerprint(edits));persistEdits(undoRecord)
            prefs.edit().putString("settings", JSONObject(raw).toString()).apply()
        } catch(error: Exception) { restoreEdits(before);undoRecord=oldUndo?.let(::JSONObject);throw error }
    }
    /** Only repertoire edits, their last Undo and selection settings. Never credentials or games. */
    @Synchronized fun backupSnapshot(): JSONObject {
        val saved=JSONObject(edits.toString())
        undoRecord?.let { saved.put("_undo",JSONObject(it.toString())) }
        return JSONObject().put("v",1).put("edits",saved).put("settings",JSONObject(settings()))
            .put("corpus",prefs.getString("fingerprint",""))
    }
    @Synchronized fun restoreBackup(snapshot: JSONObject) {
        require(snapshot.optInt("v")==1 && snapshot.toString().toByteArray().size<=12*1024*1024) { "Unsupported repertoire backup." }
        val replacement=JSONObject(snapshot.getJSONObject("edits").toString())
        val settings=snapshot.getJSONObject("settings")
        require(settings.toString().length<=32*1024)
        val savedUndo=replacement.remove("_undo") as? JSONObject
        val originalHash=fingerprint(replacement)
        // Validate our own bounded format before replacing the atomic file. Preserve every
        // supported legacy edge field and comment; the corpus itself is never overwritten.
        for(rep in replacement.keys()) {
            require(rep.length<=128 && replacement.optJSONObject(rep)!=null)
            val data=replacement.getJSONObject(rep)
            if(rep=="_settings")continue
            if(rep=="_local_repertoires") {
                require(data.length()<=64)
                for(id in data.keys()) {
                    val item=data.getJSONObject(id)
                    require(id.matches(Regex("local_[a-f0-9]{32}")) && item.getString("name").length in 1..80)
                    require(item.getString("side") in listOf("white","black") && item.getString("root").length<=100)
                }
            } else {
                require(!rep.startsWith("_"))
                for(key in data.keys()) {
                    require(key.matches(Regex("[a-f0-9]{32}")))
                    val edge=data.getJSONObject(key)
                    require(edge.optString("kind") in listOf("","repertoire","analysis","alternative","main"))
                    require(edge.optString("comment").length<=64*1024)
                    if(edge.optBoolean("added")) {
                        require(edge.getString("uci").matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")))
                        require(edge.getString("fen").length<=100 && edge.getString("san").length<=32)
                    }
                }
            }
        }
        val restoredUndo=savedUndo?.takeIf { runCatching {
            it.getInt("v")==1 && it.getString("after_hash")==originalHash &&
                listOf("token","repertoire","name","label").all { key -> it.getString(key).isNotBlank() } &&
                it.getJSONArray("changes").let { changes -> changes.length() in 1..512 && (0 until changes.length()).all { i ->
                    val change=changes.getJSONObject(i)
                    change.getString("path").matches(Regex("[a-f0-9]{32}")) && change.has("before") && (change.isNull("before") || change.optJSONObject("before")!=null)
                } }
        }.getOrDefault(false) }
        replacement.put("_settings",JSONObject(settings.toString()))
        val before=edits.toString();val oldUndo=undoRecord?.toString()
        try {
            restoreEdits(replacement.toString());undoRecord=restoredUndo
            undoRecord?.put("after_hash",fingerprint(edits));persistEdits(undoRecord)
            prefs.edit().putString("settings",settings.toString()).apply()
        } catch(error: Exception) { restoreEdits(before);undoRecord=oldUndo?.let(::JSONObject);throw error }
        finally { clearCaches() }
    }
    @Synchronized fun catalog(): JSONObject {
        val library = localLibrary()
        val result = JSONObject().put("installed", file.isFile || library.length()>0).put("fingerprint", prefs.getString("fingerprint", ""))
        val list = JSONArray()
        if (file.isFile) open().use { db ->
            db.rawQuery("SELECT id,name,side,pgn FROM repertoires ORDER BY id", null).use { rows ->
                while (rows.moveToNext()) list.put(JSONObject().put("id", rows.getString(0)).put("name", rows.getString(1))
                    .put("side", rows.getString(2)).put("file", rows.getString(3)))
            }
        }
        library.keys().forEach { id ->
            val item = library.getJSONObject(id)
            list.put(JSONObject().put("id",id).put("name",item.getString("name")).put("side",item.getString("side")).put("file","").put("local",true))
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
    @Synchronized fun markers(request: JSONObject,cancellation: CancellationSignal?=null): JSONObject {
        val fen=position(request.getString("fen"));val selected=request.getJSONArray("selected");require(selected.length()<=16)
        val results=JSONArray()
        source().use { db ->
            for(i in 0 until selected.length()) {
                cancellation?.throwIfCanceled();val rep=selected.getString(i)
                val result=markerCache.get(rep,fen) ?: RepertoirePositionBook(db,rep,overrides(rep),localRoot(rep),cancellation,activityCache).marker(fen)
                    .put("id",rep).also { markerCache.put(rep,fen,it) }
                results.put(result)
            }
        }
        return JSONObject().put("results",results)
    }
    @Synchronized fun lookup(request: JSONObject, cancellation: CancellationSignal? = null): JSONObject {
        cancellation?.throwIfCanceled()
        val moves = checkedMoves(request); val fen = position(request.getString("fen"))
        val history = positionHistory(request,moves)
        val selected = request.getJSONArray("selected")
        require(selected.length() <= 16)
        val results = JSONArray()
        if (!file.isFile && localLibrary().length()==0) return JSONObject().put("results", results)
        source().use { db ->
            for (index in 0 until selected.length()) {
                cancellation?.throwIfCanceled()
                val rep = selected.getString(index)
                val book by lazy { RepertoirePositionBook(db,rep,overrides(rep),localRoot(rep),cancellation,activityCache) }
                val current = positionCache.get(rep,fen) ?: run {
                    val identity = identity(db,rep)
                    book.status(fen).put("id",rep).put("name",identity.first).put("side",identity.second)
                        .put("moves",JSONArray(book.edges(fen).filterNot(book::deleted).map { book.moveJson(it,identity.second) }))
                        .put("deleted_moves",JSONArray(book.edges(fen).filter(book::deleted).map { book.moveJson(it,identity.second) }))
                        .also { positionCache.put(rep,fen,it) }
                }
                // Identical positions may have different departure points and missing local
                // prefixes. Recompute these from this request, even on a position-cache hit.
                activityCache.put(rep,fen,current.getBoolean("theory"))
                if(!current.getBoolean("theory"))book.prepareHistory(history)
                val anchor = if(current.getBoolean("theory")) -1 else history.indexOfLast(book::active)
                val additions = if(current.getBoolean("theory"))emptyList() else book.additions(history,moves,request.optJSONArray("entries") ?: JSONArray(),anchor)
                if (!current.getBoolean("theory") && moves.isNotEmpty()) {
                    current.put("deviation",if(anchor>=0)anchor+1 else 1)
                }
                val before=history.getOrNull(moves.size-1)
                val canAddMove=before!=null && moves.isNotEmpty() && book.canAddMove(before,moves.last())
                current.put("can_add_move",canAddMove).put("add_move_independent",canAddMove && !book.active(before!!))
                if(request.optBoolean("intersections"))current.put("intersection",book.intersection(history,moves))
                results.put(current.put("can_add",!additions.isNullOrEmpty()).put("add_count",additions?.size ?: 0))
            }
        }
        cancellation?.throwIfCanceled()
        return JSONObject().put("results",results)
    }
    private fun identity(db: SQLiteDatabase?, rep: String): Pair<String, String> {
        localLibrary().optJSONObject(rep)?.let { return it.getString("name") to it.getString("side") }
        require(db!=null) { "Repertoire is not installed" }
        return db.rawQuery("SELECT name,side FROM repertoires WHERE id=?", arrayOf(rep)).use {
        require(it.moveToFirst()) { "Repertoire is not installed" }; it.getString(0) to it.getString(1)
        }
    }
    @Synchronized fun edit(request: JSONObject) {
        val rep = request.getString("id"); val moves = checkedMoves(request)
        val kind = request.getString("kind")
        require(kind in listOf("analysis", "alternative", "main", "delete", "restore_move", "reset", "add", "add_move", "comment", "reset_comment"))
        require(moves.isNotEmpty() || kind in listOf("comment","reset_comment")) { "Choose a move first" }
        val before = edits.toString()
        val previousLocal = JSONObject(overrides(rep).toString())
        var repertoireName = rep
        var removedAddition = false
        try {
            source().use { db ->
                val identity = identity(db, rep); repertoireName = identity.first
                val local = overrides(rep); edits.put(rep, local)
                val book = RepertoirePositionBook(db,rep,local,localRoot(rep))
                if(kind in listOf("comment","reset_comment")) {
                    val fen = position(request.getString("fen"))
                    require(fen.length<=100 && book.status(fen).getBoolean("known")) { "Add the line before commenting on it." }
                    val key = RepertoirePositionBook.commentKey(fen)
                    if(kind=="reset_comment") local.remove(key)
                    else {
                        val text = request.getString("comment").replace("\r\n","\n")
                        require(text.length<=64*1024) { "This comment is too long." }
                        local.put(key,JSONObject().put("scope","comment").put("fen",fen).put("comment",text))
                    }
                } else if (kind == "add_move") {
                    val history=positionHistory(request,moves)
                    require(history.size==moves.size+1 && moves.isNotEmpty()) { "The played move is unavailable." }
                    val parent=history[moves.size-1];val uci=moves.last()
                    require(book.canAddMove(parent,uci)) { "This response already exists, or its parent is not in this repertoire." }
                    val san=request.getJSONArray("entries").getJSONObject(moves.size-1).getString("san").take(16)
                    local.put(RepertoirePositionBook.edgeKey(parent,uci),JSONObject().put("added",true).put("scope","position")
                        .put("kind","repertoire").put("anchored",!book.active(parent)).put("before",parent).put("fen",history.last()).put("uci",uci).put("san",san))
                } else if (kind == "add") {
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
                    if(kind=="delete" || kind=="restore_move") {
                        require(edge!=null) { "Choose a recorded repertoire move." }
                        val value=local.optJSONObject(key) ?: JSONObject().put("scope","position").put("kind","repertoire")
                            .put("before",beforeFen).put("fen",edge.fen).put("uci",uci).put("san",edge.san)
                        if(kind=="delete")value.put("deleted",true) else value.remove("deleted")
                        local.put(key,value)
                    } else if (kind=="reset") {
                        removedAddition = local.optJSONObject(key)?.optBoolean("added") == true || edge?.nodes?.any { it.added } == true
                        local.remove(key)
                        edge?.nodes?.forEach { if(local.optJSONObject(it.path)?.optString("scope")!="position")local.remove(it.path) }
                    } else {
                        require(edge!=null) { "Add this move before adjusting it" }
                        if (kind == "alternative" || kind=="main") {
                            require(beforeFen.split(' ')[1] == if(identity.second=="white")"w" else "b") { "Only your repertoire colour introduces alternatives" }
                            require(edge.active) { "Only active theory can be an alternative" }
                            require(!book.deleted(edge)) { "Restore the deleted move first." }
                            if(kind=="alternative")require(book.edges(beforeFen).any { it.uci!=uci && book.recommendation(it,identity.second)=="main" }) { "Choose another main recommendation first." }
                            if(kind=="main")book.edges(beforeFen).filter { it.uci!=uci }.forEach {
                                local.optJSONObject(RepertoirePositionBook.edgeKey(beforeFen,it.uci))?.takeIf { it.optString("kind")=="main" }?.put("kind","alternative")
                            }
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
                "add", "add_move" -> if (changes.length() == 1) "Added move" else "Added ${changes.length()} moves"
                "main" -> "Chose main recommendation"
                "delete" -> "Deleted repertoire move"
                "restore_move" -> "Restored repertoire move"
                "analysis" -> "Excluded branch"
                "alternative" -> "Made optional alternative"
                "comment" -> "Edited comment"
                "reset_comment" -> "Restored source comment"
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
        } finally { clearCaches() }
    }
}
