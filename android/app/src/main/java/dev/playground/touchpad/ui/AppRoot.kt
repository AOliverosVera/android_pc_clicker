package dev.playground.touchpad.ui

import androidx.compose.runtime.Composable
import dev.playground.touchpad.net.TouchpadClient
import dev.playground.touchpad.net.TouchpadClient.State

/** Picks the screen for the connection state. */
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
        is State.Searching, is State.Connected -> TouchpadScreen(
            state = state,
            onMove = client::move,
            onButton = client::setButton,
            onUnpair = client::unpair,
        )
    }
}
