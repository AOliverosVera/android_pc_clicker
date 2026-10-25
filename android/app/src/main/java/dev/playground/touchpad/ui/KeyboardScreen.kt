package dev.playground.touchpad.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.touchpad.input.KeyDef
import dev.playground.touchpad.input.Mod
import dev.playground.touchpad.input.SpanishKeyboard
import dev.playground.touchpad.input.SpanishLayout
import dev.playground.touchpad.net.Protocol
import dev.playground.touchpad.net.TouchpadClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shown in landscape: a Spanish keyboard whose keys are typed on the PC.
 * [keyboard] is only passed in by the previews below, to show a layer.
 */
@Composable
fun KeyboardScreen(
    state: TouchpadClient.State,
    onKey: (Protocol.KeyStroke) -> Unit,
    keyboard: SpanishKeyboard = remember { SpanishKeyboard() },
) {
    // The keyboard's state isn't observable; bumping this redraws the labels.
    var version by remember { mutableIntStateOf(0) }
    val view = LocalView.current

    fun press(key: KeyDef) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        keyboard.press(key).forEach(onKey)
        version++
    }

    fun release(key: KeyDef) {
        keyboard.release(key)
        version++
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        StatusLine(state, onUnpair = null)
        Spacer(Modifier.height(2.dp))
        val looks = remember(version) { ROWS.map { row -> row.map { lookOf(it, keyboard) } } }
        ROWS.forEachIndexed { index, row ->
            val isFunctionRow = index == 0
            Row(Modifier.fillMaxWidth().weight(if (isFunctionRow) 0.6f else 1f)) {
                row.forEachIndexed { i, key ->
                    KeyCap(
                        key = key,
                        look = looks[index][i],
                        small = isFunctionRow,
                        onPress = ::press,
                        onRelease = ::release,
                        modifier = Modifier.weight(key.width).fillMaxHeight().padding(2.dp),
                    )
                }
            }
        }
    }
}

private val ROWS = listOf(SpanishLayout.functionRow) + SpanishLayout.rows

/**
 * What a key shows right now: its [label], the AltGr symbol in the [corner]
 * (as printed on a real keyboard), and whether it is [lit] (a modifier that
 * is on, or the accent waiting for its letter).
 */
private data class KeyLook(val label: String, val corner: String?, val lit: Boolean)

private fun lookOf(key: KeyDef, keyboard: SpanishKeyboard): KeyLook = when (key) {
    is KeyDef.Printable -> {
        val output = keyboard.output(key)
        KeyLook(
            label = output.toString(),
            corner = key.altGr?.takeUnless { keyboard.isOn(Mod.ALT_GR) }?.toString(),
            lit = output == keyboard.pendingAccent,
        )
    }
    is KeyDef.Action -> KeyLook(key.label, null, false)
    is KeyDef.Modifier -> KeyLook(key.label, null, keyboard.isOn(key.modifier))
}

/**
 * One key. Each key tracks its own finger, so a modifier can be held with one
 * finger while typing with another. Held keys repeat, like on a PC.
 */
@Composable
private fun KeyCap(
    key: KeyDef,
    look: KeyLook,
    small: Boolean,
    onPress: (KeyDef) -> Unit,
    onRelease: (KeyDef) -> Unit,
    modifier: Modifier,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var pressed by remember { mutableStateOf(false) }
    val repeats = when (key) {
        is KeyDef.Printable -> true
        is KeyDef.Action -> key.repeats
        is KeyDef.Modifier -> false
    }
    val (label, corner, lit) = look
    val background = when {
        lit -> Palette.Accent
        pressed -> Palette.SurfacePressed
        else -> Palette.Surface
    }
    val textColor: Color = when {
        lit -> Palette.Background
        key is KeyDef.Printable -> Palette.TextPrimary
        else -> Palette.TextMuted
    }
    val fontSize: TextUnit = when {
        small -> 11.sp
        key is KeyDef.Printable -> 18.sp
        label.length > 2 -> 12.sp
        else -> 16.sp
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .pointerInput(key) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        pressed = true
                        currentOnPress(key)
                        val repeat = if (repeats) {
                            launch {
                                delay(REPEAT_DELAY_MS)
                                while (isActive) {
                                    currentOnPress(key)
                                    delay(REPEAT_EVERY_MS)
                                }
                            }
                        } else {
                            null
                        }
                        try {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.id == down.id && it.pressed })
                        } finally {
                            // Also runs if the gesture is cancelled (e.g. rotating).
                            repeat?.cancel()
                            pressed = false
                            currentOnRelease(key)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = fontSize,
            fontWeight = if (key is KeyDef.Printable) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
        )
        if (corner != null) {
            Text(
                corner,
                color = Palette.TextMuted,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 4.dp),
            )
        }
    }
}

private const val REPEAT_DELAY_MS = 450L
private const val REPEAT_EVERY_MS = 50L

// ---------------------------------------------------------------- previews
// Open this file in Android Studio and choose Split or Design (top right) to
// see these. "Interactive mode" on a preview lets you tap the keys.

private const val LANDSCAPE_PHONE = "spec:width=411dp,height=891dp,dpi=420,orientation=landscape"
private val PREVIEW_STATE = TouchpadClient.State.Connected(pcName = "MI-PC", address = "192.168.1.20", pingMs = 4)

private fun keyboardWith(vararg on: Mod) = SpanishKeyboard().apply {
    for (mod in on) press(KeyDef.Modifier(mod, ""))
}

@Preview(name = "Keyboard", device = LANDSCAPE_PHONE)
@Composable
private fun KeyboardPreview() {
    KeyboardScreen(PREVIEW_STATE, onKey = {})
}

@Preview(name = "Keyboard, Shift on", device = LANDSCAPE_PHONE)
@Composable
private fun KeyboardShiftPreview() {
    KeyboardScreen(PREVIEW_STATE, onKey = {}, keyboard = remember { keyboardWith(Mod.SHIFT) })
}

@Preview(name = "Keyboard, AltGr on", device = LANDSCAPE_PHONE)
@Composable
private fun KeyboardAltGrPreview() {
    KeyboardScreen(PREVIEW_STATE, onKey = {}, keyboard = remember { keyboardWith(Mod.ALT_GR) })
}
