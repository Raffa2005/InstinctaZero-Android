package com.instinctazero.android

import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public game reads and an independent, scope-free PKCE token. Never accepts gateway credentials. */
internal enum class BookLogin(val label:String) {
    READY("Direct opening book connected."), CANCELLED("Book connection cancelled."),
    EXPIRED("Book sign-in expired. Start again from Account / PC."),
    ACCOUNT("Book sign-in needs the same account selected on the PC."),
    NETWORK("Lichess could not finish book sign-in. Try again; the PC book is still available.")
}
internal class BookLoginFailure(val reason:BookLogin): Exception()
internal class LichessUnauthorized: IOException("Reconnect the direct opening book in Account / PC.")
internal class LichessPhone(private val prefs: SharedPreferences) {
    companion object {
        const val REDIRECT = "com.instinctazero.android://oauth/lichess"
        private const val CLIENT = "instinctazero-android"
        private val terminal = setOf("mate","resign","stalemate","timeout","draw","outoftime","cheat","variantend","insufficientmaterialclaim","unknownfinish","aborted","nostart")
        fun book(raw: JSONObject): JSONObject {
            fun counts(o: JSONObject): JSONObject = o.apply { put("games",optLong("white")+optLong("draws")+optLong("black")) }
            val moves=raw.getJSONArray("moves")
            for(i in 0 until moves.length()) counts(moves.getJSONObject(i))
            return JSONObject().put("totals",counts(JSONObject().apply {
                for(k in listOf("white","draws","black")) put(k,raw.getLong(k))
            })).put("moves",moves)
        }
        fun game(raw: JSONObject, owner: String, detail: Boolean): JSONObject {
            val players=raw.getJSONObject("players")
            val ids=mutableListOf<String>()
            val status=raw.getString("status").lowercase()
            require(status in terminal)
            val variant=raw.getString("variant")
            val result=when(raw.optString("winner")) { "white" -> "1-0"; "black" -> "0-1"; else -> if(status in setOf("aborted","nostart")) "*" else "1/2-1/2" }
            val out=JSONObject().put("id",raw.getString("id")).put("rated",raw.getBoolean("rated"))
                .put("variant",variant).put("speed",raw.optString("speed")).put("status",status)
                .put("clock",raw.optJSONObject("clock")).put("result",result)
                .put("last_move_at_ms",raw.optLong("lastMoveAt",raw.getLong("createdAt")))
                .put("preview_fen",raw.optString("lastFen")).put("initial_fen",raw.optString("initialFen"))
                .put("analyzable",variant.lowercase() in setOf("standard","fromposition") && result!="*")
            for(side in listOf("white","black")) {
                val p=players.getJSONObject(side);val user=p.optJSONObject("user");ids+=user?.optString("id").orEmpty()
                out.put(side,JSONObject().put("name",user?.optString("name") ?: p.optString("name","Anonymous")).put("rating",p.optInt("rating")))
            }
            require(owner.isNotBlank() && ids.any { it.equals(owner,true) })
            if(detail) {
                require(out.getBoolean("analyzable"))
                out.put("moves",JSONArray().apply {
                    raw.getString("moves").split(' ').filter(String::isNotBlank).forEach { put(JSONObject().put("san",it)) }
                })
            }
            return out
        }
    }
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    private fun random()=encoder.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun connected(owner: String)=owner.isNotBlank() && prefs.contains("phone.oauth.token") && owner.equals(prefs.getString("phone.oauth.owner",""),true)
    @Synchronized fun forget() {
        prefs.edit().apply { for(k in listOf("token","owner","verifier","state","expires","expected")) remove("phone.oauth."+k) }.commit()
        gateUntil=0
    }
    @Synchronized fun forgetRejected(header:String?) {
        val token=prefs.getString("phone.oauth.token",null) ?: return
        if(header=="Bearer "+token)prefs.edit().remove("phone.oauth.token").apply()
    }
    fun authorize(owner: String): Uri {
        require(owner.isNotBlank())
        val verifier=random();val state=random()
        check(prefs.edit().putString("phone.oauth.verifier",verifier).putString("phone.oauth.state",state)
            .putString("phone.oauth.expected",owner).putLong("phone.oauth.expires",System.currentTimeMillis()+600000).commit())
        return Uri.parse("https://lichess.org/oauth").buildUpon().appendQueryParameter("client_id",CLIENT)
            .appendQueryParameter("username",owner).appendQueryParameter("response_type","code").appendQueryParameter("redirect_uri",REDIRECT)
            .appendQueryParameter("state",state).appendQueryParameter("code_challenge_method","S256")
            .appendQueryParameter("code_challenge",encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))).build()
    }
    @Synchronized fun finish(uri: Uri, owner: String, read: (Request)->String) {
        require(uri.toString().substringBefore('?')==REDIRECT && owner.isNotBlank())
        val state=prefs.getString("phone.oauth.state",null) ?: throw BookLoginFailure(BookLogin.EXPIRED)
        if(state!=uri.getQueryParameter("state") || prefs.getLong("phone.oauth.expires",0)<=System.currentTimeMillis())throw BookLoginFailure(BookLogin.EXPIRED)
        if(!owner.equals(prefs.getString("phone.oauth.expected",""),true))throw BookLoginFailure(BookLogin.ACCOUNT)
        val verifier=requireNotNull(prefs.getString("phone.oauth.verifier",null))
        check(prefs.edit().remove("phone.oauth.verifier").remove("phone.oauth.state").commit())
        if(uri.getQueryParameter("error")=="access_denied")throw BookLoginFailure(BookLogin.CANCELLED)
        val form=FormBody.Builder().add("grant_type","authorization_code").add("client_id",CLIENT)
            .add("code_verifier",verifier).add("redirect_uri",REDIRECT).add("code",requireNotNull(uri.getQueryParameter("code"))).build()
        val token=JSONObject(read(Request.Builder().url("https://lichess.org/api/token").post(GatewayHttpPolicy.nonReplayable(form)).build())).getString("access_token")
        val account=JSONObject(read(Request.Builder().url("https://lichess.org/api/account").header("Authorization","Bearer "+token).build())).getString("id")
        if(!account.equals(owner,true))throw BookLoginFailure(BookLogin.ACCOUNT)
        check(prefs.edit().putString("phone.oauth.owner",owner).putString("phone.oauth.token",token).commit())
        gateUntil=0
    }
    private fun gamesUrl(owner: String)= "https://lichess.org/api/games/user/".toHttpUrl().newBuilder().addPathSegment(owner)
    private fun request(url: HttpUrl,owner: String,ndjson:Boolean=false)=Request.Builder().url(url).apply {
        header("Accept",if(ndjson)"application/x-ndjson" else "application/json")
        if(connected(owner))header("Authorization","Bearer "+prefs.getString("phone.oauth.token",""))
    }.build()
    private val books=object:LinkedHashMap<String,JSONObject>(32,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,JSONObject>?)=size>64
    }
    private var gateUntil=0L
    private var gateOwner=""
    @Synchronized private fun checkPlayable(owner:String,read:(Request)->String) {
        if(gateOwner==owner && SystemClock.elapsedRealtime()<gateUntil)return
        val url=gamesUrl(owner).addQueryParameter("max","1").addQueryParameter("ongoing","true").addQueryParameter("finished","false").addQueryParameter("moves","false").build()
        if(read(request(url,owner,true)).isNotBlank())throw IOException("Opening book unavailable during an ongoing game.")
        gateOwner=owner;gateUntil=SystemClock.elapsedRealtime()+5000
    }
    fun explorer(query:JSONObject,owner:String,read:(Request)->String):JSONObject {
        require(connected(owner))
        checkPlayable(owner,read)
        val source=query.getString("source");require(source in setOf("lichess","masters"))
        val url=("https://explorer.lichess.org/"+source).toHttpUrl().newBuilder().addQueryParameter("fen",query.getString("fen"))
            .addQueryParameter("moves","12").addQueryParameter("topGames","0")
        if(source=="lichess") {
            url.addQueryParameter("recentGames","0")
            for(k in listOf("speeds","ratings"))query.optJSONArray(k)?.let { if(it.length()>0)url.addQueryParameter(k,(0 until it.length()).joinToString(","){i->it.get(i).toString()}) }
        }
        val address=url.build()
        val key=owner+"|"+address
        synchronized(books){books[key]?.let { return it }}
        return book(JSONObject(read(request(address,owner)))).also { synchronized(books){books[key]=it} }
    }
    fun page(owner:String,cursor:String?,size:Int,read:(Request)->String):JSONObject {
        require(owner.isNotBlank())
        // Lichess 'until' is exclusive. Include the first unshown millisecond and
        // skip only its rows already emitted, including ties spanning many pages.
        val resume=cursor?.let { Regex("lc_([0-9]{1,14})_([0-9]{1,6})").matchEntire(it) ?: error("Invalid game cursor.") }
        val until=resume?.groupValues?.get(1)?.toLong()
        val skip=resume?.groupValues?.get(2)?.toInt() ?: 0
        val url=gamesUrl(owner).addQueryParameter("max",(size+1+skip).toString()).addQueryParameter("sort","dateDesc")
            .addQueryParameter("moves","false").addQueryParameter("lastFen","true").addQueryParameter("ongoing","false")
            .addQueryParameter("finished","true").addQueryParameter("clocks","false").addQueryParameter("evals","false")
        if(until!=null)url.addQueryParameter("until",until.toString())
        val rows=read(request(url.build(),owner,true)).lineSequence().filter(String::isNotBlank).map(::JSONObject)
            .let { stream ->
                var duplicates=skip
                stream.dropWhile { duplicates>0 && it.getLong("createdAt")==until!!-1 && (--duplicates)>=0 }
            }.take(size+1).toList()
        val list=JSONArray();rows.take(size).forEach { list.put(game(it,owner,false)) }
        return JSONObject().put("games",list).put("account",owner).put("total",-1)
            .put("next_cursor",rows.getOrNull(size)?.let { val stamp=it.getLong("createdAt");val count=rows.take(size).count { row->row.getLong("createdAt")==stamp }; "lc_${stamp+1}_${count+if(until==stamp+1)skip else 0}" } ?: JSONObject.NULL)
    }
    fun detail(owner:String,id:String,read:(Request)->String):JSONObject {
        require(id.matches(Regex("[A-Za-z0-9]{8,16}")))
        val url=("https://lichess.org/game/export/"+id).toHttpUrl().newBuilder().addQueryParameter("moves","true")
            .addQueryParameter("clocks","false").addQueryParameter("evals","false").build()
        val game=game(JSONObject(read(request(url,owner))),owner,true);require(game.getString("id")==id)
        return JSONObject().put("game",game)
    }
}
