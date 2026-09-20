package com.instinctazero.android

import android.content.SharedPreferences
import org.json.JSONObject

/** Small board-editor draft only. StudyDatabase owns the saved analysis trees. */
internal class StudyWorkspaceStore(private val prefs: SharedPreferences) {
    fun draft(): String = prefs.getString("position_draft_v1","{}") ?: "{}"
    fun saveDraft(raw: String?): Boolean = runCatching {
        require(raw!=null && raw.length<=1024)
        val value=JSONObject(raw)
        val result=JSONObject().put("fen",PositionFen.checked(value.getString("fen"))).put("black",value.optBoolean("black",false))
        prefs.edit().putString("position_draft_v1",result.toString()).commit()
    }.getOrDefault(false)
}

/** A narrow transport check; chess legality is checked in the editor and again on the PC. */
internal object PositionFen {
    fun checked(raw: String): String {
        require(raw.length<=200) { "Position FEN is too long." }
        val fields=raw.trim().split(Regex("\\s+"))
        require(fields.size==6) { "Position FEN needs six fields." }
        val ranks=fields[0].split('/')
        require(ranks.size==8 && ranks.all { rank ->
            rank.isNotEmpty() && !Regex("[1-8]{2}").containsMatchIn(rank) && rank.all { it in "12345678kqrbnpKQRBNP" } &&
                rank.sumOf { if(it in '1'..'8')it-'0' else 1 }==8
        }) { "Invalid position board." }
        require(fields[1] in listOf("w","b") && fields[2].matches(Regex("-|K?Q?k?q?")) && fields[2].isNotEmpty()) { "Invalid turn or castling rights." }
        require(fields[3].matches(Regex("-|[a-h][36]"))) { "Invalid en-passant square." }
        require(fields[4].matches(Regex("[0-9]{1,4}")) && fields[5].matches(Regex("[0-9]{1,4}")) && fields[5].toInt()>0) { "Invalid position counters." }
        return fields.joinToString(" ")
    }
}
