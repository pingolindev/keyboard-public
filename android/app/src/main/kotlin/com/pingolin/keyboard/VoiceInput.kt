// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pingolin.keyboard.engine.VoiceModel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Records speech and turns it into text on the phone. Audio stays in memory
 * and is dropped once transcribed. Recording stops on [stop], after a pause in
 * speech, or after two minutes. Callbacks come on the main thread.
 */
class VoiceInput(private val listener: Listener) {
    interface Listener {
        /** Loudness, 0 to 1, a few times a second while listening. */
        fun onLevel(level: Float)

        fun onWriting()

        /** The text, or null if it failed. Empty when nothing was said. */
        fun onText(text: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = thread?.isAlive == true

    /** Starts listening. The caller has checked the microphone permission. */
    fun start(model: File, language: String?) {
        if (isRunning) return
        stopped = false
        cancelled = false
        thread = Thread({ run(model, language) }, "voice").apply { start() }
    }

    /** Stops listening and writes what was said. */
    fun stop() {
        stopped = true
    }

    /** Stops without writing anything. */
    fun cancel() {
        cancelled = true
        stopped = true
    }

    @SuppressLint("MissingPermission") // checked by the caller before start()
    private fun run(model: File, language: String?) {
        val recording = record() ?: return post { listener.onText(null) }
        if (cancelled) return
        // Nothing loud enough to be speech: whisper would invent words.
        if (!recording.heard) return post { listener.onText("") }
        val pcm = recording.pcm
        post { listener.onWriting() }
        val text = try {
            val voice = VoiceModel.open(model.absolutePath)
            try {
                voice.transcribePcm16(pcm, language, THREADS.toUInt())
            } finally {
                voice.destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice typing failed: ${e.javaClass.simpleName}")
            null
        }
        if (!cancelled) post { listener.onText(text?.let(::clean)) }
    }

    private class Recording(val pcm: ByteArray, val heard: Boolean)

    @SuppressLint("MissingPermission")
    private fun record(): Recording? {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE / 5 * 2))
        } catch (e: Exception) {
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val out = ByteArrayOutputStream()
        val chunk = ShortArray(RATE / 10) // 100 ms
        val bytes = ByteArray(chunk.size * 2)
        var heard = false
        var quietMs = 0
        try {
            recorder.startRecording()
            while (!stopped && out.size() < MAX_BYTES) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) {
                    val s = chunk[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[2 * i] = (s and 0xFF).toByte()
                    bytes[2 * i + 1] = (s shr 8 and 0xFF).toByte()
                }
                out.write(bytes, 0, 2 * n)
                val level = (sqrt(sum / n) / 32768.0).toFloat()
                post { listener.onLevel((level * 8).coerceIn(0f, 1f)) }
                if (level > SPEECH) {
                    heard = true
                    quietMs = 0
                } else if (heard) {
                    quietMs += n * 1000 / RATE
                    if (quietMs >= PAUSE_MS) break
                }
            }
        } finally {
            try {
                recorder.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            recorder.release()
        }
        return Recording(out.toByteArray(), heard)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        private const val TAG = "PingolinKeyboard"
        const val RATE = 16_000
        private const val MAX_BYTES = RATE * 2 * 120

        /** Loudness (RMS of full scale) that counts as speech, and the pause that ends it. */
        private const val SPEECH = 0.02
        private const val PAUSE_MS = 2_000
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /** Whisper marks silence and sounds in brackets: "[BLANK_AUDIO]", "(music)". */
        fun clean(text: String): String =
            text.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), "").replace(Regex("""\s+"""), " ").trim()
    }
}
