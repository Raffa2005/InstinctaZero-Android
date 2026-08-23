package com.instinctazero.android

import android.content.Context

data class AppPreferenceState(
    val autoFetch: Boolean = true,
    val autoOpenNewest: Boolean = true,
    val showArrows: Boolean = true,
    val showOpeningBook: Boolean = true,
    val accountName: String = "",
)

/** Small, non-secret UI preference store. Pairing credentials live in SecureSessionStore. */
class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): AppPreferenceState = AppPreferenceState(
        autoFetch = preferences.getBoolean(KEY_AUTO_FETCH, true),
        autoOpenNewest = preferences.getBoolean(KEY_AUTO_OPEN_NEWEST, true),
        showArrows = preferences.getBoolean(KEY_SHOW_ARROWS, true),
        showOpeningBook = preferences.getBoolean(KEY_SHOW_OPENING_BOOK, true),
        accountName = preferences.getString(KEY_ACCOUNT_NAME, "").orEmpty(),
    )

    fun save(value: AppPreferenceState) {
        preferences.edit()
            .putBoolean(KEY_AUTO_FETCH, value.autoFetch)
            .putBoolean(KEY_AUTO_OPEN_NEWEST, value.autoOpenNewest)
            .putBoolean(KEY_SHOW_ARROWS, value.showArrows)
            .putBoolean(KEY_SHOW_OPENING_BOOK, value.showOpeningBook)
            .putString(KEY_ACCOUNT_NAME, value.accountName)
            .apply()
    }

    private companion object {
        const val NAME = "app_preferences"
        const val KEY_AUTO_FETCH = "auto_fetch"
        const val KEY_AUTO_OPEN_NEWEST = "auto_open_newest"
        const val KEY_SHOW_ARROWS = "show_arrows"
        const val KEY_SHOW_OPENING_BOOK = "show_opening_book"
        const val KEY_ACCOUNT_NAME = "account_name"
    }
}
