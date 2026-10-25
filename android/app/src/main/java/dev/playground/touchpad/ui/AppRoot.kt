package dev.playground.touchpad.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import dev.playground.touchpad.net.TouchpadClient
import dev.playground.touchpad.net.TouchpadClient.State

/** Picks the screen for the connection state; once paired, landscape is the keyboard. */
@Composable
fun AppRoot(state: State, client: TouchpadClient) {
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
                KeyboardScreen(state = state, onKey = client::key)
            } else {
                TouchpadScreen(
                    state = state,
                    onMove = client::move,
                    onButton = client::setButton,
                    onUnpair = client::unpair,
                )
            }
    }
}
