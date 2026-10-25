package dev.playground.touchpad.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Theme { DARK, LIGHT, SYSTEM }

/**
 * What the user can change in the settings dialog.
 *
 * Scroll direction is chosen per gesture because the usual defaults differ:
 * two-finger scrolling follows the fingers, like a phone and like Windows
 * precision touchpads ("down motion scrolls up"); edge scrolling works like a
 * scroll bar ("down motion scrolls down").
 */
data class Settings(
    val theme: Theme = Theme.DARK,
    /** Pointer speed multiplier. */
    val pointerSpeed: Float = 1f,
    /** How much faster quick flicks go; 0 turns acceleration off. */
    val acceleration: Float = 1f,
    /** Scroll speed multiplier. */
    val scrollSpeed: Float = 1f,
    val twoFingerDownScrollsUp: Boolean = true,
    val edgeScroll: Boolean = true,
    val edgeDownScrollsUp: Boolean = false,
) {
    companion object {
        val POINTER_SPEED = 0.4f..2.5f
        val ACCELERATION = 0f..2f
        val SCROLL_SPEED = 0.25f..3f
    }
}

/** Keeps [Settings] in SharedPreferences and publishes changes. */
class SettingsStore(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit {
            putString(THEME, next.theme.name)
            putFloat(POINTER_SPEED, next.pointerSpeed)
            putFloat(ACCELERATION, next.acceleration)
            putFloat(SCROLL_SPEED, next.scrollSpeed)
            putBoolean(TWO_FINGER_UP, next.twoFingerDownScrollsUp)
            putBoolean(EDGE_SCROLL, next.edgeScroll)
            putBoolean(EDGE_UP, next.edgeDownScrollsUp)
        }
    }

    private fun load(): Settings {
        val d = Settings()
        // Values are clamped, so a damaged or old file can't make the pointer unusable.
        return Settings(
            theme = Theme.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: d.theme,
            pointerSpeed = prefs.getFloat(POINTER_SPEED, d.pointerSpeed).coerceIn(Settings.POINTER_SPEED),
            acceleration = prefs.getFloat(ACCELERATION, d.acceleration).coerceIn(Settings.ACCELERATION),
            scrollSpeed = prefs.getFloat(SCROLL_SPEED, d.scrollSpeed).coerceIn(Settings.SCROLL_SPEED),
            twoFingerDownScrollsUp = prefs.getBoolean(TWO_FINGER_UP, d.twoFingerDownScrollsUp),
            edgeScroll = prefs.getBoolean(EDGE_SCROLL, d.edgeScroll),
            edgeDownScrollsUp = prefs.getBoolean(EDGE_UP, d.edgeDownScrollsUp),
        )
    }

    private companion object {
        const val THEME = "theme"
        const val POINTER_SPEED = "pointer_speed"
        const val ACCELERATION = "acceleration"
        const val SCROLL_SPEED = "scroll_speed"
        const val TWO_FINGER_UP = "two_finger_down_scrolls_up"
        const val EDGE_SCROLL = "edge_scroll"
        const val EDGE_UP = "edge_down_scrolls_up"
    }
}
