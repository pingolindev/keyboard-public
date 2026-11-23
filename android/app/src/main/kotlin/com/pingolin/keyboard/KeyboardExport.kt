// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The export file, as JSON: settings, learned words and pinned clips, each
 * optional.
 *
 * ```json
 * {"format": "pingolin-keyboard-export", "version": 1,
 *  "settings": {"suggestions": true, "autocorrect": true, "learn_words": true,
 *               "languages": ["en-US", "de"], "language": "en-US",
 *               "theme": "system", "height": 100, "auto_incognito": true},
 *  "words": {"format": "pingolin-keyboard-words", "version": 1, "words": [], "pairs": []},
 *  "clipboard": {"format": "pingolin-keyboard-clipboard", "version": 1, "clips": []}}
 * ```
 *
 * `words` is the engine's format (core/engine/src/user.rs) and `clipboard` is
 * ClipboardHistory's. Both are checked again on import.
 */
object KeyboardExport {
    const val FORMAT = "pingolin-keyboard-export"
    const val VERSION = 1
    /** Largest file accepted. */
    const val MAX_BYTES = 16 * 1024 * 1024

    data class Contents(val settings: SettingsSnapshot?, val wordsJson: String?, val clipboardJson: String? = null)

    fun write(contents: Contents): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        contents.settings?.let { s ->
            root.put(
                "settings",
                JSONObject()
                    .put("suggestions", s.suggestions)
                    .put("autocorrect", s.autocorrect)
                    .put("learn_words", s.learnWords)
                    .put("languages", JSONArray(s.languages))
                    .put("language", s.language)
                    .put("theme", s.theme)
                    .put("height", s.heightPercent)
                    .put("auto_incognito", s.autoIncognito),
            )
        }
        contents.wordsJson?.let { root.put("words", JSONObject(it)) }
        contents.clipboardJson?.let { root.put("clipboard", JSONObject(it)) }
        return root.toString()
    }

    /** Reads an export, or null if the text is not one. Unknown keys are ignored. */
    fun read(text: String): Contents? {
        if (text.length > MAX_BYTES || nesting(text) > MAX_NESTING) return null
        return try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT || root.optInt("version") != VERSION) return null
            val settings = root.optJSONObject("settings")?.let { s ->
                val languages = s.optJSONArray("languages")?.let { a ->
                    (0 until minOf(a.length(), MAX_LANGUAGES)).mapNotNull { a.optString(it).takeIf(Languages::isTag) }
                }.orEmpty()
                SettingsSnapshot(
                    suggestions = s.optBoolean("suggestions", true),
                    autocorrect = s.optBoolean("autocorrect", true),
                    learnWords = s.optBoolean("learn_words", true),
                    languages = languages,
                    language = s.optString("language").takeIf(Languages::isTag) ?: languages.firstOrNull() ?: "",
                    theme = s.optString("theme").takeIf(KeyboardTheme::isId) ?: KeyboardTheme.SYSTEM,
                    heightPercent = s.optInt("height", 100).coerceIn(KeyboardSettings.MIN_HEIGHT, KeyboardSettings.MAX_HEIGHT),
                    autoIncognito = s.optBoolean("auto_incognito", true),
                )
            }
            val words = root.optJSONObject("words")?.toString()
            val clips = root.optJSONObject("clipboard")?.toString()
            if (settings == null && words == null && clips == null) null else Contents(settings, words, clips)
        } catch (e: JSONException) {
            null
        }
    }

    private const val MAX_LANGUAGES = 64

    /** Deeper JSON is refused before parsing: Android's org.json recurses without a limit. */
    private const val MAX_NESTING = 32

    /** How deeply [text] nests objects and arrays, outside strings. */
    fun nesting(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> deepest = maxOf(deepest, ++depth)
                c == '}' || c == ']' -> depth--
            }
        }
        return deepest
    }
}
