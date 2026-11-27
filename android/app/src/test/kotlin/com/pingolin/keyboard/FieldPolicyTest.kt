// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.pingolin.keyboard.engine.FieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val number = InputType.TYPE_CLASS_NUMBER

    // What Firefox 161 and Chrome send on Android 16.
    private val firefoxAddressBar = 0x11
    private val chromeAddressBar = 0x80011
    private val chromeField = 0xc0a1
    private val chromeFieldAutocompleteOff = 0x8c0a1
    private val chromeFieldAutocorrectOff = 0x40a1
    private val chromeEmail = 0xd1

    // A CodeMirror editor in a web view: as notes take it (autocapitalize="sentences"), and as code takes it.
    private val notesEditor = 0x240a1
    private val codeEditor = 0x200a1

    @Test
    fun fieldsThatTakeOnlyAsciiSaySo() {
        assertTrue(FieldPolicy.takesOnlyAscii(EditorInfo.IME_FLAG_FORCE_ASCII or EditorInfo.IME_ACTION_DONE))
        assertFalse(FieldPolicy.takesOnlyAscii(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun everyPasswordVariationIsAPassword() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            assertTrue(variation.toString(), FieldPolicy.isPassword(text or variation))
        }
        assertTrue(FieldPolicy.isPassword(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun ordinaryFieldsAreNotPasswords() {
        assertFalse(FieldPolicy.isPassword(text))
        assertFalse(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldPolicy.isPassword(number))
        // The number-password bit means "URI" on a text field.
        assertFalse(FieldPolicy.isPassword(text or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun noLearningInPasswordsOrWhenTheAppAsksForNone() {
        assertTrue(FieldPolicy.mayLearn(text, 0))
        assertFalse(FieldPolicy.mayLearn(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertFalse(FieldPolicy.mayLearn(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertFalse(FieldPolicy.mayLearn(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        // The user turned automatic incognito off: the app's request is ignored, passwords are not.
        assertTrue(FieldPolicy.mayLearn(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, autoIncognito = false))
        assertFalse(FieldPolicy.mayLearn(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, autoIncognito = false))
    }

    @Test
    fun wordsAndGlideInEveryTextFieldButPasswordsAndEmailAddresses() {
        assertTrue(FieldPolicy.mayCompose(text))
        assertTrue(FieldPolicy.mayCompose(chromeField))
        assertTrue(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        // Address bars, where searches are typed, and fields that ask for no suggestions.
        assertTrue(FieldPolicy.mayCompose(firefoxAddressBar))
        assertTrue(FieldPolicy.mayCompose(chromeAddressBar))
        assertTrue(FieldPolicy.mayCompose(chromeFieldAutocompleteOff))
        assertTrue(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldPolicy.mayCompose(chromeEmail))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        // Editors that turn suggestions off with a visible password keep them off.
        assertFalse(
            FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
        )
        assertFalse(FieldPolicy.mayCompose(number))
        assertFalse(FieldPolicy.mayCompose(InputType.TYPE_NULL))
    }

    @Test
    fun noWordsLearnedInWebAddressesOrWhereTheAppAsksForNoSuggestions() {
        assertTrue(FieldPolicy.mayLearnWords(text))
        assertTrue(FieldPolicy.mayLearnWords(chromeField))
        assertFalse(FieldPolicy.mayLearnWords(firefoxAddressBar))
        assertFalse(FieldPolicy.mayLearnWords(chromeAddressBar))
        assertFalse(FieldPolicy.mayLearnWords(chromeFieldAutocompleteOff))
        assertFalse(FieldPolicy.mayLearnWords(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun autocorrectOnlyInProseOrWhenTheAppAsks() {
        assertTrue(FieldPolicy.mayAutocorrect(text))
        assertTrue(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
        // Names, postal addresses and search filters keep what was typed...
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_FILTER))
        // ...unless the app asks for autocorrect.
        assertTrue(
            FieldPolicy.mayAutocorrect(
                text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
            ),
        )
        // Web fields follow the page: browsers ask for it unless the page turns it off.
        assertTrue(FieldPolicy.mayAutocorrect(chromeField))
        assertFalse(FieldPolicy.mayAutocorrect(chromeFieldAutocorrectOff))
        // Never in addresses, passwords or fields without suggestions.
        assertFalse(FieldPolicy.mayAutocorrect(firefoxAddressBar))
        assertFalse(FieldPolicy.mayAutocorrect(chromeAddressBar))
        assertFalse(FieldPolicy.mayAutocorrect(chromeFieldAutocompleteOff))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.mayAutocorrect(number))
    }

    @Test
    fun aSpaceFollowsPunctuationInProseEvenWithoutAutocorrect() {
        assertTrue(FieldPolicy.mayAutoSpace(text))
        assertTrue(FieldPolicy.mayAutoSpace(chromeField))
        // A notes editor that turned autocorrect off still writes sentences.
        assertFalse(FieldPolicy.mayAutocorrect(notesEditor))
        assertTrue(FieldPolicy.mayAutoSpace(notesEditor))
        // Code, a one-line field with autocorrect off, addresses, and fields without suggestions do not.
        assertFalse(FieldPolicy.mayAutoSpace(codeEditor))
        assertFalse(FieldPolicy.mayAutoSpace(chromeFieldAutocorrectOff))
        assertFalse(FieldPolicy.mayAutoSpace(firefoxAddressBar))
        assertFalse(FieldPolicy.mayAutoSpace(chromeAddressBar))
        assertFalse(FieldPolicy.mayAutoSpace(chromeEmail))
        assertFalse(FieldPolicy.mayAutoSpace(notesEditor or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.mayAutoSpace(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }

    @Test
    fun numbersPhonesAndDatesAreNumeric() {
        assertEquals(FieldKind.NUMBER, FieldPolicy.fieldKind(number))
        assertEquals(FieldKind.NUMBER, FieldPolicy.fieldKind(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals(FieldKind.PHONE, FieldPolicy.fieldKind(InputType.TYPE_CLASS_PHONE))
        assertEquals(FieldKind.DATE_TIME, FieldPolicy.fieldKind(InputType.TYPE_CLASS_DATETIME))
        assertEquals(FieldKind.TEXT, FieldPolicy.fieldKind(text))
    }

    @Test
    fun enterRunsTheFieldsActionUnlessTheFieldSaysOtherwise() {
        assertEquals(EditorInfo.IME_ACTION_SEND, FieldPolicy.enterAction(EditorInfo.IME_ACTION_SEND))
        assertEquals(EditorInfo.IME_ACTION_SEARCH, FieldPolicy.enterAction(EditorInfo.IME_ACTION_SEARCH))
        assertNull(FieldPolicy.enterAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(FieldPolicy.enterAction(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertNull(FieldPolicy.enterAction(EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun terminalsTakeRawKeys() {
        assertTrue(FieldPolicy.takesRawKeys(InputType.TYPE_NULL))
        // Termux's other mode: a visible-password variation with no class.
        assertTrue(FieldPolicy.takesRawKeys(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.takesRawKeys(InputType.TYPE_CLASS_TEXT))
        assertFalse(FieldPolicy.takesRawKeys(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun privateTabsAskForIncognito() {
        assertTrue(FieldPolicy.asksIncognito(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO))
        assertFalse(FieldPolicy.asksIncognito(EditorInfo.IME_ACTION_GO))
        assertFalse(FieldPolicy.mayLearn(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }
}
