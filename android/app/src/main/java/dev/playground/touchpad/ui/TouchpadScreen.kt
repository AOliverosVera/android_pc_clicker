package dev.playground.touchpad.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.touchpad.R
import dev.playground.touchpad.input.PointerAccelerator
import dev.playground.touchpad.net.Protocol
import dev.playground.touchpad.net.TouchpadClient

/** Shown once paired: [state] is Searching or Connected. */
@Composable
fun TouchpadScreen(
    state: TouchpadClient.State,
    onMove: (dx: Float, dy: Float) -> Unit,
    onButton: (Protocol.Button, pressed: Boolean) -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.unpair_title)) },
            text = { Text(stringResource(R.string.unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text(stringResource(R.string.unpair_confirm), color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.pairing_cancel)) }
            },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        StatusLine(state, onUnpair = { confirmUnpair = true })
        Spacer(Modifier.height(12.dp))
        Touchpad(onMove, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClickButton(Protocol.Button.LEFT, stringResource(R.string.button_left), onButton, Modifier.weight(1f))
            ClickButton(Protocol.Button.RIGHT, stringResource(R.string.button_right), onButton, Modifier.weight(1f))
        }
    }
}

/** The connection dot and text; [onUnpair] adds the Unpair button. */
@Composable
internal fun StatusLine(state: TouchpadClient.State, onUnpair: (() -> Unit)?) {
    val (color, text) = when (state) {
        is TouchpadClient.State.Connected -> Palette.Accent to buildString {
            append(stringResource(R.string.status_connected, state.pcName))
            state.pingMs?.let { append(" · ").append(it).append(" ms") }
        }
        is TouchpadClient.State.Searching -> Palette.Warning to stringResource(R.string.status_searching_named, state.pcName)
        else -> Palette.Warning to ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(
            text,
            color = Palette.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onUnpair != null) {
            TextButton(onClick = onUnpair) { Text(stringResource(R.string.unpair_button), color = Palette.TextMuted, fontSize = 13.sp) }
        }
    }
    if (state is TouchpadClient.State.Searching) {
        Text(
            stringResource(R.string.status_searching_hint),
            color = Palette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

/**
 * Follows one finger and reports its movement. If that finger lifts while
 * another is still down, tracking hands over to the other finger without a jump.
 */
@Composable
private fun Touchpad(onMove: (Float, Float) -> Unit, modifier: Modifier) {
    val currentOnMove by rememberUpdatedState(onMove)
    val accelerator = remember { PointerAccelerator() }
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    var tracked = awaitFirstDown(requireUnconsumed = false).id
                    accelerator.reset()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change: PointerInputChange = event.changes.firstOrNull { it.id == tracked && it.pressed }
                            ?: event.changes.firstOrNull { it.pressed }?.also { tracked = it.id }
                            ?: break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero && change.previousPressed) {
                            val dxDp = delta.x / density
                            val dyDp = delta.y / density
                            val dt = change.uptimeMillis - change.previousUptimeMillis
                            val gain = accelerator.gainFor(dxDp, dyDp, dt)
                            currentOnMove(dxDp * gain, dyDp * gain)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}

/**
 * Sends "down" on touch and "up" on release. The button stays held until the
 * finger lifts, even if it slides off, so it can be held for drag-and-drop
 * while another finger moves on the touchpad.
 */
@Composable
private fun ClickButton(
    button: Protocol.Button,
    label: String,
    onButton: (Protocol.Button, Boolean) -> Unit,
    modifier: Modifier,
) {
    val currentOnButton by rememberUpdatedState(onButton)
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val color by animateColorAsState(if (pressed) Palette.SurfacePressed else Palette.Surface, tween(60), label = "press")
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .pointerInput(button) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentOnButton(button, true)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.id == down.id && it.pressed })
                    } finally {
                        // Also runs if the gesture is cancelled, so a button never stays stuck.
                        pressed = false
                        currentOnButton(button, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.TextMuted, fontSize = 13.sp)
    }
}
