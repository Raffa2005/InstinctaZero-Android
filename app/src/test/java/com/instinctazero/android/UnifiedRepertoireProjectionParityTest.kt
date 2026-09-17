package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Deterministic artificial source-context/override combinations, not private repertoire data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UnifiedRepertoireProjectionParityTest {
    private fun context():Context {
        val app=RuntimeEnvironment.getApplication();val dir=java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(),"parity-").toFile()
        return object:ContextWrapper(app) {
            override fun getFilesDir()=dir
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(dir.name+name,mode)
        }
    }
    private fun request(line:List<String>):JSONObject {
        var fen=RepertoireStore.START_POSITION;val entries=JSONArray()
        for(uci in line){val move=RepertoireLegalMoves.from(fen).single {it.uci==uci};entries.put(JSONObject().put("san",RepertoireLegalMoves.san(fen,uci)).put("fen",move.fen));fen=move.fen}
        return JSONObject().put("root",RepertoireStore.START_POSITION).put("fen",fen).put("history",JSONArray(line)).put("entries",entries).put("selected",JSONArray().put("artificial")).put("intersections",true)
    }
    @Test fun occurrenceMasksRecommendationsNotesAndRepeatedPositionsMatchReleasedReader() {
        val lines=listOf("g1f3 g8f6 g2g3 g7g6 f1g2 f8g7 d2d4 d7d5", "g2g3 g7g6 f1g2 f8g7 g1f3 g8f6 d2d4 d7d5", "g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 g2g3 g7g6")
            .map {it.split(' ')}
        val requests=lines.flatMap {line ->(0..line.size).map {request(line.take(it))}}
        repeat(12) {seed ->
            val random=Random(seed);val oldContext=context();val freshContext=context()
            val source=File(oldContext.filesDir,"mobile_repertoire.sqlite")
            val overrides=JSONObject()
            SQLiteDatabase.openOrCreateDatabase(source,null).use {db ->
                db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO meta VALUES('schema_version','1')")
                db.execSQL("CREATE TABLE repertoires(id TEXT PRIMARY KEY,name TEXT,side TEXT,pgn TEXT)");db.execSQL("INSERT INTO repertoires VALUES('artificial','Artificial','black','artificial.pgn')")
                db.execSQL("CREATE TABLE nodes(id INTEGER PRIMARY KEY,parent_id INTEGER,repertoire_id TEXT,path_id TEXT,uci TEXT,san TEXT,fen TEXT,fen_before TEXT,theory INTEGER,line_alternative INTEGER,kind TEXT,reason TEXT,comment TEXT,starting_comment TEXT,srs INTEGER)")
                db.execSQL("CREATE INDEX nodes_fen ON nodes(repertoire_id,fen)");db.execSQL("CREATE INDEX nodes_before ON nodes(repertoire_id,fen_before)");db.execSQL("CREATE INDEX nodes_training ON nodes(repertoire_id,srs)")
                var id=1
                repeat(4) {copy ->for(line in lines) {
                    var parent:Int?=null;var before="";var active=true;var optional=false
                    for(ply in 0..line.size) {
                        val req=request(line.take(ply));val fen=req.getString("fen");val uci=line.getOrNull(ply-1);val path=RepertoireStore.pathId(RepertoireStore.START_POSITION,line.take(ply))
                        if(ply>1 && random.nextInt(10)==0)active=false
                        if(ply>0 && ply%2==0 && random.nextInt(5)==0)optional=true
                        val san=if(ply==0)"" else req.getJSONArray("entries").getJSONObject(ply-1).getString("san")
                        db.execSQL("INSERT INTO nodes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(id,parent,"artificial",path,uci,san,fen,before,if(active)1 else 0,if(optional)1 else 0,if(active)"repertoire" else "analysis","Synthetic reason $id","Synthetic note ${id%7}",if(ply==2)"Introduction $copy" else "",if(active && ply%2==0)1 else 0))
                        if(ply>0 && random.nextInt(10)==0) {
                            val kind=listOf("analysis","alternative","main","repertoire")[random.nextInt(4)]
                            val entry=JSONObject().put("kind",kind)
                            val key=if(random.nextBoolean())path else RepertoirePositionBook.edgeKey(before,uci!!).also {entry.put("scope","position").put("before",before).put("fen",fen).put("uci",uci).put("san",san)}
                            if(random.nextInt(5)==0)entry.put("deleted",true)
                            overrides.put(key,entry)
                        }
                        parent=id++;before=fen
                    }
                }}
            }
            source.copyTo(File(freshContext.filesDir,"mobile_repertoire.sqlite"))
            val edits=JSONObject().put("artificial",overrides).toString()
            for(ctx in listOf(oldContext,freshContext))File(ctx.filesDir,"mobile_repertoire_edits.json").writeText(edits)
            val old=LegacyRepertoireStore(oldContext);val fresh=RepertoireStore(freshContext)
            requests.forEachIndexed {i,req ->
                assertEquals("seed $seed marker $i",UnifiedRepertoireDatabase.canonical(old.markers(req)),UnifiedRepertoireDatabase.canonical(fresh.markers(req)))
                assertEquals("seed $seed response $i",UnifiedRepertoireDatabase.canonical(old.lookup(req)),UnifiedRepertoireDatabase.canonical(fresh.lookup(req)))
            }
        }
    }
}
