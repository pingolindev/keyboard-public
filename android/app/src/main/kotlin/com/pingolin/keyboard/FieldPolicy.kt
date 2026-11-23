// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

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

    /**
     * Whether the keyboard may learn from what is typed here, and use what it
     * learned. Never in passwords; not where the app asks for incognito, unless
     * the user turned off [autoIncognito].
     */
    fun mayLearn(inputType: Int, imeOptions: Int, autoIncognito: Boolean = true): Boolean =
        !isPassword(inputType) && !(autoIncognito && asksIncognito(imeOptions))

    /** Whether suggestions may be shown here. */
    fun maySuggest(inputType: Int): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            !isPassword(inputType) &&
            (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0

    /** Web and e-mail addresses: typed as they are, without suggestions. */
    private val ADDRESSES = setOf(
        InputType.TYPE_TEXT_VARIATION_URI,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )

    /** Prose, where autocorrect helps: not names, addresses, codes or search filters. */
    private val PROSE = setOf(
        InputType.TYPE_TEXT_VARIATION_NORMAL,
        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
    )

    /** Whether words are typed with the suggestion strip here. */
    fun mayCompose(inputType: Int): Boolean =
        maySuggest(inputType) && (inputType and InputType.TYPE_MASK_VARIATION) !in ADDRESSES

    /**
     * Whether a space may replace a typed word with a correction here. Prose
     * fields, and fields that ask for it with TYPE_TEXT_FLAG_AUTO_CORRECT unless
     * they hold an address.
     */
    fun mayAutocorrect(inputType: Int): Boolean {
        if (!mayCompose(inputType)) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation in PROSE || (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0
    }

    /** Fields that take raw key presses rather than text, as terminals do. */
    fun takesRawKeys(inputType: Int): Boolean = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL

    /** Numbers, phone numbers, dates and times: the field starts on the symbols layout. */
    fun isNumeric(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
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
