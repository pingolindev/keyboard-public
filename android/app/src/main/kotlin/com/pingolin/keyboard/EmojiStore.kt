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
 * The recent emoji on storage. Credential-encrypted, so nothing is read or
 * kept before the first unlock. One per process, used on the main thread and
 * written on another.
 */
class EmojiStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE))
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "emoji-recent").apply { isDaemon = true } }
    private var loaded = false
    private var changed = false
    val recents = EmojiRecents()

    /** Reads what was kept, the first time. Only call when the phone is unlocked. */
    fun load() {
        if (loaded) return
        loaded = true
        if (!file.baseFile.isFile) return
        try {
            recents.readJson(file.readFully().toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "recent emoji unreadable: ${e.javaClass.simpleName}")
        }
    }

    /** [text] was typed. Only call when the phone is unlocked, and never in incognito. */
    fun used(text: String) = change { recents.used(text) }

    /** The user picked [tone] for [base]. Only call when the phone is unlocked, and never in incognito. */
    fun setTone(base: String, tone: Int) = change { recents.setTone(base, tone) }

    /** Forgets the recent emoji, here and on storage. */
    fun clear() {
        change { recents.clear() }
        save()
    }

    private fun change(block: () -> Unit) {
        load()
        block()
        changed = true
    }

    /** Writes what changed, off the main thread. */
    fun save() {
        if (!changed) return
        changed = false
        val json = recents.toJson()
        writer.execute {
            try {
                Languages.writeAtomically(file.baseFile, json.toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                Log.w(TAG, "recent emoji not saved: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "PingolinKeyboard"
        private const val FILE = "emoji.json"

        @Volatile private var instance: EmojiStore? = null

        fun get(context: Context): EmojiStore =
            instance ?: synchronized(this) { instance ?: EmojiStore(context).also { instance = it } }
    }
}
