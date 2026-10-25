package dev.playground.touchpad

import android.app.Application
import android.os.Build
import dev.playground.touchpad.net.PairingStore
import dev.playground.touchpad.net.TouchpadClient
import dev.playground.touchpad.settings.SettingsStore

/** Holds the single [TouchpadClient] shared by the UI and [ConnectionService], and the settings. */
class TouchpadApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(getSharedPreferences("settings", MODE_PRIVATE)) }

    val client: TouchpadClient by lazy {
        // The model name is only shown on the PC during pairing, to identify the phone.
        TouchpadClient(PairingStore(getSharedPreferences("touchpad", MODE_PRIVATE)), Build.MODEL ?: "Android")
    }
}
