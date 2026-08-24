package com.instinctazero.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    @Test
    fun launchStartsAtHomeAndBackUnwindsNativeSurfacesBeforeExit() {
        val navigation = ShellNavigation()
        assertEquals(ShellScreen.HOME, navigation.screen)
        assertEquals(ShellBackAction.EXIT, navigation.onBack())

        navigation.showProfile()
        navigation.openKeypad()
        assertEquals(ShellBackAction.RENDER_NATIVE, navigation.onBack())
        assertEquals(ShellScreen.PROFILE, navigation.screen)
        assertFalse(navigation.keypadOpen)
        assertEquals(ShellBackAction.RENDER_NATIVE, navigation.onBack())
        assertEquals(ShellScreen.HOME, navigation.screen)

        navigation.openDrawer()
        assertEquals(ShellBackAction.RENDER_NATIVE, navigation.onBack())
        assertFalse(navigation.drawerOpen)
    }

    @Test
    fun analysisDelegatesItsFirstBackPressToTheBundledPanelThenReturnsHome() {
        val navigation = ShellNavigation()
        navigation.showAnalysis()
        assertEquals(ShellBackAction.REQUEST_ANALYSIS_BACK, navigation.onBack())
        assertEquals(ShellScreen.ANALYSIS, navigation.screen)
        navigation.showHome()
        assertEquals(ShellScreen.HOME, navigation.screen)
    }

    @Test
    fun pairingCodeAcceptsOnlyTheEightTouchKeypadCharacters() {
        val code = PairingCodeBuffer()
        "abcd2345".forEach(code::append)
        assertEquals("ABCD2345", code.value)
        assertTrue(code.complete)
        assertFalse(code.append('Z'))
        assertTrue(code.erase())
        assertFalse(code.complete)
        assertFalse(code.append('0'))
        assertTrue(code.append('9'))
        code.clear()
        assertEquals("", code.value)
    }

    @Test
    fun sixColumnPairingKeypadFitsCanonical341By543PhoneWithoutScrolling() {
        assertEquals(6, ShellLayoutMetrics.KEYPAD_COLUMNS)
        assertEquals(6, ShellLayoutMetrics.keypadRows)
        assertTrue(ShellLayoutMetrics.minimumKeyWidthDp >= 48)
        assertTrue(ShellLayoutMetrics.profileRequiredHeightDp <= ShellLayoutMetrics.CANONICAL_HEIGHT_DP)
    }

    @Test
    fun pairingBusyStateRejectsDuplicatePairAndAllCodeEditingUntilFinished() {
        val code = PairingCodeBuffer()
        "ABCD2345".forEach(code::append)
        assertTrue(code.beginPair())
        assertTrue(code.busy)
        assertFalse(code.beginPair())
        assertFalse(code.append('Z'))
        assertFalse(code.erase())
        assertFalse(code.clear())
        assertEquals("ABCD2345", code.value)
        code.finishPair()
        assertTrue(code.erase())
    }
}
