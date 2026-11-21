package com.hackathon.glasseshello

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * A foreground service, so Android keeps the app running in the background.
 * It runs the Wi-Fi control server and the server that takes patient reports from the laptop,
 * so both keep listening while another app is in front. Put anything else that should always
 * run here.
 */
class KeepAliveService : Service() {

    private var server: ControlServer? = null
    private var reports: ReportServer? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("keepalive", "Background", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "keepalive")
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)

        server = ControlServer(applicationContext, Config.HTTP_PORT).also { it.start() }
        // A report or a medicine goes to the open app; while the app is closed it is turned away.
        reports = ReportServer(
            { report -> MainActivity.deliver(report) },
            { medicine -> MainActivity.deliver(medicine) },
        ).also { it.start() }
        Log.i(Config.TAG, "KeepAliveService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.shutdown()
        reports?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
