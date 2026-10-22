package me.rerere.rikkahub.data.power

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/** Observe the system value even when auto power saving is off; no polling or Root is needed. */
class AndroidFlightModeMonitor(context: Context, private val controller: ReadingPowerController) {
    private val resolver = context.applicationContext.contentResolver
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { controller.refreshSystemFlightMode() }
    }

    fun start() {
        try {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, observer)
        } catch (failure: Exception) {
            // Foreground refresh and every controlled transition still read the real system value.
            Log.w("ReadingPower", "System flight-mode observation unavailable", failure)
        }
        controller.refreshSystemFlightMode()
    }
}
