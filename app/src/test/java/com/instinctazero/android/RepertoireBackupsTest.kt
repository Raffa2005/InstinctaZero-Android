package com.instinctazero.android

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RepertoireBackupsTest {
    private fun phone(name: String)=object: ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir()=File(super.getFilesDir(),name).apply { mkdirs() }
        override fun getSharedPreferences(key: String,mode: Int)=super.getSharedPreferences("$name-$key",mode)
    }
    private fun await(queue: LinkedBlockingQueue<JSONObject>): JSONObject {
        repeat(20) { val value=queue.poll(5,TimeUnit.SECONDS) ?: fail("Backup did not complete");if(value is JSONObject && !value.getBoolean("busy"))return value }
        error("No completed backup")
    }
    @Test fun replacementPhoneRestoresHistoryAndFailedOrRacingRestoreCannotOverwriteEdits() {
        val old=phone("old");val store=RepertoireStore(old)
        val id=store.saveRepertoire(JSONObject().put("name","My local opening").put("side","black")).getString("created_id")
        store.saveSettings(JSONObject().put("_selected",JSONArray().put(id)).toString())
        val snapshots=linkedMapOf<String,JSONObject>();var failUpload=false;var onLoad:(()->Unit)?=null
        val transport={ version: String,payload: JSONObject? ->
            if(payload!=null) {
                if(failUpload)throw java.io.IOException("offline")
                val key=RepertoireStore.hash(payload.toString().toByteArray());snapshots[key]=JSONObject(payload.toString())
                JSONObject().put("saved",true).put("id",key)
            } else if(version.isNotEmpty()) { onLoad?.invoke();JSONObject().put("snapshot",JSONObject(snapshots.getValue(version).toString())) }
            else JSONObject().put("versions",JSONArray(snapshots.keys.map { JSONObject().put("id",it).put("saved_ms",1234).put("this_device",false) }))
        }
        val queue=LinkedBlockingQueue<JSONObject>();val manager=RepertoireBackups(store,old.getSharedPreferences("backup",0),transport,queue::offer)
        manager.action("save");assertEquals("",await(queue).getString("message"));val key=snapshots.keys.first();manager.close()
        val replacement=phone("replacement");val restored=RepertoireStore(replacement);val original=File(replacement.filesDir,"saved.pgn").apply { writeText("1. e4 *") }
        val currentId=restored.saveRepertoire(JSONObject().put("name","New phone edits").put("side","white")).getString("created_id")
        val q=LinkedBlockingQueue<JSONObject>();val client=RepertoireBackups(restored,replacement.getSharedPreferences("backup",0),transport,q::offer)
        try {
            client.action("restore",key);assertTrue(await(q).getBoolean("restored"))
            assertEquals(id,restored.catalog().getJSONArray("repertoires").getJSONObject(0).getString("id"))
            assertTrue(snapshots.values.any { it.getJSONObject("edits").optJSONObject("_local_repertoires")?.has(currentId)==true })
            assertEquals("1. e4 *",original.readText());assertEquals(id,JSONObject(restored.settings()).getJSONArray("_selected").getString(0))
            restored.saveRepertoire(JSONObject().put("id",id).put("name","Keep this rename"))
            val before=restored.backupSnapshot().toString();failUpload=true
            client.action("restore",key);assertFalse(await(q).optBoolean("restored"));assertEquals(before,restored.backupSnapshot().toString())
            failUpload=false;onLoad={ restored.saveRepertoire(JSONObject().put("id",id).put("name","Changed during download")) }
            client.action("restore",key);assertFalse(await(q).optBoolean("restored"));assertEquals("Changed during download",restored.catalog().getJSONArray("repertoires").getJSONObject(0).getString("name"))
            onLoad=null;client.edited();client.action("save");val saved=await(q);assertFalse(saved.getBoolean("pending"));assertTrue(saved.getLong("saved_ms")>0)
        } finally { client.close() }
    }
    @Test fun gatewayAllowsOnlyBoundedVersionIdentifiers() {
        val base=BuildConfig.LEELA_GATEWAY_ORIGIN+"/api/mobile/v1/repertoire-backups"
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl(base))
        assertTrue(AnalysisWebPolicy.isAllowedNativeGatewayUrl(base+"?snapshot="+"a".repeat(64)))
        for(query in listOf("snapshot=../../x","snapshot="+"a".repeat(65),"snapshot="+"a".repeat(64)+"&other=x","token=secret"))assertFalse(AnalysisWebPolicy.isAllowedNativeGatewayUrl("$base?$query"))
    }
}
