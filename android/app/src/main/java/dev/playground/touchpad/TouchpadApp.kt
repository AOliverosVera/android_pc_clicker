package dev.playground.touchpad

import android.app.Application
import android.os.Build
import dev.playground.touchpad.net.PairingStore
import dev.playground.touchpad.net.TouchpadClient

/** Holds the single [TouchpadClient] shared by the UI and [ConnectionService]. */
class TouchpadApp : Application() {
    val client: TouchpadClient by lazy {
        // The model name is only shown on the PC during pairing, to identify the phone.
        TouchpadClient(PairingStore(getSharedPreferences("touchpad", MODE_PRIVATE)), Build.MODEL ?: "Android")
    }
}
