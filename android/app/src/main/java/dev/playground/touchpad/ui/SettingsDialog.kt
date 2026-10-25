package dev.playground.touchpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.playground.touchpad.R
import dev.playground.touchpad.settings.Settings
import dev.playground.touchpad.settings.Theme

/** Appearance, pointer and scrolling settings. Every change applies at once. */
@Composable
fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { SettingsPanel(settings, onChange, onDismiss) }
}

@Composable
private fun SettingsPanel(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Palette.Surface, modifier = Modifier.widthIn(max = 520.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(
                stringResource(R.string.settings_title),
                color = Palette.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Section(stringResource(R.string.settings_appearance))
            Choices(
                options = listOf(
                    Theme.DARK to stringResource(R.string.theme_dark),
                    Theme.LIGHT to stringResource(R.string.theme_light),
                    Theme.SYSTEM to stringResource(R.string.theme_system),
                ),
                selected = settings.theme,
                onSelect = { onChange(settings.copy(theme = it)) },
            )

            Section(stringResource(R.string.settings_pointer))
            SliderRow(
                label = stringResource(R.string.settings_pointer_speed),
                value = settings.pointerSpeed,
                range = Settings.POINTER_SPEED,
                shown = stringResource(R.string.settings_multiplier, settings.pointerSpeed),
                onChange = { onChange(settings.copy(pointerSpeed = it)) },
            )
            SliderRow(
                label = stringResource(R.string.settings_acceleration),
                value = settings.acceleration,
                range = Settings.ACCELERATION,
                shown = if (settings.acceleration < 0.05f) {
                    stringResource(R.string.settings_off)
                } else {
                    stringResource(R.string.settings_multiplier, settings.acceleration)
                },
                onChange = { onChange(settings.copy(acceleration = if (it < 0.05f) 0f else it)) },
            )

            Section(stringResource(R.string.settings_scrolling))
            Label(stringResource(R.string.settings_two_finger_direction))
            DirectionChoices(settings.twoFingerDownScrollsUp) { onChange(settings.copy(twoFingerDownScrollsUp = it)) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.settings_edge_scroll), Modifier.weight(1f))
                Switch(checked = settings.edgeScroll, onCheckedChange = { onChange(settings.copy(edgeScroll = it)) })
            }
            if (settings.edgeScroll) {
                Label(stringResource(R.string.settings_edge_direction))
                DirectionChoices(settings.edgeDownScrollsUp) { onChange(settings.copy(edgeDownScrollsUp = it)) }
            }
            Spacer(Modifier.height(4.dp))
            SliderRow(
                label = stringResource(R.string.settings_scroll_speed),
                value = settings.scrollSpeed,
                range = Settings.SCROLL_SPEED,
                shown = stringResource(R.string.settings_multiplier, settings.scrollSpeed),
                onChange = { onChange(settings.copy(scrollSpeed = it)) },
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onChange(Settings(theme = settings.theme)) }) {
                    Text(stringResource(R.string.settings_reset), color = Palette.TextMuted)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_done), color = Palette.Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title.uppercase(), color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.TextPrimary, fontSize = 15.sp, modifier = modifier.padding(bottom = 6.dp))
}

@Composable
private fun DirectionChoices(downScrollsUp: Boolean, onSelect: (Boolean) -> Unit) {
    Choices(
        options = listOf(true to stringResource(R.string.settings_scrolls_up), false to stringResource(R.string.settings_scrolls_down)),
        selected = downScrollsUp,
        onSelect = onSelect,
    )
}

/** A row of mutually exclusive options, like a segmented control. */
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Background)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Palette.Accent else Palette.Background)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Palette.OnAccent else Palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    shown: String,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(shown, color = Palette.TextMuted, fontSize = 14.sp)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

// ---------------------------------------------------------------- previews

@Preview(name = "Settings, dark", heightDp = 900)
@Composable
private fun SettingsDarkPreview() {
    TouchpadTheme(DarkColors) { SettingsPanel(Settings(), onChange = {}, onDismiss = {}) }
}

@Preview(name = "Settings, light", heightDp = 900)
@Composable
private fun SettingsLightPreview() {
    TouchpadTheme(LightColors) { SettingsPanel(Settings(theme = Theme.LIGHT), onChange = {}, onDismiss = {}) }
}
