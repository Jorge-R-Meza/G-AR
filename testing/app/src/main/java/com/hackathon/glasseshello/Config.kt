package com.hackathon.glasseshello

/** Everything you're likely to want to change, in one place. */
object Config {
    /** Port for the Wi-Fi control server: http://<glasses-ip>:8080/open */
    const val HTTP_PORT = 8080

    /** Open the app's screen automatically when the glasses boot. Off: it just starts listening. */
    const val OPEN_ON_BOOT = false

    /** The same tag as the rest of the app, so one filter shows everything: adb logcat -s GlassesProbe */
    const val TAG = "GlassesProbe"
}
