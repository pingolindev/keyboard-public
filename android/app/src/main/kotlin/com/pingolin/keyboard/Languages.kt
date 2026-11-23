// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.pingolin.keyboard.engine.Dictionary
import java.io.File
import java.security.MessageDigest

/** A language the keyboard can type in. */
data class Language(val tag: String, val name: String, val layout: String, val builtIn: Boolean)

/** What happened to a file the user opened as a language. */
sealed interface InstallResult {
    data class Installed(val language: Language) : InstallResult

    /** Not one of the language files this version of the app was built with. */
    data object NotOfficial : InstallResult

    data object Invalid : InstallResult

    /** It could not be saved (storage full). */
    data object Failed : InstallResult
}

/**
 * The dictionaries: built into the APK (readable at the lock screen), or
 * language files the user opened, kept in credential-encrypted storage (usable
 * after the first unlock). A file is accepted when it is one of this version's
 * official files, or when the user allows other files. The engine checks every
 * file before using it.
 */
class Languages private constructor(context: Context) {
    private val app = context.applicationContext
    private val packs: File get() = File(app.filesDir, "languages")
    private val cache = HashMap<String, Dictionary>()

    /** Every official language file: tag -> (sha256, name, layout). */
    val official: Map<String, OfficialFile> by lazy { readOfficial() }

    data class OfficialFile(val sha256: String, val name: String, val layout: String)

    private fun readOfficial(): Map<String, OfficialFile> = try {
        app.assets.open(OFFICIAL).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size == 4 && !line.startsWith("#")) f[0] to OfficialFile(f[1], f[2], f[3]) else null
            }.toMap()
        }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "no list of official language files: ${e.javaClass.simpleName}")
        emptyMap()
    }

    /** Languages built into the APK. */
    val builtIn: List<String> by lazy {
        app.assets.list(BUILT_IN)?.filter { it.endsWith(SUFFIX) }?.map { it.removeSuffix(SUFFIX) }?.sorted().orEmpty()
    }

    /** Every language that can be used now: built in, and opened files once unlocked. */
    @Synchronized
    fun available(unlocked: Boolean): List<Language> {
        val inApk = builtIn.map { tag ->
            val o = official[tag]
            Language(tag, o?.name ?: tag, o?.layout ?: DEFAULT_LAYOUT, builtIn = true)
        }
        if (!unlocked) return inApk
        val opened = readIndex().filter { it.tag !in builtIn }
        return (inApk + opened).sortedBy { it.name.lowercase() }
    }

    /** The dictionary of [tag], or null if it is not available (yet). */
    @Synchronized
    fun load(tag: String, unlocked: Boolean): Dictionary? {
        cache[tag]?.let { return it }
        val bytes = try {
            if (tag in builtIn) {
                app.assets.open("$BUILT_IN/$tag$SUFFIX").use { it.readBytes() }
            } else if (unlocked && isTag(tag)) {
                File(packs, "$tag$SUFFIX").takeIf { it.isFile }?.readBytes()
            } else {
                null
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "cannot read language $tag: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val dictionary = try {
            Dictionary(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "language $tag is not a valid dictionary: ${e.javaClass.simpleName}")
            return null
        }
        cache[tag] = dictionary
        return dictionary
    }

    /**
     * Installs a language file the user opened. Needs the phone unlocked.
     * Hashing and checking happen before the lock the keyboard also takes.
     */
    fun install(bytes: ByteArray, allowOther: Boolean): InstallResult {
        val hash = sha256(bytes)
        val isOfficial = official.values.any { it.sha256 == hash }
        if (!isOfficial && !allowOther) return InstallResult.NotOfficial
        val dictionary = try {
            Dictionary(bytes)
        } catch (e: Exception) {
            return InstallResult.Invalid
        }
        val tag = dictionary.locale()
        if (!isTag(tag)) return InstallResult.Invalid
        val (name, layout) = dictionary.info()
        val language = Language(tag, name.take(MAX_NAME), layout.take(MAX_NAME), builtIn = false)
        synchronized(this) {
            try {
                packs.mkdirs()
                writeAtomically(File(packs, "$tag$SUFFIX"), bytes)
                writeIndex(readIndex().filter { it.tag != tag } + language)
            } catch (e: java.io.IOException) {
                return InstallResult.Failed
            }
            // The old dictionary may still be in use: it is freed when unused.
            cache[tag] = dictionary
        }
        return InstallResult.Installed(language)
    }

    /** Removes an opened language file. Built-in languages stay. */
    @Synchronized
    fun remove(tag: String) {
        if (tag in builtIn || !isTag(tag)) return
        File(packs, "$tag$SUFFIX").delete()
        try {
            writeIndex(readIndex().filter { it.tag != tag })
        } catch (e: java.io.IOException) {
            Log.w(TAG, "language index not updated: ${e.javaClass.simpleName}")
        }
        cache.remove(tag)
    }

    private fun readIndex(): List<Language> {
        val file = File(packs, INDEX)
        if (!file.isFile) return emptyList()
        return try {
            AtomicFile(file).readFully().toString(Charsets.UTF_8).lines().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size == 3 && isTag(f[0]) && File(packs, "${f[0]}$SUFFIX").isFile) Language(f[0], f[1], f[2], false) else null
            }
        } catch (e: java.io.IOException) {
            emptyList()
        }
    }

    private fun writeIndex(languages: List<Language>) {
        val text = languages.joinToString("") { "${it.tag}\t${clean(it.name)}\t${clean(it.layout)}\n" }
        writeAtomically(File(packs, INDEX), text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val FALLBACK = "en-US"
        const val DEFAULT_LAYOUT = "letters"
        /** The keyboard-public release page, where the language files are. */
        const val DOWNLOAD_PAGE = "https://github.com/pingolindev/keyboard-public/releases"
        private const val TAG = "PingolinKeyboard"
        private const val BUILT_IN = "dictionaries"
        private const val OFFICIAL = "languages/official.tsv"
        private const val SUFFIX = ".pkd"
        private const val INDEX = "index.tsv"
        private const val MAX_NAME = 64

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Languages? = null

        fun get(context: Context): Languages =
            instance ?: synchronized(this) { instance ?: Languages(context).also { instance = it } }

        /** A BCP 47 tag as the engine writes it: letters, digits and hyphens. */
        fun isTag(tag: String): Boolean = tag.length in 2..35 && tag.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

        fun writeAtomically(file: File, bytes: ByteArray) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (e: java.io.IOException) {
                atomic.failWrite(out)
                throw e
            }
        }
    }
}
