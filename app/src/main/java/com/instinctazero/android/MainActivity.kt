package com.instinctazero.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instinctazero.android.ui.AppAction
import com.instinctazero.android.ui.AppScreen
import com.instinctazero.android.ui.InstinctaZeroApp

class MainActivity : ComponentActivity() {
    private val viewModel: InstinctaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value.screen in setOf(AppScreen.PAIRING, AppScreen.GAMES)) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        viewModel.onAction(AppAction.Back)
                    }
                }
            },
        )
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            InstinctaZeroApp(state = state, onAction = viewModel::onAction)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }
}
