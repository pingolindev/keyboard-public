// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/** What a file the user opens with the keyboard is, from its first bytes. */
enum class KeyboardFileKind { LANGUAGE, VOICE, UNKNOWN }

/**
 * Adds a language file or voice model the user opened, from the setup screen
 * or with Open in the browser or a file manager.
 */
object KeyboardFiles {
    private val LANGUAGE_MAGIC = "PKBDICT\u0000".toByteArray(Charsets.ISO_8859_1)

    /** whisper.cpp's model files start with ggml's magic, 0x67676d6c, little-endian. */
    private val VOICE_MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)

    private const val MAX_LANGUAGE_FILE = 64 * 1024 * 1024

    /** Where a language file for this version of the app is published. */
    private const val RELEASES = "https://github.com/pingolindev/keyboard-public/releases/download"

    fun kindOf(header: ByteArray): KeyboardFileKind = when {
        header.startsWith(LANGUAGE_MAGIC) -> KeyboardFileKind.LANGUAGE
        header.startsWith(VOICE_MAGIC) -> KeyboardFileKind.VOICE
        else -> KeyboardFileKind.UNKNOWN
    }

    /**
     * The download link of language [tag] in the release of [versionName],
     * whose files are the official ones for this build ("0.1.0-debug" looks
     * in v0.1.0).
     */
    fun languageUrl(versionName: String, tag: String): String =
        "$RELEASES/v${versionName.substringBefore('-')}/$tag.pkd"

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Adds the file at [uri]; the message to show. Slow: not on the main thread. */
    fun add(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val header = read(resolver, uri, LANGUAGE_MAGIC.size) ?: return context.getString(R.string.file_unreadable)
        return when (kindOf(header)) {
            KeyboardFileKind.LANGUAGE -> addLanguage(context, uri)
            KeyboardFileKind.VOICE -> addVoiceModel(context, uri)
            KeyboardFileKind.UNKNOWN -> context.getString(R.string.file_not_keyboard)
        }
    }

    private fun addLanguage(context: Context, uri: Uri): String {
        val bytes = read(context.contentResolver, uri, MAX_LANGUAGE_FILE, whole = true)
            ?: return context.getString(R.string.file_unreadable)
        val settings = KeyboardSettings(context)
        return when (val result = Languages.get(context).install(bytes, settings.allowOtherLanguageFiles)) {
            is InstallResult.Installed -> {
                settings.languages = (settings.languages + result.language.tag).distinct()
                context.getString(R.string.languages_added, result.language.name)
            }
            InstallResult.NotOfficial -> context.getString(R.string.languages_not_official)
            InstallResult.Invalid -> context.getString(R.string.languages_invalid)
            InstallResult.Failed -> context.getString(R.string.file_unsaved)
        }
    }

    private fun addVoiceModel(context: Context, uri: Uri): String {
        val result = open(context.contentResolver, uri)?.use { VoiceModels.get(context).install(it) }
        return when (result) {
            null, VoiceModels.Result.Failed -> context.getString(R.string.file_unreadable)
            VoiceModels.Result.NotOfficial -> context.getString(R.string.voice_not_official)
            is VoiceModels.Result.Installed -> context.getString(R.string.voice_added, result.model.name)
        }
    }

    private fun open(resolver: ContentResolver, uri: Uri): InputStream? = try {
        resolver.openInputStream(uri)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    /**
     * Up to [limit] bytes of the file. With [whole], null when the file is
     * longer, so an oversized file is refused rather than cut.
     */
    private fun read(resolver: ContentResolver, uri: Uri, limit: Int, whole: Boolean = false): ByteArray? = try {
        open(resolver, uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit || whole) {
                val n = input.read(buffer, 0, if (whole) buffer.size else minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
                if (whole && out.size() > limit) return@use null
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        null
    }
}
