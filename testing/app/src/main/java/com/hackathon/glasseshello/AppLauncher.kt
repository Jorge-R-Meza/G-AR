package com.hackathon.glasseshello

import android.content.Context
import android.content.Intent
import android.util.Log

/** Opens or closes the app's screen. Works from the background on Android 9. */
object AppLauncher {

    fun open(context: Context, reason: String) {
        Log.i(Config.TAG, "Opening app ($reason)")
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Exits the app, as a double tap does: what was scanned is forgotten. */
    fun close() {
        Log.i(Config.TAG, "Closing app")
        MainActivity.close()
    }
}
