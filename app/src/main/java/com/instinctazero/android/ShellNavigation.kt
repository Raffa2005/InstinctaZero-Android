package com.instinctazero.android

internal enum class ShellScreen {
    HOME,
    PROFILE,
    ANALYSIS,
}

internal enum class ShellBackAction {
    RENDER_NATIVE,
    REQUEST_ANALYSIS_BACK,
    EXIT,
}

/** Conservative dp budget for the smallest supported legacy-style portrait shell. */
internal object ShellLayoutMetrics {
    const val CANONICAL_HEIGHT_DP = 543
    const val CANONICAL_WIDTH_DP = 341
    const val HEADER_HEIGHT_DP = 56
    const val PROFILE_VERTICAL_PADDING_DP = 6
    const val PROFILE_COPY_DP = 60
    const val CODE_SLOTS_DP = 52
    const val KEYPAD_COLUMNS = 6
    const val PROFILE_HORIZONTAL_PADDING_DP = 20
    const val KEY_HORIZONTAL_MARGIN_DP = 2
    const val KEYPAD_ROW_DP = 48
    const val PAIR_ACTIONS_DP = 54
    val keypadRows: Int get() = (PairingCodeBuffer.ALPHABET.length + KEYPAD_COLUMNS - 1) / KEYPAD_COLUMNS
    val profileRequiredHeightDp: Int get() = HEADER_HEIGHT_DP + PROFILE_VERTICAL_PADDING_DP +
        PROFILE_COPY_DP + CODE_SLOTS_DP + keypadRows * KEYPAD_ROW_DP + PAIR_ACTIONS_DP
    val minimumKeyWidthDp: Int get() =
        (CANONICAL_WIDTH_DP - PROFILE_HORIZONTAL_PADDING_DP) / KEYPAD_COLUMNS - KEY_HORIZONTAL_MARGIN_DP
}

/** Android-owned navigation state. The bundled analysis page owns only its internal panels. */
internal class ShellNavigation {
    var screen: ShellScreen = ShellScreen.HOME
        private set
    var drawerOpen: Boolean = false
        private set
    var keypadOpen: Boolean = false
        private set

    fun showHome() {
        screen = ShellScreen.HOME
        drawerOpen = false
        keypadOpen = false
    }

    fun showProfile() {
        screen = ShellScreen.PROFILE
        drawerOpen = false
        keypadOpen = false
    }

    fun showAnalysis() {
        screen = ShellScreen.ANALYSIS
        drawerOpen = false
        keypadOpen = false
    }

    fun openDrawer() {
        if (screen != ShellScreen.ANALYSIS) drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun openKeypad() {
        if (screen == ShellScreen.PROFILE) keypadOpen = true
    }

    fun closeKeypad() {
        keypadOpen = false
    }

    fun onBack(): ShellBackAction = when {
        keypadOpen -> {
            keypadOpen = false
            ShellBackAction.RENDER_NATIVE
        }
        drawerOpen -> {
            drawerOpen = false
            ShellBackAction.RENDER_NATIVE
        }
        screen == ShellScreen.PROFILE -> {
            showHome()
            ShellBackAction.RENDER_NATIVE
        }
        screen == ShellScreen.ANALYSIS -> ShellBackAction.REQUEST_ANALYSIS_BACK
        else -> ShellBackAction.EXIT
    }
}

/** Eight unambiguous characters entered exclusively through the native touch keypad. */
internal class PairingCodeBuffer {
    companion object {
        const val REQUIRED_LENGTH = 8
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    private val characters = StringBuilder(REQUIRED_LENGTH)

    val value: String get() = characters.toString()
    val complete: Boolean get() = characters.length == REQUIRED_LENGTH
    var busy: Boolean = false
        private set

    fun append(character: Char): Boolean {
        if (busy) return false
        val normalized = character.uppercaseChar()
        if (normalized !in ALPHABET || characters.length >= REQUIRED_LENGTH) return false
        characters.append(normalized)
        return true
    }

    fun erase(): Boolean {
        if (busy) return false
        if (characters.isEmpty()) return false
        characters.deleteCharAt(characters.lastIndex)
        return true
    }

    fun clear(): Boolean {
        if (busy) return false
        characters.setLength(0)
        return true
    }

    fun beginPair(): Boolean {
        if (!complete || busy) return false
        busy = true
        return true
    }

    fun finishPair() {
        busy = false
    }
}
