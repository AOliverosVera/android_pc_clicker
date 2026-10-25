package dev.playground.touchpad.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.playground.touchpad.net.TouchpadClient
import dev.playground.touchpad.net.TouchpadClient.State
import dev.playground.touchpad.settings.Settings

/** Picks the screen for the connection state; once paired, landscape is the keyboard. */
@Composable
fun AppRoot(state: State, client: TouchpadClient, settings: Settings, onSettingsChange: (Settings) -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsDialog(settings, onChange = onSettingsChange, onDismiss = { showSettings = false })
    }
    when (state) {
        is State.NotPaired -> PairScreen(state.pcs, onPair = client::pair)
        is State.Pairing -> PairingScreen(
            pcName = state.pcName,
            step = state.step,
            onConfirm = client::confirmCode,
            onCancel = client::cancelPairing,
        )
        is State.Searching, is State.Connected ->
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                KeyboardScreen(state = state, onKey = client::key, onSettings = { showSettings = true })
            } else {
                TouchpadScreen(
                    state = state,
                    settings = settings,
                    onMove = client::move,
                    onScroll = client::scroll,
                    onButton = client::setButton,
                    onClick = client::click,
                    onSettings = { showSettings = true },
                    onUnpair = client::unpair,
                )
            }
    }
}
