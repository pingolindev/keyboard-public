// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.pingolin.keyboard.engine.EmojiException
import com.pingolin.keyboard.engine.EmojiIndex
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The emoji this phone can draw, named in the user's languages, and the search
 * over them. Read off the main thread, once per set of languages.
 */
class EmojiData private constructor(context: Context) {
    /** The emoji in one set of languages. */
    class Tables(val items: List<EmojiItem>, private val index: EmojiIndex) {
        private val byText = items.associateBy { it.text }
        private val byTone = items.flatMap { item -> item.tones.map { it to item } }.toMap()

        /** The emoji that match every word of [query], best first. */
        fun search(query: String): List<EmojiCell> = index.search(query, SEARCHED.toUInt())
            .mapNotNull { byText[it] }.take(SHOWN).map { EmojiCell(it) }

        /** The emoji a typed word stands for, if any. */
        fun suggest(word: String): EmojiItem? = index.suggest(word)?.let { byText[it] }

        /** The recent emoji this phone can draw. */
        fun recent(recents: EmojiRecents): List<EmojiCell> =
            recents.recent().mapNotNull { text -> (byText[text] ?: byTone[text])?.let { EmojiCell(it, text) } }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "emoji").apply { isDaemon = true }
    }
    private var loaded: List<String>? = null
    private var waiting = ArrayList<(Tables) -> Unit>()

    /** The emoji last read, for the languages asked for then. */
    var tables: Tables? = null
        private set

    /**
     * Reads the emoji for [tags], the user's languages, unless they are the
     * ones in [tables]. Called on the main thread; so is [onReady].
     */
    fun load(tags: List<String>, onReady: (Tables) -> Unit = {}) {
        if (tags == loaded) {
            val ready = tables
            if (ready != null) onReady(ready) else waiting.add(onReady)
            return
        }
        loaded = tags
        // The old languages' emoji stay until the new ones are read.
        waiting = arrayListOf(onReady)
        worker.execute {
            val read = read(tags)
            main.post {
                if (tags != loaded) return@post // the languages changed meanwhile
                tables = read
                val callbacks = waiting
                waiting = ArrayList()
                if (read != null) callbacks.forEach { it(read) }
            }
        }
    }

    private fun read(tags: List<String>): Tables? = try {
        val typed = tags.mapNotNull { asset("names/$it.tsv") ?: asset("names/${it.substringBefore('-')}.tsv") }
        // English names are searched in every language.
        val english = if (Languages.FALLBACK in tags) null else asset("names/${Languages.FALLBACK}.tsv")
        val index = EmojiIndex(asset("list.tsv") ?: ByteArray(0), typed + listOfNotNull(english), typed.size.toUInt())
        // An emoji the phone's font lacks would show as a box. Fonts have all
        // the skin tones of an emoji or none.
        val paint = Paint()
        val items = index.all().filter { paint.hasGlyph(it.text) }.map { e ->
            val tones = if (e.tones.isNotEmpty() && paint.hasGlyph(e.tones.first())) e.tones else emptyList()
            EmojiItem(e.text, e.group.toInt(), e.name, tones)
        }
        Tables(items, index)
    } catch (e: EmojiException) {
        Log.w(TAG, "no emoji: ${e.javaClass.simpleName}")
        null
    }

    private fun asset(name: String): ByteArray? = try {
        app.assets.open("emoji/$name").use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val TAG = "PingolinKeyboard"

        /** Matches asked of the engine: more than are shown, as the phone may not draw them all. */
        private const val SEARCHED = 160
        private const val SHOWN = 48

        @Volatile private var instance: EmojiData? = null

        fun get(context: Context): EmojiData =
            instance ?: synchronized(this) { instance ?: EmojiData(context).also { instance = it } }
    }
}
