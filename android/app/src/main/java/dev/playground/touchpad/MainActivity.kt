package dev.playground.touchpad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.playground.touchpad.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val client by lazy { (application as TouchpadApp).client }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsOnce()
        setContent {
            val state by client.state.collectAsStateWithLifecycle()
            AppRoot(state, client)
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
