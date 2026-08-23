package com.instinctazero.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AppScreen { PAIRING, GAMES, ANALYSIS, SETTINGS }

@Immutable
data class GameSummaryUi(
    val id: String,
    val opponent: String,
    val result: String,
    val color: String,
    val speed: String,
    val playedAt: String,
    val opening: String = "",
)

@Immutable
data class AnalysisProfileUi(
    val id: String,
    val label: String,
    val detail: String,
    val experimental: Boolean = false,
)

@Immutable
data class AppUiState(
    val screen: AppScreen = AppScreen.GAMES,
    val accountName: String = "",
    val pcName: String = "InstinctaZero PC",
    val pcConnected: Boolean = false,
    val hasPairing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val isRefreshingGames: Boolean = false,
    val games: List<GameSummaryUi> = emptyList(),
    val analysis: AnalysisUiState = AnalysisUiState(),
    val autoFetch: Boolean = true,
    val autoOpenNewest: Boolean = true,
    val showArrows: Boolean = true,
    val showOpeningBook: Boolean = true,
    val selectedProfileId: String = "exact-sycl",
    val availableProfiles: List<AnalysisProfileUi> = listOf(
        AnalysisProfileUi("exact-sycl", "SYCL · exact", "Default"),
        AnalysisProfileUi("experimental-hetero-int8", "CPU + GPU INT8", "Experimental", experimental = true),
    ),
    val targetNodes: Int = 1_000,
    val multiPv: Int = 3,
)

sealed interface AppAction {
    data object Back : AppAction
    data object OpenDemo : AppAction
    data class ClaimPairing(val url: String, val code: String, val deviceName: String) : AppAction
    data object RefreshGames : AppAction
    data object OpenSettings : AppAction
    data object OpenGames : AppAction
    data object DisconnectPc : AppAction
    data class OpenGame(val id: String) : AppAction
    data class SetAutoFetch(val enabled: Boolean) : AppAction
    data class SetAutoOpenNewest(val enabled: Boolean) : AppAction
    data class SetShowArrows(val enabled: Boolean) : AppAction
    data class SetShowOpeningBook(val enabled: Boolean) : AppAction
    data class SetAnalysisProfile(val id: String) : AppAction
    data class SetTargetNodes(val nodes: Int) : AppAction
    data class SetMultiPv(val lines: Int) : AppAction
    data class Analysis(val action: AnalysisAction) : AppAction
}

/**
 * Single native UI entry point. The host owns immutable state and performs all networking/storage
 * in response to [AppAction]; this UI package never talks to Lichess or the engine directly.
 */
@Composable
fun InstinctaZeroApp(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
) {
    InstinctaZeroTheme {
        when (state.screen) {
            AppScreen.PAIRING -> PairingScreen(state, onAction)
            AppScreen.GAMES -> GamesScreen(state, onAction)
            AppScreen.ANALYSIS -> AnalysisScreen(
                state = state.analysis,
                onAction = { action ->
                    when (action) {
                        AnalysisAction.OpenGames, AnalysisAction.NavigateBack -> onAction(AppAction.OpenGames)
                        AnalysisAction.OpenSettings -> onAction(AppAction.OpenSettings)
                        else -> onAction(AppAction.Analysis(action))
                    }
                },
            )
            AppScreen.SETTINGS -> SettingsScreen(state, onAction)
        }
    }
}

@Composable
private fun PairingScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("Android phone") }
    Column(
        Modifier
            .fillMaxSize()
            .background(LegacyColors.Background)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SimpleHeader("InstinctaZero", null)
        Spacer(Modifier.height(44.dp))
        Icon(Icons.Default.Computer, null, tint = LegacyColors.AccentStrong, modifier = Modifier.size(58.dp))
        Spacer(Modifier.height(16.dp))
        Text("Connect your analysis PC", color = LegacyColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            "Enter the address and one-time code shown by InstinctaZero on your PC.",
            color = LegacyColors.Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 42.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("HTTPS server address") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("One-time code") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device name") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        )
        state.error?.let {
            Text(it, color = Color(0xFFE07A6A), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 5.dp))
        }
        Button(
            onClick = { onAction(AppAction.ClaimPairing(serverUrl.trim(), pairingCode.trim(), deviceName.trim())) },
            enabled = !state.busy && serverUrl.startsWith("https://") && pairingCode.isNotBlank() && deviceName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = LegacyColors.Accent),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(if (state.busy) "Pairing…" else "Pair phone")
        }
        TextButton(onClick = { onAction(AppAction.OpenDemo) }, enabled = !state.busy) {
            Text("Preview demo · offline", color = LegacyColors.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(44.dp))
    }
}

@Composable
private fun GamesScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(Modifier.fillMaxSize().background(LegacyColors.Background)) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).background(LegacyColors.Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("InstinctaZero", color = LegacyColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (state.accountName.isEmpty()) "Completed games" else state.accountName,
                    color = LegacyColors.Muted,
                    fontSize = 10.sp,
                )
            }
            IconButton(onClick = { onAction(AppAction.RefreshGames) }) {
                Icon(Icons.Default.Refresh, "Fetch completed games", tint = LegacyColors.Muted)
            }
            IconButton(onClick = { onAction(AppAction.OpenSettings) }) {
                Icon(Icons.Default.Settings, "Settings", tint = LegacyColors.Muted)
            }
        }
        ConnectionStrip(state)
        if (state.games.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.isRefreshingGames) "Fetching completed games…" else "No completed games yet",
                        color = LegacyColors.Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.games, key = { it.id }) { game ->
                    GameRow(game) { onAction(AppAction.OpenGame(game.id)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStrip(state: AppUiState) {
    Row(
        Modifier.fillMaxWidth().height(27.dp).background(LegacyColors.SurfaceRaised).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (state.pcConnected) Icons.Default.CheckCircle else Icons.Default.CloudOff,
            null,
            tint = if (state.pcConnected) Color(0xFF7A9B4B) else LegacyColors.Muted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (state.pcConnected) "${state.pcName} connected" else "Analysis PC unavailable",
            color = LegacyColors.Muted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun GameRow(game: GameSummaryUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            game.result,
            color = when (game.result) {
                "1-0" -> if (game.color == "white") LegacyColors.Positive else LegacyColors.Muted
                "0-1" -> if (game.color == "black") LegacyColors.Positive else LegacyColors.Muted
                else -> LegacyColors.Muted
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(game.opponent, color = LegacyColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(game.speed, game.opening).filter { it.isNotEmpty() }.joinToString(" · "),
                color = LegacyColors.Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(game.playedAt, color = LegacyColors.Muted, fontSize = 10.sp)
    }
    HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
}

@Composable
private fun SettingsScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(Modifier.fillMaxSize().background(LegacyColors.Background)) {
        SimpleHeader("Settings") { onAction(AppAction.Back) }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SettingsLabel("Synchronization") }
            item { ToggleRow("Fetch completed games when app opens", state.autoFetch) { onAction(AppAction.SetAutoFetch(it)) } }
            item { ToggleRow("Open the newest fetched game", state.autoOpenNewest) { onAction(AppAction.SetAutoOpenNewest(it)) } }
            item { SettingsLabel("Analysis") }
            item { ToggleRow("Show Leela arrows", state.showArrows) { onAction(AppAction.SetShowArrows(it)) } }
            item { ToggleRow("Show opening book", state.showOpeningBook) { onAction(AppAction.SetShowOpeningBook(it)) } }
            item { SettingsLabel("Backend") }
            items(state.availableProfiles, key = { it.id }) { profile ->
                ProfileRow(profile, profile.id == state.selectedProfileId) { onAction(AppAction.SetAnalysisProfile(profile.id)) }
            }
            item {
                StepperRow(
                    label = "Nodes",
                    value = state.targetNodes.toString(),
                    canDecrease = state.targetNodes > 100,
                    canIncrease = state.targetNodes < 100_000,
                    onDecrease = { onAction(AppAction.SetTargetNodes((state.targetNodes - 500).coerceAtLeast(100))) },
                    onIncrease = { onAction(AppAction.SetTargetNodes((state.targetNodes + 500).coerceAtMost(100_000))) },
                )
            }
            item {
                StepperRow(
                    label = "Lines",
                    value = state.multiPv.toString(),
                    canDecrease = state.multiPv > 1,
                    canIncrease = state.multiPv < 8,
                    onDecrease = { onAction(AppAction.SetMultiPv((state.multiPv - 1).coerceAtLeast(1))) },
                    onIncrease = { onAction(AppAction.SetMultiPv((state.multiPv + 1).coerceAtMost(8))) },
                )
            }
            item { SettingsLabel("Connections") }
            item {
                ActionRow(
                    title = when {
                        state.hasPairing && state.pcConnected -> state.pcName
                        state.hasPairing -> "Forget / re-pair analysis PC"
                        else -> "Pair analysis PC"
                    },
                    subtitle = when {
                        state.hasPairing && state.pcConnected -> "Connected · tap to forget or re-pair"
                        state.hasPairing -> "Offline · clear credentials and pair again"
                        else -> "No phone pairing"
                    },
                    onClick = { onAction(if (state.hasPairing) AppAction.DisconnectPc else AppAction.Back) },
                )
            }
            if (state.accountName.isNotEmpty()) item { ActionRow(state.accountName, "Games are fetched by the paired PC", onClick = {}) }
        }
    }
}

@Composable
private fun SimpleHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).background(LegacyColors.Surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LegacyColors.Text) }
        } else {
            Spacer(Modifier.width(14.dp))
        }
        Text(title, color = LegacyColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        color = LegacyColors.AccentStrong,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 17.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(50.dp).clickable { onChecked(!checked) }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = LegacyColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = LegacyColors.Accent, checkedThumbColor = LegacyColors.Text),
        )
    }
    HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(title, color = LegacyColors.Text, fontSize = 13.sp)
        Text(subtitle, color = LegacyColors.Muted, fontSize = 10.sp)
    }
    HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
}

@Composable
private fun ProfileRow(profile: AnalysisProfileUi, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = LegacyColors.Accent),
        )
        Column(Modifier.weight(1f)) {
            Text(profile.label, color = LegacyColors.Text, fontSize = 13.sp)
            Text(profile.detail, color = if (profile.experimental) LegacyColors.AccentStrong else LegacyColors.Muted, fontSize = 10.sp)
        }
    }
    HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LegacyColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDecrease, enabled = canDecrease) {
            Icon(Icons.Default.Remove, "Decrease $label", tint = if (canDecrease) LegacyColors.Muted else LegacyColors.Divider)
        }
        Text(value, color = LegacyColors.Text, fontSize = 12.sp, modifier = Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onIncrease, enabled = canIncrease) {
            Icon(Icons.Default.Add, "Increase $label", tint = if (canIncrease) LegacyColors.Muted else LegacyColors.Divider)
        }
    }
    HorizontalDivider(color = LegacyColors.Divider, thickness = 1.dp)
}
