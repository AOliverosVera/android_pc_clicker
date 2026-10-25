package dev.playground.touchpad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.playground.touchpad.settings.Theme
import dev.playground.touchpad.ui.AppRoot
import dev.playground.touchpad.ui.DarkColors
import dev.playground.touchpad.ui.LightColors
import dev.playground.touchpad.ui.TouchpadTheme

class MainActivity : ComponentActivity() {

    private val client by lazy { (application as TouchpadApp).client }
    private val settingsStore by lazy { (application as TouchpadApp).settings }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsOnce()
        setContent {
            val state by client.state.collectAsStateWithLifecycle()
            val settings by settingsStore.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                Theme.DARK -> true
                Theme.LIGHT -> false
                Theme.SYSTEM -> isSystemInDarkTheme()
            }
            val colors = if (dark) DarkColors else LightColors
            LaunchedEffect(dark) {
                // Status and navigation bar icons must contrast with the app's background.
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.setBackgroundDrawable(colors.background.toArgb().toDrawable())
            }
            TouchpadTheme(colors) {
                AppRoot(state, client, settings, onSettingsChange = { new -> settingsStore.update { new } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Idempotent: connects on first launch, re-attaches after "Disconnect".
        ConnectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        client.pingNow()
    }

    /** The notification only shows connection status; the app works without it. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("touchpad", MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit { putBoolean(KEY_ASKED, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val KEY_ASKED = "asked_notifications"
    }
}
