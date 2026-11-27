// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.pingolin.keyboard.engine.FieldKind

/**
 * What a text field allows, read from its EditorInfo values. Pure
 * functions on the raw ints, so they run in plain JVM tests.
 */
object FieldPolicy {
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** The app asks for incognito here (a private browser tab, for one). */
    fun asksIncognito(imeOptions: Int): Boolean = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /** The app takes only ASCII here, as the lock screen's password does. */
    fun takesOnlyAscii(imeOptions: Int): Boolean = (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) != 0

    /**
     * Whether the keyboard may learn from what is typed here, and use what it
     * learned. Never in passwords; not where the app asks for incognito, unless
     * the user turned off [autoIncognito].
     */
    fun mayLearn(inputType: Int, imeOptions: Int, autoIncognito: Boolean = true): Boolean =
        !isPassword(inputType) && !(autoIncognito && asksIncognito(imeOptions))

    /** The app asks for no suggestions here. */
    private fun asksNoSuggestions(inputType: Int): Boolean =
        (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

    private fun isText(inputType: Int): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

    private fun isWebAddress(inputType: Int): Boolean =
        isText(inputType) && (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_URI

    /** E-mail addresses: typed as they are, without suggestions. */
    private val EMAIL_ADDRESSES = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )

    /** Prose, where autocorrect helps: not names, addresses, codes or search filters. */
    private val PROSE = setOf(
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
    )

    /**
     * Whether words are typed with the suggestion strip and glide typing here:
     * text fields other than passwords and e-mail addresses. That includes web
     * addresses and fields that ask for no suggestions. Chrome asks that of its
     * address bar, where searches are typed, and of every web field that turns
     * autofill off. There words are offered, but not corrected or learned.
     */
    fun mayCompose(inputType: Int): Boolean =
        isText(inputType) &&
            !isPassword(inputType) &&
            (inputType and InputType.TYPE_MASK_VARIATION) !in EMAIL_ADDRESSES

    /** Whether words typed here may be learned: not in web addresses or where the app asks for no suggestions. */
    fun mayLearnWords(inputType: Int): Boolean = !asksNoSuggestions(inputType) && !isWebAddress(inputType)

    /**
     * Whether a space may replace a typed word with a correction here. Prose
     * fields, and fields that ask for it with TYPE_TEXT_FLAG_AUTO_CORRECT, as
     * browsers do unless the page turns autocorrect off. Never in web addresses
     * or where the app asks for no suggestions.
     */
    fun mayAutocorrect(inputType: Int): Boolean {
        if (!mayCompose(inputType) || asksNoSuggestions(inputType) || isWebAddress(inputType)) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation in PROSE || (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0
    }

    /**
     * Whether a space follows punctuation typed after a word: where autocorrect
     * may work, and in multi-line web editors that ask for capitals at sentence
     * starts. Editors built on CodeMirror turn autocorrect off unless the page
     * turns it back on; one used for notes still asks for sentence capitals,
     * while one used for code does not.
     */
    fun mayAutoSpace(inputType: Int): Boolean {
        if (mayAutocorrect(inputType)) return true
        if (!mayCompose(inputType) || asksNoSuggestions(inputType)) return false
        return (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT &&
            (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 &&
            (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
    }

    /** Fields that take raw key presses rather than text, as terminals do. */
    fun takesRawKeys(inputType: Int): Boolean = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL

    /** Numbers, phone numbers, dates and times get a number pad; the rest, the letters. */
    fun fieldKind(inputType: Int): FieldKind = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
        InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
        InputType.TYPE_CLASS_DATETIME -> FieldKind.DATE_TIME
        else -> FieldKind.TEXT
    }

    /**
     * The editor action Enter should run (Go, Send, Next...), or null when Enter
     * should go to the field as a key press (a new line in multi-line fields).
     */
    fun enterAction(imeOptions: Int): Int? {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return null
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> null
            else -> action
        }
    }
}
