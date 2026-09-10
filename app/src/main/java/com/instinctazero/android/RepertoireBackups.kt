package com.instinctazero.android

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A separate, serial network worker. Local edits never wait for the PC. Each immutable
 * snapshot is content-addressed by the PC, so an uncertain upload can safely be retried. */
internal class RepertoireBackups(
    private val store: RepertoireStore,
    private val prefs: SharedPreferences,
    private val transport: (String,JSONObject?) -> JSONObject,
    private val changed: (JSONObject) -> Unit,
) {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    private var scheduled: ScheduledFuture<*>?=null
    @Volatile private var closed=false
    @Volatile private var generation=0L
    @Volatile private var message=""
    @Volatile private var busy=false
    fun status()=JSONObject().put("pending",prefs.getBoolean("pending",false)).put("saved_ms",prefs.getLong("saved_ms",0))
        .put("message",message).put("busy",busy)
    @Synchronized fun edited() {
        if(closed)return
        generation++;prefs.edit().putBoolean("pending",true).apply()
        scheduled?.cancel(false);scheduled=worker.schedule({ run("save",automatic=true) },2,TimeUnit.SECONDS)
    }
    fun resume() {
        if(closed)return
        worker.execute {
            // Do not make an empty replacement phone the newest backup merely by opening it.
            val edits=store.backupSnapshot().getJSONObject("edits")
            if(prefs.getBoolean("pending",false) || edits.keys().asSequence().any { !it.startsWith("_") || it=="_local_repertoires" }) run("save",automatic=true)
        }
    }
    fun action(action: String,version: String="") {
        if(!closed)worker.execute { run(action,version) }
    }
    private fun upload(force: Boolean) {
        val start=generation
        val snapshot=store.backupSnapshot()
        val hash=RepertoireStore.hash(snapshot.toString().toByteArray())
        if(hash!=prefs.getString("uploaded_hash",null))prefs.edit().putBoolean("pending",true).apply()
        if(force || hash!=prefs.getString("uploaded_hash",null)) {
            val response=transport("",snapshot)
            check(response.optBoolean("saved")) { "PC backup was not confirmed." }
            prefs.edit().putString("uploaded_hash",hash).putLong("saved_ms",System.currentTimeMillis()).apply()
        }
        synchronized(this) { if(generation==start)prefs.edit().putBoolean("pending",false).apply() }
    }
    private fun run(action: String,version: String="",automatic: Boolean=false) {
        if(closed)return
        busy=true;message="";changed(status())
        var result=JSONObject()
        try {
            when(action) {
                "save" -> upload(!automatic)
                "list" -> Unit
                "restore" -> {
                    require(version.matches(Regex("[a-f0-9]{64}")))
                    val before=store.backupSnapshot().toString()
                    // A restore is not allowed to overwrite edits that have no recoverable PC copy.
                    val snapshot=transport(version,null).getJSONObject("snapshot")
                    // Fetch first so retention cannot prune the selected oldest version while
                    // saving the current one. No local state changes before that save succeeds.
                    upload(true)
                    // UI disables edits while restoring; this also protects programmatic races.
                    synchronized(this) {
                        check(!closed)
                        synchronized(store) {
                            check(store.backupSnapshot().toString()==before) { "Local edits changed during restore." }
                            store.restoreBackup(snapshot)
                        }
                        generation++;prefs.edit().putBoolean("pending",true).apply()
                    }
                    result.put("restored",true).put("catalog",store.catalog())
                    edited() // Publish the restored state now; its upload must not delay UI invalidation.
                }
                else -> throw IllegalArgumentException("Unknown backup action")
            }
            if(!automatic && action!="restore")result.put("versions",transport("",null).getJSONArray("versions"))
        } catch(error: Exception) {
            // No account-bearing server errors or URLs reach the phone. A successful restore
            // remains reported even when its following upload/list encounters a disconnect.
            message=if(action=="restore" && !result.optBoolean("restored"))"Restore unavailable. Your phone copy is unchanged. Connect your PC and try again."
                else "PC backup unavailable. Your phone copy is safe; retry when connected."
        } finally {
            busy=false
            if(!closed)changed(status().also { state -> result.keys().forEach { state.put(it,result.get(it)) } })
        }
    }
    @Synchronized fun close() { closed=true;scheduled?.cancel(false);worker.shutdownNow() }
}
