package com.instinctazero.android

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ManeuverSettingsTest {
    @Test fun defaultsUpgradePersistenceAndValidationPreserveExistingSettings() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("study_ui_settings",Context.MODE_PRIVATE)
        prefs.edit().putBoolean("arrowsEnabled",false).putInt("arrowCount",5).putInt("nodes",4000).putString("engineBackend","sycl").commit()
        // Construct only the bridge: no Activity onCreate, authentication, network or phone.
        val bridge=NativeAnalysisBridge(activity)
        try {
            val old=JSONObject(bridge.getUiSettings());assertEquals("best",old.getString("arrowMode"));assertFalse(old.getBoolean("arrowsEnabled"))
            val updated=JSONObject(bridge.saveUiSettings("""{"arrowMode":"maneuver"}"""))
            assertEquals("maneuver",updated.getString("arrowMode"));assertEquals(5,updated.getInt("arrowCount"));assertEquals(4000,updated.getInt("nodes"));assertEquals("sycl",updated.getString("engineBackend"));assertFalse(updated.getBoolean("arrowsEnabled"))
            val reopened=NativeAnalysisBridge(Robolectric.buildActivity(MainActivity::class.java).get())
            try { assertEquals(updated.toString(),reopened.getUiSettings()) } finally { reopened.close() }
            assertEquals(updated.toString(),bridge.saveUiSettings("""{"arrowMode":"unexpected","nodes":100}"""))
            val legacy=JSONObject(bridge.saveUiSettings("""{"arrowsEnabled":true}"""));assertEquals("maneuver",legacy.getString("arrowMode"))
            prefs.edit().putString("arrowMode","invalid stored value").commit();assertEquals("best",JSONObject(bridge.getUiSettings()).getString("arrowMode"))
        } finally { bridge.close() }
    }
}
