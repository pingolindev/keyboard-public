// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChoiceTest {
    private val english = Language("en-US", "English (US)", "letters", builtIn = true)
    private val german = Language("de", "Deutsch", "qwertz", builtIn = false)
    private val russian = Language("ru", "Русский", "russian", builtIn = false)
    private val greek = Language("el", "Ελληνικά", "greek", builtIn = false)

    private fun order(enabled: List<Language>, current: String, asciiOnly: Boolean = false, english: Language? = this.english) =
        LanguageChoice.order(enabled, english, current, asciiOnly) { it.layout !in setOf("russian", "greek") }

    @Test
    fun theCurrentLanguageComesFirst() {
        assertEquals(listOf(german, english), order(listOf(english, german), "de"))
        assertEquals(listOf(english, german), order(listOf(english, german), "en-US"))
        // One that is not enabled any more.
        assertEquals(listOf(english, german), order(listOf(english, german), "fr"))
        assertEquals(listOf(german), order(listOf(german), "de"))
    }

    @Test
    fun englishComesAlongWhenNoLanguageHasLatinLetters() {
        assertEquals(listOf(russian, english), order(listOf(russian), "ru"))
        assertEquals(listOf(greek, russian, english), order(listOf(russian, greek), "el"))
        // Holding Space switched to it: it stays.
        assertEquals(listOf(english, russian), order(listOf(russian), "en-US"))
        // Not where another one has them.
        assertEquals(listOf(russian, german), order(listOf(russian, german), "ru"))
    }

    @Test
    fun fieldsThatTakeOnlyAsciiStartWithLatinLetters() {
        assertEquals(listOf(english, russian), order(listOf(russian), "ru", asciiOnly = true))
        assertEquals(listOf(german, russian), order(listOf(russian, german), "ru", asciiOnly = true))
        assertEquals(listOf(english, russian), order(listOf(russian, english), "ru", asciiOnly = true))
        // The current one, if it has them.
        assertEquals(listOf(german, english), order(listOf(english, german), "de", asciiOnly = true))
    }

    @Test
    fun nothingToChooseFrom() {
        assertEquals(listOf(english), order(emptyList(), "ru"))
        assertEquals(emptyList<Language>(), order(emptyList(), "ru", english = null))
        assertEquals(listOf(russian), order(listOf(russian), "ru", asciiOnly = true, english = null))
    }
}
