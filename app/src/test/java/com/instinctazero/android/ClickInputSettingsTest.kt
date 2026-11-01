package com.instinctazero.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ClickInputSettingsTest {
    @Test fun destinationInputDefaultsOnAndPersistsWithoutChangingEngineSettings() {
        val bridge=NativeAnalysisBridge(Robolectric.buildActivity(MainActivity::class.java).get())
        try {
            assertTrue(JSONObject(bridge.getUiSettings()).getBoolean("smartInput"))
            val saved=JSONObject(bridge.saveUiSettings("""{"smartInput":false,"nodes":4000}"""))
            assertFalse(saved.getBoolean("smartInput"));assertEquals(4000,saved.getInt("nodes"))
            val reopened=NativeAnalysisBridge(Robolectric.buildActivity(MainActivity::class.java).get())
            try {
                assertFalse(JSONObject(reopened.getUiSettings()).getBoolean("smartInput"))
                val partial=JSONObject(reopened.saveUiSettings("""{"arrowsEnabled":false}"""))
                assertFalse(partial.getBoolean("smartInput"));assertEquals(4000,partial.getInt("nodes"))
            } finally { reopened.close() }
        } finally { bridge.close() }
    }
}
