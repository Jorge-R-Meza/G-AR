package com.hackathon.glasseshello

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The glasses' end of the voice assistant: sends the microphone to the laptop, plays what the
 * laptop says, and hands over what it wants shown on the lens. All the thinking happens on the
 * laptop (voice_assistant/assistant.py); the frames are described in voice_assistant/link.py.
 *
 * NOT COMPILED YET. It lives outside the app so it can't break the build. To wire it in:
 *   1. Copy this file to testing/app/src/main/java/com/hackathon/glasseshello/.
 *   2. Start it in KeepAliveService.onCreate, next to the report server:
 *          voice = VoiceLink { json -> MainActivity.deliverAnswer(json) }.also { it.start() }
 *      and call voice?.shutdown() in onDestroy.
 *   3. Add MainActivity.deliverAnswer(json): draw "detail" (a JSON array of short lines) on a card.
 *   4. The probe card's microphone meter (MainActivity.runMic) and this class cannot both hold
 *      the microphone. Stop the meter, or feed it from here.
 *
 * One laptop at a time. Over USB: adb forward tcp:32890 tcp:32890.
 * Anything on the same network can connect and listen: fine for a demo, not for real patients.
 */
class VoiceLink(private val onShow: (String) -> Unit) : Thread("VoiceLink") {

    companion object {
        /** The port after the report server's. */
        const val PORT = 32890
        private const val MIC = 1
        private const val SPEAK = 2
        private const val SHOW = 3
        private const val HELLO = 4
        /** The laptop always sends speech at this rate. */
        private const val SPEAKER_RATE = 16000
        private const val FRAME_LIMIT = 1 shl 20
    }

    @Volatile private var server: ServerSocket? = null

    override fun run() {
        try {
            val open = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            server = open
            Log.i(Config.TAG, "voice link: listening on port $PORT")
            while (!isInterrupted) {
                open.accept().use { serve(it) }
            }
        } catch (e: IOException) {
            Log.i(Config.TAG, "voice link stopped: ${e.message}")
        }
    }

    fun shutdown() {
        interrupt()
        server?.close()
    }

    /** One laptop, from connecting to hanging up: the microphone goes out on this thread. */
    private fun serve(socket: Socket) {
        val recorder = openMicrophone()
        if (recorder == null) {
            Log.e(Config.TAG, "voice link: could not open the microphone")
            return
        }
        socket.tcpNoDelay = true
        val speaker = thread(name = "VoiceLink-speaker") { playAndShow(socket) }
        try {
            val out = DataOutputStream(socket.getOutputStream())
            send(out, HELLO, """{"rate":${recorder.sampleRate}}""".toByteArray())
            recorder.startRecording()
            Log.i(Config.TAG, "voice link: laptop connected, microphone at ${recorder.sampleRate} Hz")
            // 40 ms of 16-bit mono: small enough to fit one Wi-Fi packet.
            val chunk = ByteArray(recorder.sampleRate * 2 * 40 / 1000)
            while (!socket.isClosed) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break
                send(out, MIC, chunk, read)
            }
        } catch (e: IOException) {
            Log.i(Config.TAG, "voice link: laptop went away: ${e.message}")
        } finally {
            recorder.release()
            socket.close()   // also ends the speaker thread's read
            speaker.join(1000)
        }
    }

    /** The other direction, on its own thread: speech to the speaker, answers to the lens. */
    private fun playAndShow(socket: Socket) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SPEAKER_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(
                SPEAKER_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val type = input.readUnsignedByte()
                val length = input.readInt()   // big-endian, as the laptop writes it
                if (length < 0 || length > FRAME_LIMIT) throw IOException("frame of $length bytes")
                val payload = ByteArray(length)
                input.readFully(payload)
                when (type) {
                    SPEAK -> track.write(payload, 0, length)   // blocks while the speaker catches up
                    SHOW -> onShow(String(payload, Charsets.UTF_8))
                }
            }
        } catch (e: IOException) {
            // The laptop hung up, or serve() closed the socket.
        } finally {
            track.release()
        }
    }

    private fun send(out: DataOutputStream, type: Int, payload: ByteArray, length: Int = payload.size) {
        out.writeByte(type)
        out.writeInt(length)
        out.write(payload, 0, length)
        out.flush()
    }

    /**
     * 16 kHz is what speech recognition wants; the others are fallbacks, and the hello tells the
     * laptop which one it got. MediaRecorder.AudioSource.VOICE_COMMUNICATION is worth trying in
     * place of MIC: where the hardware supports it, Android then removes the speaker's echo itself.
     */
    private fun openMicrophone(): AudioRecord? {
        for (rate in intArrayOf(16000, 44100, 48000)) {
            val minimum = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            try {
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, rate * 2 / 5))
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
                recorder.release()
            } catch (e: RuntimeException) {   // no permission, or the rate was refused
                Log.w(Config.TAG, "voice link: microphone refused $rate Hz", e)
            }
        }
        return null
    }
}
