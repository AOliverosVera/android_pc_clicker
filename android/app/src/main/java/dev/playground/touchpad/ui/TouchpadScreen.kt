package dev.playground.touchpad.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.playground.touchpad.R
import dev.playground.touchpad.input.ButtonHolds
import dev.playground.touchpad.input.PointerAccelerator
import dev.playground.touchpad.input.TapDetector
import dev.playground.touchpad.input.TapDrag
import dev.playground.touchpad.input.TouchpadGestures
import dev.playground.touchpad.settings.Settings
import dev.playground.touchpad.net.Protocol
import dev.playground.touchpad.net.TouchpadClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shown once paired: [state] is Searching or Connected. */
@Composable
fun TouchpadScreen(
    state: TouchpadClient.State,
    settings: Settings,
    onMove: (dx: Float, dy: Float) -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onButton: (Protocol.Button, pressed: Boolean) -> Unit,
    onClick: (Protocol.Button) -> Unit,
    onSettings: () -> Unit,
    onUnpair: () -> Unit,
) {
    var confirmUnpair by remember { mutableStateOf(false) }
    // The on-screen Left button and touchpad drags can both hold the left
    // button; the PC sees one press and one release, so neither cancels the other.
    val currentOnButton by rememberUpdatedState(onButton)
    val leftHolds = remember { ButtonHolds() }
    val holdLeft = { who: String, held: Boolean ->
        if (held) {
            if (leftHolds.hold(who)) currentOnButton(Protocol.Button.LEFT, true)
        } else if (leftHolds.release(who)) {
            currentOnButton(Protocol.Button.LEFT, false)
        }
    }
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
        StatusLine(state, onSettings = onSettings, onUnpair = { confirmUnpair = true })
        Spacer(Modifier.height(12.dp))
        Touchpad(settings, onMove, onScroll, onClick, { held -> holdLeft("touchpad", held) }, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClickButton(stringResource(R.string.button_left), { held -> holdLeft("button", held) }, Modifier.weight(1f))
            ClickButton(stringResource(R.string.button_right), { held -> onButton(Protocol.Button.RIGHT, held) }, Modifier.weight(1f))
        }
    }
}

/** The connection dot and text, the settings gear, and (with [onUnpair]) the Unpair button. */
@Composable
internal fun StatusLine(state: TouchpadClient.State, onSettings: () -> Unit, onUnpair: (() -> Unit)?) {
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
        IconButton(onClick = onSettings) {
            Icon(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.settings_button),
                tint = Palette.TextMuted,
            )
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
 * The touchpad surface. One finger moves the pointer, two fingers scroll, and
 * (if enabled) a finger sliding along the right-edge strip scrolls; see
 * [TouchpadGestures]. A quick tap clicks: one finger left, two fingers right.
 * Tap-and-drag, drag lock and three-finger drag hold the left button through
 * [onLeftHold]; see [TapDrag].
 */
@Composable
private fun Touchpad(
    settings: Settings,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (Protocol.Button) -> Unit,
    onLeftHold: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val currentSettings by rememberUpdatedState(settings)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLeftHold by rememberUpdatedState(onLeftHold)
    val view = LocalView.current
    val accelerator = remember { PointerAccelerator() }
    val drag = remember { TapDrag() }
    val scope = rememberCoroutineScope()
    val timer = remember { TimerSlot() }

    fun perform(actions: List<TapDrag.Action>) {
        for (action in actions) when (action) {
            TapDrag.Action.LEFT_DOWN -> currentOnLeftHold(true)
            TapDrag.Action.LEFT_UP -> currentOnLeftHold(false)
            TapDrag.Action.LEFT_CLICK -> currentOnClick(Protocol.Button.LEFT)
            TapDrag.Action.RIGHT_CLICK -> currentOnClick(Protocol.Button.RIGHT)
        }
    }

    // Runs one TapDrag step and buzzes when a drag starts, so the user feels the item being picked up.
    fun step(step: () -> List<TapDrag.Action>) {
        val wasDragging = drag.dragging
        perform(step())
        if (!wasDragging && drag.dragging) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // Fires TapDrag.onTimeout at its deadline. Timers run on the same thread
    // as the touch handling, so the two never race.
    fun reschedule() {
        timer.job?.cancel()
        val due = drag.deadline ?: return
        timer.job = scope.launch {
            delay((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
            step { drag.onTimeout(SystemClock.uptimeMillis()) }
            reschedule()
        }
    }

    // Leaving the touchpad (rotating to the keyboard, closing the app) must
    // never leave the button held on the PC.
    DisposableEffect(Unit) {
        onDispose {
            timer.job?.cancel()
            perform(drag.cancel())
        }
    }

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .pointerInput(Unit) {
                val taps = TapDetector(slopPx = viewConfiguration.touchSlop)
                val gestures = TouchpadGestures(slopDp = viewConfiguration.touchSlop / density, edgeWidthDp = EDGE_WIDTH.value)
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    // Settings are read once per touch, so a touch never changes behaviour halfway.
                    val s = currentSettings
                    accelerator.reset()
                    accelerator.speed = s.pointerSpeed
                    accelerator.acceleration = s.acceleration
                    drag.tapToDrag = s.tapToDrag
                    drag.dragLock = s.dragLock
                    step { drag.onTouchStart(first.uptimeMillis) }
                    reschedule()
                    taps.cancel() // in case the previous touch was interrupted
                    taps.onDown(first.id.value, first.position.x, first.position.y, first.uptimeMillis)
                    gestures.start(
                        first.id.value,
                        first.position.x / density,
                        first.position.y / density,
                        padWidthDp = size.width / density,
                        edgeScroll = s.edgeScroll,
                        pointerOnly = drag.pointerOnly,
                        threeFingerDrag = s.threeFingerDrag,
                    )
                    var lastTime = first.uptimeMillis
                    var tap: TapDetector.Tap? = null
                    var movedReported = false
                    var threeFingerReported = false
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            when {
                                c.changedToDownIgnoreConsumed() ->
                                    taps.onDown(c.id.value, c.position.x, c.position.y, c.uptimeMillis)
                                c.changedToUpIgnoreConsumed() -> taps.onUp(c.id.value, c.uptimeMillis)?.let { tap = it }
                                c.pressed -> taps.onMove(c.id.value, c.position.x, c.position.y)
                            }
                        }
                        if (!movedReported && taps.moved) {
                            movedReported = true
                            step { drag.onMoved() }
                            reschedule()
                        }
                        val motion = gestures.update(
                            event.changes.map {
                                TouchpadGestures.Finger(it.id.value, it.position.x / density, it.position.y / density, it.pressed)
                            },
                        )
                        if (!threeFingerReported && gestures.mode == TouchpadGestures.Mode.THREE_FINGER_DRAG) {
                            threeFingerReported = true
                            step { drag.onThreeFingerDrag() }
                        }
                        val time = event.changes.maxOf { it.uptimeMillis }
                        if (motion.moveX != 0f || motion.moveY != 0f) {
                            val gain = accelerator.gainFor(motion.moveX, motion.moveY, time - lastTime)
                            currentOnMove(motion.moveX * gain, motion.moveY * gain)
                        }
                        if (motion.scrollX != 0f || motion.scrollY != 0f) {
                            val edge = gestures.mode == TouchpadGestures.Mode.EDGE_SCROLL
                            val downScrollsUp = if (edge) s.edgeDownScrollsUp else s.twoFingerDownScrollsUp
                            // The wheel turns up for positive values. "Down scrolls up"
                            // means the page follows the fingers, sideways too.
                            val sign = if (downScrollsUp) 1f else -1f
                            val units = SCROLL_UNITS_PER_DP * s.scrollSpeed
                            currentOnScroll(-sign * motion.scrollX * units, sign * motion.scrollY * units)
                        }
                        lastTime = time
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (tap != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    step { drag.onTouchEnd(tap, lastTime) }
                    reschedule()
                }
            },
    ) {
        if (settings.edgeScroll) {
            // The strip that scrolls; it only draws, the touchpad handles its touches.
            val stripWidth = minOf(EDGE_WIDTH, maxWidth * TouchpadGestures.MAX_EDGE_FRACTION)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(stripWidth)
                    .background(Palette.SurfacePressed.copy(alpha = 0.35f)),
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(4.dp)
                        .fillMaxHeight(0.3f)
                        .clip(CircleShape)
                        .background(Palette.TextMuted.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/** Holds the pending [TapDrag] timeout so a new one can replace it. */
private class TimerSlot {
    var job: Job? = null
}

/** Width of the right-edge scroll strip: about libinput's 7 mm, and a 48 dp touch target. */
private val EDGE_WIDTH = 48.dp

/** Wheel units (120 = one notch) per dp of finger travel, at scroll speed 1×. */
private const val SCROLL_UNITS_PER_DP = 3f

/**
 * Reports "pressed" on touch and "released" on lift. The button stays held
 * until the finger lifts, even if it slides off, so it can be held for
 * drag-and-drop while another finger moves on the touchpad.
 */
@Composable
private fun ClickButton(
    label: String,
    onPress: (pressed: Boolean) -> Unit,
    modifier: Modifier,
) {
    val currentOnPress by rememberUpdatedState(onPress)
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val color by animateColorAsState(if (pressed) Palette.SurfacePressed else Palette.Surface, tween(60), label = "press")
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentOnPress(true)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.id == down.id && it.pressed })
                    } finally {
                        // Also runs if the gesture is cancelled, so a button never stays stuck.
                        pressed = false
                        currentOnPress(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.TextMuted, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- previews

@Composable
private fun TouchpadPreview(colors: Colors) {
    TouchpadTheme(colors) {
        TouchpadScreen(
            state = TouchpadClient.State.Connected(pcName = "MI-PC", address = "192.168.1.20", pingMs = 4),
            settings = Settings(),
            onMove = { _, _ -> },
            onScroll = { _, _ -> },
            onButton = { _, _ -> },
            onClick = {},
            onSettings = {},
            onUnpair = {},
        )
    }
}

@Preview(name = "Touchpad, dark", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
private fun TouchpadDarkPreview() = TouchpadPreview(DarkColors)

@Preview(name = "Touchpad, light", device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
private fun TouchpadLightPreview() = TouchpadPreview(LightColors)
