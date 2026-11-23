// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.pingolin.keyboard.engine.UserModel
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The words the keyboard learned, in credential-encrypted storage (readable
 * after the first unlock). One per process, shared by the keyboard and its
 * settings.
 */
class LearnedWords private constructor(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE))
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "learned-words").apply { isDaemon = true } }
    private var model: UserModel? = null
    private var savedChanges = 0UL
    private var pending: ScheduledFuture<*>? = null

    /** The learned words, read from storage the first time. Only call when unlocked. */
    @Synchronized
    fun load(): UserModel {
        model?.let { return it }
        val m = UserModel()
        if (file.baseFile.isFile) {
            try {
                m.mergeJson(file.readFully().toString(Charsets.UTF_8))
            } catch (e: Exception) {
                // Kept aside, not overwritten by the next save.
                Log.w(TAG, "learned words unreadable: ${e.javaClass.simpleName}")
                file.baseFile.renameTo(File(file.baseFile.parentFile, UNREADABLE))
            }
        }
        savedChanges = m.changeCount()
        model = m
        return m
    }

    /** Saves after a short pause, so a burst of words is one write. */
    @Synchronized
    fun saveSoon(delayMillis: Long = SAVE_DELAY_MS) {
        if (delayMillis > 0 && pending?.isDone == false) return
        pending?.cancel(false)
        pending = saver.schedule({ save() }, delayMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun save() {
        val m = model ?: return
        val changes = m.changeCount()
        if (changes == savedChanges) return
        try {
            Languages.writeAtomically(file.baseFile, m.toJson().toByteArray(Charsets.UTF_8))
            savedChanges = changes
        } catch (e: java.io.IOException) {
            Log.w(TAG, "learned words not saved: ${e.javaClass.simpleName}")
        }
    }

    /** Forgets every learned word, in memory and on storage. */
    @Synchronized
    fun clear() {
        pending?.cancel(false)
        model?.clear()
        file.delete()
        File(file.baseFile.parentFile, UNREADABLE).delete()
        savedChanges = model?.changeCount() ?: 0UL
    }

    companion object {
        private const val TAG = "PingolinKeyboard"
        private const val FILE = "learned.json"
        private const val UNREADABLE = "learned-unreadable.json"
        private const val SAVE_DELAY_MS = 5_000L

        @Volatile private var instance: LearnedWords? = null

        fun get(context: Context): LearnedWords =
            instance ?: synchronized(this) { instance ?: LearnedWords(context).also { instance = it } }

        /** Days since 1970-01-01, for "last used". */
        fun today(): UInt = (System.currentTimeMillis() / 86_400_000L).toUInt()
    }
}
