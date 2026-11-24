package com.instinctazero.android

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LichessPhoneTest {
    private val prefs get()=RuntimeEnvironment.getApplication().getSharedPreferences("test_phone_reads",0)
    private val row="""{"id":"abcD1234","rated":true,"variant":"standard","status":"resign","speed":"blitz","createdAt":1234,"lastMoveAt":2000,
        "players":{"white":{"user":{"id":"away","name":"Away"}},"black":{"user":{"id":"owner","name":"Owner"}}},"winner":"black","moves":"e4 c5","lastFen":"preview"}""".replace("\n","")
    @Test fun pagesAreLightweightAndDetailIsOwnerCheckedAndFetchedOnlyOnTap(){
        val phone=LichessPhone(prefs)
        val p=phone.page("owner",null,1){ req->
            assertEquals("false",req.url.queryParameter("moves"));assertEquals("true",req.url.queryParameter("lastFen"))
            assertNull(req.header("Authorization"));row+"\n"+row.replace("1234,","1200,")
        }
        assertEquals("lc_1200",p.getString("next_cursor"))
        val summary=p.getJSONArray("games").getJSONObject(0)
        assertFalse(summary.has("moves"));assertEquals("0-1",summary.getString("result"))
        val detail=phone.detail("owner","abcD1234"){req->assertEquals("true",req.url.queryParameter("moves"));row}
        assertEquals("c5",detail.getJSONObject("game").getJSONArray("moves").getJSONObject(1).getString("san"))
        assertTrue(runCatching{phone.detail("unrelated","abcD1234"){row}}.isFailure)
        assertTrue(runCatching{phone.detail("owner","abcD1234"){row.replace("resign","started")}}.isFailure)
    }
    @Test fun privatePkceCredentialIsBoundToOwnerAndOngoingGatePrecedesExplorer(){
        val phone=LichessPhone(prefs);phone.forget()
        val auth=phone.authorize("owner");val state=auth.getQueryParameter("state")!!
        assertEquals("S256",auth.getQueryParameter("code_challenge_method"));assertNull(auth.getQueryParameter("code_verifier"))
        fun redirect(s:String)=Uri.parse(LichessPhone.REDIRECT+"?code=test&state="+s)
        assertTrue(runCatching{phone.finish(redirect("invalid"),"owner"){ error("No request allowed") }}.isFailure)
        phone.finish(redirect(state),"owner"){r->if(r.url.encodedPath=="/api/token") """{"access_token":"dummy"}""" else """{"id":"owner"}"""}
        assertTrue(phone.connected("owner"));assertFalse(phone.connected("away"))
        val q=JSONObject().put("source","masters").put("fen","test")
        val raw="""{"white":4,"draws":2,"black":1,"moves":[{"uci":"e2e4","san":"e4","white":2,"draws":1,"black":1}]}"""
        assertTrue(runCatching{phone.explorer(q,"owner"){request->assertEquals("lichess.org",request.url.host);row}}.isFailure)
        val result=phone.explorer(q,"owner"){r->
            assertEquals("Bearer dummy",r.header("Authorization"))
            if(r.url.host=="lichess.org") "" else {assertEquals("explorer.lichess.org",r.url.host);raw}
        }
        assertEquals(7,result.getJSONObject("totals").getInt("games"))
        assertEquals(4,result.getJSONArray("moves").getJSONObject(0).getInt("games"))
        phone.forget();assertFalse(phone.connected("owner"))
    }
}
