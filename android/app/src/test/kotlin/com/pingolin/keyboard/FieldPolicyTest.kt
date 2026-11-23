// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val number = InputType.TYPE_CLASS_NUMBER

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
    fun suggestionsOnlyInTextFieldsThatAllowThem() {
        assertTrue(FieldPolicy.maySuggest(text))
        assertFalse(FieldPolicy.maySuggest(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.maySuggest(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(FieldPolicy.maySuggest(number))
    }

    @Test
    fun addressesAreTypedWithoutSuggestions() {
        assertTrue(FieldPolicy.mayCompose(text))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertFalse(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(FieldPolicy.mayCompose(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
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
        // Never in addresses, passwords or fields without suggestions.
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(FieldPolicy.mayAutocorrect(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(FieldPolicy.mayAutocorrect(number))
    }

    @Test
    fun numbersPhonesAndDatesAreNumeric() {
        assertTrue(FieldPolicy.isNumeric(number))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_PHONE))
        assertTrue(FieldPolicy.isNumeric(InputType.TYPE_CLASS_DATETIME))
        assertFalse(FieldPolicy.isNumeric(text))
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
