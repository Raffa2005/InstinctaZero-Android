package com.instinctazero.android

import android.app.Application
import com.instinctazero.android.data.InstinctaRepository

class InstinctaZeroApplication : Application() {
    lateinit var repository: InstinctaRepository
        private set
    lateinit var preferences: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        repository = InstinctaRepository.create(this)
        preferences = AppPreferences(this)
    }
}
