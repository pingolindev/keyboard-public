// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import com.pingolin.keyboard.engine.Dictionary
import com.pingolin.keyboard.engine.GlideOptions
import com.pingolin.keyboard.engine.KeyCenter
import com.pingolin.keyboard.engine.Point
import com.pingolin.keyboard.engine.PredictOptions
import com.pingolin.keyboard.engine.SuggestOptions
import com.pingolin.keyboard.engine.Suggester
import com.pingolin.keyboard.engine.Tap
import com.pingolin.keyboard.engine.UserModel
import java.util.concurrent.Executors

/** One key press of the word being typed, with where it landed (null when unknown). */
data class TypedKey(val text: String, val x: Float?, val y: Float?)

/** A point of a glide over the keys, in view pixels. */
data class GlidePoint(val x: Float, val y: Float)

/** What a word follows: the previous word, or the start of a sentence. */
data class WordContext(val previous: String?, val sentenceStart: Boolean) {
    companion object {
        val NONE = WordContext(null, false)
    }
}

/** The engine's answer for the word being typed. */
data class WordSuggestions(
    val typed: String,
    val typedIsWord: Boolean,
    /** What a space commits instead of [typed], if anything. */
    val autocorrect: String?,
    /** Best first, never [typed] itself. */
    val candidates: List<String>,
)

interface WordSuggester {
    /**
     * Suggestions for the word made of [keys], or null while the dictionary
     * loads. [personal]: use the learned words (not in incognito).
     */
    fun suggest(keys: List<TypedKey>, autocorrect: Boolean, context: WordContext, personal: Boolean): WordSuggestions?

    /** Words that may come next. */
    fun predict(context: WordContext, personal: Boolean): List<String>

    /** The words a finger's path over the keys may spell, best first (glide typing). */
    fun glide(points: List<GlidePoint>, context: WordContext, personal: Boolean): List<String>

    /** The user typed [word] where learning is allowed. */
    fun learn(word: String, context: WordContext)

    /** The user kept [word] against an autocorrection, where learning is allowed. */
    fun keep(word: String)

    /** The same where learning is off: remembered only while the keyboard runs. */
    fun reject(word: String)
}

/**
 * The Rust engine with the languages the user types in and their learned
 * words. Dictionaries load on a background thread; suggestions start once
 * the first is in. Typed text is never logged.
 */
class Engine(context: Context) : WordSuggester {
    private val languages = Languages.get(context)
    private val learned = LearnedWords.get(context)
    private val lock = Any()
    private var suggester: Suggester? = null
    private var model: UserModel? = null
    private var keys: List<KeyCenter> = emptyList()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keyboard-engine").apply { isDaemon = true } }

    /**
     * The languages to type in, the current one first, and whether the
     * phone is unlocked: before the first unlock only built-in dictionaries
     * and no learned words are used.
     */
    fun configure(tags: List<String>, unlocked: Boolean) {
        worker.execute {
            val dictionaries = tags.mapNotNull { languages.load(it, unlocked) }
                .ifEmpty { listOfNotNull(languages.load(Languages.FALLBACK, unlocked)) }
            val first = dictionaries.firstOrNull() ?: return@execute
            val userModel = if (unlocked) learned.load() else null
            val s = synchronized(lock) {
                val s = suggester ?: Suggester(first).also { suggester = it }
                s.setDictionaries(dictionaries)
                s.setUserModel(userModel)
                s.setKeys(keys)
                model = userModel
                s
            }
            s.refreshUserWords()
        }
    }

    /** Compiles newly known words here, not on the main thread. */
    private fun refreshSoon(s: Suggester) {
        worker.execute { s.refreshUserWords() }
    }

    /** The letter keys on screen, in view pixels. */
    fun setKeys(keys: List<KeyCenter>) {
        synchronized(lock) {
            this.keys = keys
            suggester?.setKeys(keys)
        }
    }

    private fun current(): Suggester? = synchronized(lock) { suggester }

    override fun suggest(keys: List<TypedKey>, autocorrect: Boolean, context: WordContext, personal: Boolean): WordSuggestions? {
        val engine = current() ?: return null
        val taps = keys.map { key ->
            val position = if (key.x != null && key.y != null) Point(key.x, key.y) else null
            Tap(key.text, position)
        }
        val options = SuggestOptions(
            autocorrect = autocorrect,
            blockOffensive = true,
            sentenceStart = context.sentenceStart,
            previousWord = context.previous,
            maxResults = MAX_RESULTS.toUInt(),
            personal = personal,
        )
        val result = engine.suggest(taps, options)
        return WordSuggestions(
            typed = result.typed,
            typedIsWord = result.typedIsWord,
            autocorrect = result.autocorrect,
            candidates = result.candidates.map { it.text },
        )
    }

    override fun glide(points: List<GlidePoint>, context: WordContext, personal: Boolean): List<String> {
        val engine = current() ?: return emptyList()
        val options = GlideOptions(
            sentenceStart = context.sentenceStart,
            previousWord = context.previous,
            maxResults = MAX_RESULTS.toUInt(),
            personal = personal,
        )
        return engine.glide(points.map { Point(it.x, it.y) }, options).map { it.text }
    }

    override fun predict(context: WordContext, personal: Boolean): List<String> {
        val engine = current() ?: return emptyList()
        val options = PredictOptions(
            sentenceStart = context.sentenceStart,
            previousWord = context.previous,
            maxResults = PREDICTIONS.toUInt(),
            personal = personal,
        )
        return engine.predict(options).map { it.text }
    }

    override fun learn(word: String, context: WordContext) {
        val (m, s) = synchronized(lock) { model to suggester }
        if (m == null || s == null) return
        val previous = context.previous ?: if (context.sentenceStart) SENTENCE_START else null
        m.learn(s.learningForm(word, context.sentenceStart), previous, LearnedWords.today())
        refreshSoon(s)
        learned.saveSoon()
    }

    override fun keep(word: String) {
        val (m, s) = synchronized(lock) { model to suggester }
        if (m == null || s == null) return
        m.keep(word, LearnedWords.today())
        refreshSoon(s)
        learned.saveSoon()
    }

    override fun reject(word: String) {
        current()?.reject(word)
    }

    /** Saves learned words now (the keyboard is closing). */
    fun save() {
        learned.saveSoon(delayMillis = 0)
    }

    private companion object {
        const val MAX_RESULTS = 5
        const val PREDICTIONS = 3
        const val SENTENCE_START = "<s>"
    }
}

/** Reads a dictionary's display name and layout. */
fun Dictionary.info(): Pair<String, String> =
    (metadata("name") ?: locale()) to (metadata("layout") ?: Languages.DEFAULT_LAYOUT)
