package com.hackathon.glasseshello

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A tiny HTTP server so a phone or laptop on the same Wi-Fi can control the app:
 *
 *   curl http://<glasses-ip>:8080/open
 *   curl http://<glasses-ip>:8080/close     (exits the app, which forgets what was scanned)
 *   curl http://<glasses-ip>:8080/ping
 *
 * Anyone on the same network can reach it. Add your own paths in [route].
 */
class ControlServer(private val context: Context, private val port: Int) : Thread("ControlServer") {

    @Volatile private var serverSocket: ServerSocket? = null

    override fun run() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            serverSocket = server
            Log.i(Config.TAG, "Control server listening on port $port")
            while (!isInterrupted) {
                server.accept().use { handle(it) }
            }
        } catch (e: IOException) {
            Log.i(Config.TAG, "Control server stopped: ${e.message}")
        }
    }

    private fun route(path: String): Pair<Int, String> = when (path) {
        "/open" -> {
            AppLauncher.open(context, "wifi")
            200 to """{"ok":true}"""
        }
        "/close" -> {
            AppLauncher.close()
            200 to """{"ok":true}"""
        }
        "/", "/ping" -> 200 to """{"ok":true,"app":"${context.packageName}","visible":${MainActivity.isVisible()}}"""
        else -> 404 to """{"ok":false,"error":"unknown path"}"""
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val requestLine = client.getInputStream().bufferedReader().readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val (status, body) = route(path)
            val bytes = body.toByteArray()
            val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(header.toByteArray())
                write(bytes)
                flush()
            }
        } catch (e: IOException) {
            Log.w(Config.TAG, "Control request failed: ${e.message}")
        }
    }

    fun shutdown() {
        interrupt()
        serverSocket?.close()
    }
}
