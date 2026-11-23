// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The clipboard history on storage: pinned clips always, the others only when
 * the user keeps the history. Credential-encrypted, so before the first unlock
 * the history is in memory only. One per process, used on the main thread and
 * written on another.
 */
class ClipboardStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE))
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "clipboard").apply { isDaemon = true } }
    val history = ClipboardHistory()
    private var loaded = false

    /** Reads the kept clips the first time. Only call when the phone is unlocked. */
    fun load(keepAll: Boolean) {
        history.keepAll = keepAll
        if (loaded) return
        loaded = true
        if (!file.baseFile.isFile) return
        try {
            history.mergeJson(file.readFully().toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "clipboard history unreadable: ${e.javaClass.simpleName}")
        }
    }

    /** Writes what is kept: the pinned clips, and the others when the user keeps the history. */
    fun save() {
        if (!loaded) return
        val json = history.toJson(everything = history.keepAll)
        writer.execute {
            try {
                Languages.writeAtomically(file.baseFile, json.toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "clipboard history not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Forgets every clip, pinned ones too, in memory and on storage. */
    fun forget() {
        history.clear(pinnedToo = true)
        writer.execute { file.delete() }
    }

    companion object {
        private const val TAG = "PingolinKeyboard"
        private const val FILE = "clipboard.json"

        @Volatile private var instance: ClipboardStore? = null

        fun get(context: Context): ClipboardStore =
            instance ?: synchronized(this) { instance ?: ClipboardStore(context).also { instance = it } }
    }
}
