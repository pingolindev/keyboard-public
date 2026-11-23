// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A voice model the keyboard knows, from assets/voice/official.tsv. */
data class VoiceModelFile(val file: String, val sha256: String, val bytes: Long, val allLanguages: Boolean, val name: String)

/**
 * The voice model on the phone, one at a time, in credential-encrypted
 * storage. Only official models are accepted: whisper's loader is not fuzzed
 * like the engine's parsers.
 */
class VoiceModels private constructor(context: Context) {
    private val app = context.applicationContext
    private val dir: File get() = File(app.filesDir, DIR)

    val official: List<VoiceModelFile> by lazy { read(app.assets.open(OFFICIAL).bufferedReader().readText()) }

    /** The installed model and its file, or null. */
    fun installed(): Pair<VoiceModelFile, File>? =
        official.firstNotNullOfOrNull { model -> File(dir, model.file).takeIf { it.isFile }?.let { model to it } }

    sealed interface Result {
        data class Installed(val model: VoiceModelFile) : Result

        data object NotOfficial : Result

        data object Failed : Result
    }

    /**
     * Copies a model from [input] while hashing it, and keeps it only if it
     * is an official one. Replaces the model installed before.
     */
    @Synchronized
    fun install(input: InputStream): Result {
        val largest = official.maxOf { it.bytes }
        val temp = File(dir, "$DIR.part")
        return try {
            dir.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > largest) return Result.NotOfficial
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val model = official.firstOrNull { it.sha256 == hash && it.bytes == total } ?: return Result.NotOfficial
            dir.listFiles().orEmpty().filter { it.name != temp.name }.forEach { it.delete() }
            if (!temp.renameTo(File(dir, model.file))) return Result.Failed
            Result.Installed(model)
        } catch (e: IOException) {
            Result.Failed
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun remove() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    companion object {
        private const val DIR = "voice"
        private const val OFFICIAL = "voice/official.tsv"

        /** Where to download the official models, in the browser. */
        const val DOWNLOAD_PAGE = "https://huggingface.co/ggerganov/whisper.cpp/tree/main"

        @Volatile private var instance: VoiceModels? = null

        fun get(context: Context): VoiceModels =
            instance ?: synchronized(this) { instance ?: VoiceModels(context).also { instance = it } }

        /** Reads the official list: file, sha256, bytes, "en" or "all", name. */
        fun read(tsv: String): List<VoiceModelFile> = tsv.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 5 || f[1].length != 64) return@mapNotNull null
                VoiceModelFile(f[0], f[1], f[2].toLongOrNull() ?: return@mapNotNull null, f[3] == "all", f[4])
            }

        /** The whisper language code for a keyboard language tag ("pt-BR" is "pt", Norwegian is "no"). */
        fun whisperLanguage(tag: String): String = when (val language = tag.substringBefore('-').lowercase()) {
            "nb", "nn" -> "no"
            else -> language
        }
    }
}
