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
}

/** Native library and naming form. The form never changes the analysis tree or PGNs. */
internal object RepertoireLibraryView {
    fun build(context: Context, state: RepertoireLibraryState, render: () -> Unit,
        save: (JSONObject) -> Unit, open: (String?) -> Unit): android.view.View {
        val ui=WorkspaceMenu(context)
        val dp=context.resources.displayMetrics.density
        val page=ui.column().apply { setPadding((16*dp).toInt(),0,(16*dp).toInt(),(16*dp).toInt()) }
        if(state.error.isNotBlank())page.addView(ui.text(state.error,14f))
        if(state.editing) {
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
                state.editing=true; state.id=""; state.name=""; state.side="white"; state.error=""; render()
            })
            val rows=state.catalog.optJSONArray("repertoires")
            for(local in listOf(true,false)) {
                val reps=if(rows==null)emptyList() else (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("local")==local }
                if(reps.isEmpty())continue
                page.addView(ui.section(if(local)"On this phone" else "From your PC"))
                for(rep in reps) {
                    page.addView(ui.row("\uf02d",rep.getString("name"),rep.getString("side").replaceFirstChar(Char::uppercase)+" · Open on the analysis board") { open(rep.getString("id")) })
                    if(local)page.addView(ui.row("\uf040","Rename ${rep.getString("name")}") {
                        state.editing=true; state.id=rep.getString("id"); state.name=rep.getString("name"); state.side=rep.getString("side"); render()
                    })
                }
            }
            page.addView(ui.section("Explore"))
            page.addView(ui.row("\uf279","Repertoire board","Compare your selected repertoires") { open(null) })
            page.addView(ui.text("PC copies can be updated from repertoire settings on the board. Phone-created repertoires and your edits stay here.",13f,true))
        }
        return ui.scroll(page)
    }
}
