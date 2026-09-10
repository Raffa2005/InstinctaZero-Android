package com.instinctazero.android

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

internal class RepertoireLibraryState {
    var catalog = JSONObject()
    var editing = false
    var id = ""
    var name = ""
    var side = "white"
    var busy = false
    var error = ""
    var revealPrivateText = false
    var backupsOpen = false
    var backup = JSONObject()
    var restoreVersion = ""
}

/** Native library and naming form. The form never changes the analysis tree or PGNs. */
internal object RepertoireLibraryView {
    fun build(context: Context, state: RepertoireLibraryState, render: () -> Unit,
        save: (JSONObject) -> Unit, open: (String?) -> Unit, privacy: AccountPrivacy? = null,
        backupAction: (String,String) -> Unit = { _,_ -> }): android.view.View {
        val ui=WorkspaceMenu(context)
        val dp=context.resources.displayMetrics.density
        val page=ui.column().apply { setPadding((16*dp).toInt(),0,(16*dp).toInt(),(16*dp).toInt()) }
        if(state.error.isNotBlank())page.addView(ui.text(privacy?.message(state.error,"Repertoire operation failed. Your saved copy is unchanged.") ?: state.error,14f))
        if(state.backupsOpen) {
            val busy=state.backup.optBoolean("busy")
            page.addView(ui.section("Private PC backups"))
            val saved=state.backup.optLong("saved_ms")
            val date={ ms: Long -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(ms)) }
            page.addView(ui.text(when { busy -> "Connecting to your PC…";state.backup.optBoolean("pending") -> "Changes saved on this phone · PC backup pending";saved>0 -> "Last saved: ${date(saved)}";else -> "No confirmed backup from this phone yet" },15f))
            if(state.backup.optString("message").isNotBlank())page.addView(ui.text(state.backup.getString("message"),14f,true))
            page.addView(ui.text("Additions, deleted moves, recommendations, comments, phone-created repertoires, selection settings and the last Undo are saved privately on your paired PC. Up to 100 versions (64 MiB compressed). Games, pairing and original PGNs are not changed.",14f,true).apply { setPadding(0,(12*dp).toInt(),0,0) })
            if(state.restoreVersion.isNotEmpty()) {
                page.addView(ui.section("Restore this version?"))
                page.addView(ui.text("Your current repertoire edits are backed up first. This replaces only phone repertoire edits and selections. On a replacement phone, pair with the same PC and download its repertoire library, then restore here.",15f))
                page.addView(ui.row("\uf0e2","Restore repertoire edits") { if(!busy)backupAction("restore",state.restoreVersion) }.apply { isEnabled=!busy })
                page.addView(ui.row("\uf00d","Cancel") { state.restoreVersion="";render() }.apply { isEnabled=!busy })
            } else {
                page.addView(ui.row("\uf0ee","Back up now") { if(!busy)backupAction("save","") }.apply { isEnabled=!busy })
                page.addView(ui.row("\uf021","Refresh history") { if(!busy)backupAction("list","") }.apply { isEnabled=!busy })
                page.addView(ui.section("Saved versions"))
                val versions=state.backup.optJSONArray("versions")
                if(versions==null || versions.length()==0)page.addView(ui.text("Connect to your PC and refresh to see saved versions.",14f,true))
                if(versions!=null)for(i in 0 until versions.length()) {
                    val item=versions.getJSONObject(i)
                    page.addView(ui.row("\uf017",date(item.getLong("saved_ms")),if(item.optBoolean("this_device"))"From this phone" else "From another paired phone") {
                        state.restoreVersion=item.getString("id");render()
                    }.apply { isEnabled=!busy })
                }
            }
            return ui.scroll(page)
        } else if(state.editing) {
            if(privacy?.enabled==true && privacy.text(state.name)!=state.name && !state.revealPrivateText) {
                page.addView(ui.section("Private name"))
                page.addView(ui.text("Renaming displays the original name, which contains hidden identity information.",14f,true))
                page.addView(ui.row("\uf06e","Show original name (reveals identity)") { state.revealPrivateText=true;render() })
                page.addView(ui.row("\uf00d","Cancel") { state.editing=false;render() })
                return ui.scroll(page)
            }
            page.addView(ui.section(if(state.id.isEmpty()) "New repertoire" else "Repertoire name"))
            page.addView(EditText(context).apply {
                hint="Repertoire name"; contentDescription="Repertoire name"; textSize=18f
                setTextColor(0xffeeeeea.toInt()); setHintTextColor(0xffaaa9a2.toInt())
                setSingleLine(); filters=arrayOf(InputFilter.LengthFilter(80)); setText(state.name)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { state.name=s.toString() }
                    override fun afterTextChanged(s: Editable?) {}
                })
            })
            if(state.id.isEmpty()) {
                page.addView(ui.section("Play as"))
                for(side in listOf("white","black"))page.addView(ui.row(if(state.side==side)"\uf058" else "\uf10c",side.replaceFirstChar(Char::uppercase)) {
                    state.side=side; render()
                }.apply { isSelected=state.side==side })
                page.addView(ui.text("Start empty. Add moves and comments from the analysis board. Saved on this phone.",14f,true))
            }
            page.addView(ui.section(""))
            page.addView(ui.row("\uf00c",if(state.busy)"Saving…" else if(state.id.isEmpty())"Create repertoire" else "Save name") {
                if(!state.busy)save(JSONObject().put("id",state.id).put("name",state.name).put("side",state.side))
            }.apply { isEnabled=!state.busy })
            page.addView(ui.row("\uf00d","Cancel") { state.editing=false; state.error=""; render() }.apply { isEnabled=!state.busy })
        } else {
            page.addView(ui.section("Your collection"))
            page.addView(ui.row("\uf067","New repertoire","Build an opening repertoire for White or Black") {
                state.editing=true; state.id=""; state.name=""; state.side="white"; state.error=""; state.revealPrivateText=false; render()
            })
            val rows=state.catalog.optJSONArray("repertoires")
            for(local in listOf(true,false)) {
                val reps=if(rows==null)emptyList() else (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("local")==local }
                if(reps.isEmpty())continue
                page.addView(ui.section(if(local)"On this phone" else "From your PC"))
                for(rep in reps) {
                    val label=privacy?.text(rep.getString("name")) ?: rep.getString("name")
                    page.addView(ui.row("\uf02d",label,rep.getString("side").replaceFirstChar(Char::uppercase)+" · Open on the analysis board") { open(rep.getString("id")) })
                    if(local)page.addView(ui.row("\uf040","Rename $label") {
                        state.editing=true; state.id=rep.getString("id"); state.name=rep.getString("name"); state.side=rep.getString("side"); state.revealPrivateText=false;render()
                    })
                }
            }
            page.addView(ui.section("Explore"))
            page.addView(ui.row("\uf0ee","Backups and restore","Private history on your paired PC") {
                state.backupsOpen=true;state.restoreVersion="";render();backupAction("list","")
            })
            page.addView(ui.row("\uf279","Repertoire board","Compare your selected repertoires") { open(null) })
            page.addView(ui.text("PC copies can be updated from repertoire settings on the board. Local edits are backed up when your paired PC is reachable.",13f,true))
        }
        return ui.scroll(page)
    }
}
