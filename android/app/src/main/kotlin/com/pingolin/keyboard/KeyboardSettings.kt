// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * The user's settings, in device-protected storage so the keyboard can read
 * them at the lock screen. They hold no typed text.
 */
class KeyboardSettings(context: Context) {
    private val prefs: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var suggestions: Boolean
        get() = prefs.getBoolean(SUGGESTIONS, true)
        set(value) = prefs.edit().putBoolean(SUGGESTIONS, value).apply()

    var autocorrect: Boolean
        get() = prefs.getBoolean(AUTOCORRECT, true)
        set(value) = prefs.edit().putBoolean(AUTOCORRECT, value).apply()

    /** A space follows punctuation typed right after a word, and a picked word. */
    var autoSpace: Boolean
        get() = prefs.getBoolean(AUTO_SPACE, true)
        set(value) = prefs.edit().putBoolean(AUTO_SPACE, value).apply()

    /** Glide typing: sliding over the letters types a word. */
    var glideTyping: Boolean
        get() = prefs.getBoolean(GLIDE, true)
        set(value) = prefs.edit().putBoolean(GLIDE, value).apply()

    /** Learn the words the user types, on the phone. */
    var learnWords: Boolean
        get() = prefs.getBoolean(LEARN_WORDS, true)
        set(value) = prefs.edit().putBoolean(LEARN_WORDS, value).apply()

    /** The languages to type in, in the order a long press on Space cycles them. */
    var languages: List<String>
        get() = prefs.getString(LANGUAGES, null)
            ?.split(',')?.filter { Languages.isTag(it) }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: listOf(Languages.FALLBACK)
        set(value) = prefs.edit().putString(LANGUAGES, value.filter { Languages.isTag(it) }.distinct().joinToString(",")).apply()

    /** The language typed in now. */
    var language: String
        get() = prefs.getString(LANGUAGE, null)?.takeIf { Languages.isTag(it) } ?: languages.first()
        set(value) = prefs.edit().putString(LANGUAGE, value).apply()

    /** An app that asks for it (a private tab) turns on incognito there. */
    var autoIncognito: Boolean
        get() = prefs.getBoolean(AUTO_INCOGNITO, true)
        set(value) = prefs.edit().putBoolean(AUTO_INCOGNITO, value).apply()

    /** Incognito, switched on in the keyboard: nothing learned, learned words not used. */
    var incognito: Boolean
        get() = prefs.getBoolean(INCOGNITO, false)
        set(value) = prefs.edit().putBoolean(INCOGNITO, value).apply()

    /** The colors: one of [KeyboardTheme.IDS]. */
    var theme: String
        get() = prefs.getString(THEME, null)?.takeIf(KeyboardTheme::isId) ?: KeyboardTheme.SYSTEM
        set(value) = prefs.edit().putString(THEME, value.takeIf(KeyboardTheme::isId) ?: KeyboardTheme.SYSTEM).apply()

    /** The keyboard's height, as a percentage of the usual one. */
    var heightPercent: Int
        get() = prefs.getInt(HEIGHT, 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        set(value) = prefs.edit().putInt(HEIGHT, value.coerceIn(MIN_HEIGHT, MAX_HEIGHT)).apply()

    /**
     * Keep the clipboard history across restarts, and past an hour. Pinned
     * clips are kept either way.
     */
    var keepClipboard: Boolean
        get() = prefs.getBoolean(KEEP_CLIPBOARD, false)
        set(value) = prefs.edit().putBoolean(KEEP_CLIPBOARD, value).apply()

    /** The terminal rows (Esc, Tab, Ctrl, Alt, arrows) above the keys in [app]: only where the user turned them on. */
    fun terminalKeys(app: String?): Boolean = app != null && app in terminalApps()

    fun setTerminalKeys(app: String, on: Boolean) {
        val apps = terminalApps().toMutableSet()
        if (on) apps += app else apps -= app
        // Until 0.1.2 one switch showed the rows in every app.
        prefs.edit().putStringSet(TERMINAL_APPS, apps).remove(OLD_TERMINAL_KEYS).apply()
    }

    private fun terminalApps(): Set<String> = prefs.getStringSet(TERMINAL_APPS, null).orEmpty()

    /** Accept language files that are not official ones. */
    var allowOtherLanguageFiles: Boolean
        get() = prefs.getBoolean(ALLOW_OTHER, false)
        set(value) = prefs.edit().putBoolean(ALLOW_OTHER, value).apply()

    /** Calls [listener] when a setting changes; keep the listener referenced. */
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    /** What an export holds of these settings. */
    fun snapshot() = SettingsSnapshot(suggestions, autocorrect, learnWords, languages, language, theme, heightPercent, autoIncognito, autoSpace)

    fun restore(s: SettingsSnapshot) {
        suggestions = s.suggestions
        autocorrect = s.autocorrect
        learnWords = s.learnWords
        if (s.languages.isNotEmpty()) languages = s.languages
        if (Languages.isTag(s.language)) language = s.language
        theme = s.theme
        heightPercent = s.heightPercent
        autoIncognito = s.autoIncognito
        autoSpace = s.autoSpace
    }

    companion object {
        const val MIN_HEIGHT = 80
        const val MAX_HEIGHT = 130
        const val THEME = "theme"
        const val HEIGHT = "height_percent"
        const val AUTO_INCOGNITO = "auto_incognito"
        const val AUTO_SPACE = "auto_space"
        private const val FILE = "settings"
        private const val SUGGESTIONS = "suggestions"
        private const val AUTOCORRECT = "autocorrect"
        private const val LEARN_WORDS = "learn_words"
        private const val LANGUAGES = "languages"
        private const val LANGUAGE = "language"
        private const val ALLOW_OTHER = "allow_other_language_files"
        private const val INCOGNITO = "incognito"
        private const val OLD_TERMINAL_KEYS = "terminal_keys"
        private const val TERMINAL_APPS = "terminal_apps"
        private const val KEEP_CLIPBOARD = "keep_clipboard"
        private const val GLIDE = "glide_typing"
    }
}

data class SettingsSnapshot(
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val learnWords: Boolean,
    val languages: List<String>,
    val language: String,
    val theme: String = KeyboardTheme.SYSTEM,
    val heightPercent: Int = 100,
    val autoIncognito: Boolean = true,
    val autoSpace: Boolean = true,
)
