// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionStripTest {
    private fun result(autocorrect: String?, vararg candidates: String, typed: String = "teh") =
        WordSuggestions(typed, typedIsWord = false, autocorrect = autocorrect, candidates = candidates.toList())

    @Test
    fun anAutocorrectionGoesInTheMiddleAndTheTypedWordLeft() {
        val strip = Strip.arrange("teh", result("the", "the", "tea", "ten"))
        assertEquals(Slot("teh", Slot.Role.TYPED), strip.left)
        assertEquals(Slot("the", Slot.Role.AUTOCORRECT), strip.middle)
        assertEquals(Slot("tea", Slot.Role.SUGGESTION), strip.right)
    }

    @Test
    fun withoutAnAutocorrectionTheTypedWordStaysInTheMiddle() {
        val strip = Strip.arrange("hel", result(null, "help", "hello", "held", typed = "hel"))
        assertEquals(Slot("help", Slot.Role.SUGGESTION), strip.left)
        assertEquals(Slot("hel", Slot.Role.CURRENT), strip.middle)
        assertEquals(Slot("hello", Slot.Role.SUGGESTION), strip.right)
    }

    @Test
    fun repeatsAndTheTypedWordAreNotShownTwice() {
        val strip = Strip.arrange("teh", result("the", "the", "teh", "the", "tea"))
        assertEquals(listOf("teh", "the", "tea"), strip.slots.map { it?.text })
    }

    @Test
    fun anAutocorrectionEqualToTheTypedWordIsNone() {
        val strip = Strip.arrange("the", result("the", "then", typed = "the"))
        assertEquals(Slot("the", Slot.Role.CURRENT), strip.middle)
    }

    @Test
    fun anAutocorrectionThatWillNotBeAppliedIsOnlyASuggestion() {
        val strip = Strip.arrange("teh", result("the", "the", "tea"), applyAutocorrect = false)
        assertEquals(Slot("teh", Slot.Role.CURRENT), strip.middle)
        assertEquals(listOf("the", "teh", "tea"), strip.slots.map { it?.text })
    }

    @Test
    fun predictionsPutTheLikeliestInTheMiddle() {
        val strip = Strip.predictions(listOf("you", "there", "all", "again"))
        assertEquals(listOf("there", "you", "all"), strip.slots.map { it?.text })
        assertEquals(Slot.Role.PREDICTION, strip.middle?.role)
        assertEquals(listOf(null, "you", null), Strip.predictions(listOf("you")).slots.map { it?.text })
        assertEquals(Strip.EMPTY, Strip.predictions(emptyList()))
    }

    @Test
    fun anEmojiForTheMiddleWordTakesTheRightPlace() {
        val emoji = { word: String -> if (word == "pizza") "🍕" else null }
        val typed = Strip.arrange("pizza", result(null, "pizzas", "pizzeria", typed = "pizza"), emojiFor = emoji)
        assertEquals(
            listOf(Slot("pizzas", Slot.Role.SUGGESTION), Slot("pizza", Slot.Role.CURRENT), Slot("🍕", Slot.Role.EMOJI)),
            typed.slots,
        )
        // It goes with the word a space would put there.
        val corrected = Strip.arrange("piza", result("pizza", "pizza", "plaza", typed = "piza"), emojiFor = emoji)
        assertEquals(
            listOf(Slot("piza", Slot.Role.TYPED), Slot("pizza", Slot.Role.AUTOCORRECT), Slot("🍕", Slot.Role.EMOJI)),
            corrected.slots,
        )
        // Not with a word that is only offered.
        val offered = Strip.arrange("piz", result(null, "pizza", "pizzas", typed = "piz"), emojiFor = emoji)
        assertEquals(listOf("pizza", "piz", "pizzas"), offered.slots.map { it?.text })
        // Alone on its side.
        assertEquals(
            listOf(null, "pizza", "🍕"),
            Strip.arrange("pizza", result(null, typed = "pizza"), emojiFor = emoji).slots.map { it?.text },
        )
    }

    @Test
    fun nothingToShow() {
        assertEquals(Strip.EMPTY, Strip.arrange("", result("the")))
        assertEquals(Strip.EMPTY, Strip.arrange("teh", null))
        val strip = Strip.arrange("xq", result(null, typed = "xq"))
        assertEquals(listOf(null, "xq", null), strip.slots.map { it?.text })
    }
}
