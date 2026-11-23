// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A text field that behaves like an EditText: composing text, a cursor, and
 * cursor reports that arrive after the edit, as they do on Android.
 */
private class FakeEditor : TextField {
    val text = StringBuilder()
    var cursor = 0
    var composeFrom = -1
    var composeTo = -1
    var logic: InputLogic? = null

    /** Report at once, inside the edit, as an editor in the keyboard's own process does. */
    var immediate = false
    private var batch = 0
    private var changed = false
    private val reports = ArrayDeque<IntArray>()

    override fun setComposingText(text: String) {
        val (from, to) = region()
        this.text.replace(from, to, text)
        if (text.isEmpty()) {
            composeFrom = -1
            composeTo = -1
        } else {
            composeFrom = from
            composeTo = from + text.length
        }
        cursor = from + text.length
        report()
    }

    override fun commitText(text: String) {
        val (from, to) = region()
        this.text.replace(from, to, text)
        composeFrom = -1
        composeTo = -1
        cursor = from + text.length
        report()
    }

    override fun finishComposingText() {
        composeFrom = -1
        composeTo = -1
        report()
    }

    override fun deleteBeforeCursor(count: Int) {
        val from = maxOf(0, cursor - count)
        text.delete(from, cursor)
        cursor = from
        report()
    }

    override fun textBeforeCursor(count: Int): String = text.substring(maxOf(0, cursor - count), cursor)

    override fun textAfterCursor(count: Int): String = text.substring(cursor, minOf(text.length, cursor + count))

    override fun setComposingRegion(start: Int, end: Int) {
        composeFrom = start
        composeTo = end
        report()
    }

    override fun sendBackspace() {
        if (cursor > 0) {
            text.deleteCharAt(cursor - 1)
            cursor--
        }
        report()
    }

    override fun beginBatchEdit() {
        batch++
    }

    override fun endBatchEdit() {
        batch--
        if (batch == 0 && changed) report()
    }

    private fun region(): Pair<Int, Int> = if (composeFrom >= 0) composeFrom to composeTo else cursor to cursor

    private fun report() {
        if (batch > 0) {
            changed = true
            return
        }
        changed = false
        reports.addLast(intArrayOf(cursor, cursor, composeFrom, composeTo))
        if (immediate) deliver()
    }

    /** Hands the queued cursor reports to the logic, as Android does after the edit. */
    fun deliver() {
        while (reports.isNotEmpty()) {
            val r = reports.removeFirst()
            logic?.onSelectionUpdate(r[0], r[1], r[2], r[3])
        }
    }

    /** Holds the reports back, to deliver them late. */
    fun takeReports(): List<IntArray> = reports.toList().also { reports.clear() }

    fun deliver(reports: List<IntArray>) = reports.forEach { logic?.onSelectionUpdate(it[0], it[1], it[2], it[3]) }

    /** The user taps somewhere in the text. */
    fun tap(position: Int) {
        cursor = position
        logic?.onSelectionUpdate(position, position, composeFrom, composeTo)
        deliver()
    }

    override fun toString(): String = text.toString()
}

/** Canned engine answers: a fixed autocorrection per typed word, and pairs. */
private class FakeSuggester : WordSuggester {
    val fixes = mutableMapOf("teh" to "the", "Teh" to "The", "dont" to "don't", "helo" to "hello")
    val words = setOf("the", "then", "they", "hello", "help", "there", "how", "are", "you")
    val next = mapOf("hello" to listOf("there", "you"), "how" to listOf("are"), "are" to listOf("you"))
    val learned = mutableListOf<Pair<String, WordContext>>()
    val kept = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    var lastContext: WordContext? = null
    var lastAutocorrect: Boolean? = null
    val personalAsked = mutableListOf<Boolean>()

    override fun suggest(keys: List<TypedKey>, autocorrect: Boolean, context: WordContext, personal: Boolean): WordSuggestions {
        lastContext = context
        lastAutocorrect = autocorrect
        personalAsked += personal
        val typed = keys.joinToString("") { it.text }
        val fix = fixes[typed]?.takeIf { autocorrect && typed !in rejected && typed !in kept }
        val candidates = listOfNotNull(fixes[typed]) + words.filter { it.startsWith(typed) && it != typed }
        return WordSuggestions(typed, typed in words, fix, candidates)
    }

    /** What a glide gives: set by a test. */
    var glides = listOf("hello", "help", "hell")
    var lastGlideContext: WordContext? = null

    override fun glide(points: List<GlidePoint>, context: WordContext, personal: Boolean): List<String> {
        lastGlideContext = context
        personalAsked += personal
        return glides
    }

    override fun predict(context: WordContext, personal: Boolean): List<String> {
        personalAsked += personal
        return when {
            context.previous != null -> next[context.previous.lowercase()].orEmpty()
            context.sentenceStart -> listOf("I", "The", "How")
            else -> emptyList()
        }
    }

    override fun learn(word: String, context: WordContext) {
        learned += word to context
    }

    override fun keep(word: String) {
        kept += word
    }

    override fun reject(word: String) {
        rejected += word
    }
}

class InputLogicTest {
    private val editor = FakeEditor()
    private val engine = FakeSuggester()
    private var strip = Strip.EMPTY
    private val logic = InputLogic(editor, engine) { strip = it }.also { editor.logic = it }
    private val prose = InputPolicy(compose = true, autocorrect = true, personal = true, learn = true)

    private fun start(policy: InputPolicy = prose, existing: String = "", cursor: Int = existing.length) {
        editor.text.append(existing)
        editor.cursor = cursor
        logic.start(policy, cursor, cursor)
    }

    /** Types [keys]: letters and punctuation go through onText, ' ' is the space bar. */
    private fun type(keys: String, autoShifted: Boolean = false) {
        for ((i, c) in keys.withIndex()) {
            if (c == ' ') logic.onSpace() else logic.onText(c.toString(), 1f, 2f, autoShifted && i == 0)
            editor.deliver()
        }
    }

    private fun backspace() {
        logic.onBackspace()
        editor.deliver()
    }

    private fun pick(slot: Slot?) {
        logic.onPick(slot!!)
        editor.deliver()
    }

    private fun words() = strip.slots.map { it?.text }

    @Test
    fun aWordIsComposedWithSuggestions() {
        start()
        type("teh")
        assertEquals("teh", editor.toString())
        assertEquals(0 to 3, editor.composeFrom to editor.composeTo)
        assertTrue(logic.isComposing)
        assertEquals(listOf("teh", "the", null), words())
        assertEquals(Slot.Role.AUTOCORRECT, strip.middle?.role)
    }

    @Test
    fun spaceAutocorrects() {
        start()
        type("teh ")
        assertEquals("the ", editor.toString())
        assertFalse(logic.isComposing)
    }

    @Test
    fun punctuationAutocorrectsToo() {
        start()
        type("teh.")
        assertEquals("the.", editor.toString())
    }

    @Test
    fun deleteRightAfterAnAutocorrectionPutsBackWhatWasTyped() {
        start()
        type("teh ")
        backspace()
        assertEquals("teh ", editor.toString())
        assertEquals(listOf("teh"), engine.kept)
        // The next Delete goes back into the word.
        backspace()
        assertEquals("teh", editor.toString())
        assertTrue(logic.isComposing)
    }

    @Test
    fun whereLearningIsOffTheUndoIsOnlyRemembered() {
        start(prose.copy(learn = false))
        type("teh ")
        backspace()
        assertEquals("teh ", editor.toString())
        assertTrue(engine.kept.isEmpty())
        assertEquals(listOf("teh"), engine.rejected)
    }

    @Test
    fun incognitoFieldsNeitherLearnNorRemember() {
        start(prose.copy(learn = false, personal = false))
        type("teh ")
        backspace()
        type("then ")
        assertTrue(engine.kept.isEmpty() && engine.rejected.isEmpty() && engine.learned.isEmpty())
        assertTrue("learned words are not used either", engine.personalAsked.isNotEmpty() && engine.personalAsked.none { it })
    }

    @Test
    fun switchingIncognitoOnRedoesTheStripWithoutLearnedWords() {
        start()
        type("teh")
        assertTrue(engine.personalAsked.last())
        logic.updatePolicy(prose.copy(personal = false, learn = false))
        assertFalse(engine.personalAsked.last())
        assertEquals("the", strip.middle?.text)
        type(" ")
        assertTrue(engine.learned.isEmpty())
    }

    @Test
    fun theUndoEndsWithTheNextKey() {
        start()
        type("teh a")
        backspace()
        backspace()
        assertEquals("the", editor.toString())
    }

    @Test
    fun theUndoChecksTheTextIsStillThere() {
        start()
        type("teh ")
        // The app changed the text behind the keyboard's back.
        editor.text.setLength(0)
        editor.text.append("xyz.")
        backspace()
        assertEquals("xyz", editor.toString())
    }

    @Test
    fun aWordInTheDictionaryIsCommittedAsTyped() {
        start()
        type("then ")
        assertEquals("then ", editor.toString())
    }

    @Test
    fun autocorrectOffKeepsTheTypedWordButStillSuggests() {
        start(prose.copy(autocorrect = false))
        type("teh")
        assertEquals(Slot("teh", Slot.Role.CURRENT), strip.middle)
        assertTrue(words().contains("the"))
        type(" ")
        assertEquals("teh ", editor.toString())
    }

    @Test
    fun wordsTheEngineWouldCorrectAreNotLearned() {
        // Even where autocorrect is off, "teh" is a typo of "the": not learned.
        start(prose.copy(autocorrect = false))
        type("teh then ")
        assertEquals(listOf("then"), engine.learned.map { it.first })
    }

    @Test
    fun committedWordsAreLearnedWithTheWordBefore() {
        start()
        type("Teh hello there ", autoShifted = true)
        assertEquals(
            listOf(
                "The" to WordContext(null, true),
                "hello" to WordContext("The", false),
                "there" to WordContext("hello", false),
            ),
            engine.learned,
        )
    }

    @Test
    fun pickingAWordLeavesASpaceForTheNextOne() {
        start()
        type("hel")
        assertEquals(Slot.Role.CURRENT, strip.middle?.role)
        pick(strip.slots.first { it?.text == "hello" })
        assertEquals("hello", editor.toString())
        type("a")
        assertEquals("hello a", editor.toString())
    }

    @Test
    fun punctuationAfterAPickedWordAttaches() {
        start()
        type("hel")
        pick(strip.slots.first { it?.text == "help" })
        type(",")
        assertEquals("help,", editor.toString())
        type(" ")
        assertEquals("help, ", editor.toString())
    }

    @Test
    fun keepingTheTypedWordKeepsIt() {
        start()
        type("teh")
        pick(strip.left)
        assertEquals("teh", editor.toString())
        assertEquals(listOf("teh"), engine.kept)
    }

    @Test
    fun pickingTheAutocorrectionIsLikeSpace() {
        start()
        type("teh")
        pick(strip.middle)
        type(" ")
        assertEquals("the ", editor.toString())
    }

    @Test
    fun aSpaceAfterAWordShowsWhatComesNext() {
        start()
        type("hello ")
        assertEquals(listOf("you", "there", null), words())
        assertEquals(Slot.Role.PREDICTION, strip.middle?.role)
        pick(strip.middle)
        assertEquals("hello there", editor.toString())
        assertEquals("there" to WordContext("hello", false), engine.learned.last())
        // A picked prediction leaves a space for the next word, and predicts again.
        type("x")
        assertEquals("hello there x", editor.toString())
    }

    @Test
    fun predictionsFollowAPickedPrediction() {
        start()
        type("how ")
        pick(strip.middle)
        assertEquals("how are", editor.toString())
        assertEquals("you", strip.middle?.text)
        pick(strip.middle)
        assertEquals("how are you", editor.toString())
    }

    @Test
    fun anEmptyFieldPredictsASentenceStart() {
        start()
        assertEquals(listOf("The", "I", "How"), words())
        pick(strip.middle)
        assertEquals("I", editor.toString())
        assertEquals("I" to WordContext(null, true), engine.learned.last())
    }

    @Test
    fun punctuationReplacesTheSpaceAfterAWord() {
        start()
        type("then .")
        assertEquals("then.", editor.toString())
        type(" they ?")
        assertEquals("then. they?", editor.toString())
    }

    @Test
    fun punctuationAfterASpaceTheUserTypedStays() {
        start(existing = "a ")
        type(".")
        assertEquals("a .", editor.toString())
    }

    @Test
    fun deleteEditsTheWordBeingTyped() {
        start()
        type("tehx")
        backspace()
        assertEquals("teh", editor.toString())
        assertEquals("the", strip.middle?.text)
        backspace()
        backspace()
        backspace()
        assertEquals("", editor.toString())
        assertFalse(logic.isComposing)
    }

    @Test
    fun deletingTheSpaceAfterAWordGoesBackIntoIt() {
        start()
        type("helo ")
        assertEquals("hello ", editor.toString())
        backspace() // undoes the autocorrection
        assertEquals("helo ", editor.toString())
        backspace() // deletes the space: "helo" is the word being typed again
        assertEquals("helo", editor.toString())
        assertTrue(logic.isComposing)
        assertEquals(0 to 4, editor.composeFrom to editor.composeTo)
        assertFalse("a word typed again is not autocorrected", engine.lastAutocorrect!!)
        // A suggestion replaces it.
        pick(strip.slots.first { it?.text == "hello" })
        assertEquals("hello", editor.toString())
    }

    @Test
    fun aWordTypedAgainKeepsItsSpellingOnSpace() {
        start(existing = "teh", cursor = 0)
        editor.tap(3)
        assertTrue(logic.isComposing)
        type(" ")
        assertEquals("teh ", editor.toString())
        assertTrue("not typed now: not learned", engine.learned.isEmpty())
    }

    @Test
    fun editingAWordTypedAgainAutocorrectsAgain() {
        start(existing = "te", cursor = 0)
        editor.tap(2)
        assertTrue(logic.isComposing)
        type("h")
        assertTrue(engine.lastAutocorrect!!)
        type(" ")
        assertEquals("the ", editor.toString())
    }

    @Test
    fun theCursorInsideAWordDoesNotResumeIt() {
        start(existing = "hello world")
        editor.tap(2)
        assertFalse(logic.isComposing)
        editor.tap(5)
        assertTrue(logic.isComposing)
        assertEquals(0 to 5, editor.composeFrom to editor.composeTo)
    }

    @Test
    fun apostrophesContinueAWordAndDigitsEndIt() {
        start()
        type("don't")
        assertTrue(logic.isComposing)
        assertEquals(0 to 5, editor.composeFrom to editor.composeTo)
        type("2")
        assertEquals("don't2", editor.toString())
        assertFalse(logic.isComposing)
        type("'x")
        assertEquals("don't2'x", editor.toString())
        assertEquals(7 to 8, editor.composeFrom to editor.composeTo)
    }

    @Test
    fun passwordsAndAddressesGetPlainTyping() {
        start(InputPolicy.PLAIN)
        type("teh ")
        assertEquals("teh ", editor.toString())
        assertEquals(-1, editor.composeFrom)
        assertEquals(Strip.EMPTY, strip)
        assertTrue(engine.learned.isEmpty())
    }

    @Test
    fun movingTheCursorLeavesTheWordAsItIs() {
        start(existing = "ab ")
        type("teh")
        editor.tap(1)
        assertFalse(logic.isComposing)
        assertEquals("ab teh", editor.toString())
        assertEquals(-1, editor.composeFrom)
        assertEquals(Strip.EMPTY, strip)
    }

    @Test
    fun lateCursorReportsDoNotEndTheWord() {
        start()
        logic.onText("t", null, null, false)
        val late = editor.takeReports()
        logic.onText("e", null, null, false)
        logic.onText("h", null, null, false)
        val current = editor.takeReports()
        editor.deliver(late)
        editor.deliver(current)
        assertTrue(logic.isComposing)
        type(" ")
        assertEquals("the ", editor.toString())
    }

    @Test
    fun lateReportsOfACommitDoNotEndTheNextWord() {
        start()
        type("then")
        logic.onSpace()
        val late = editor.takeReports()
        logic.onText("t", null, null, false)
        editor.deliver(late)
        editor.deliver()
        assertTrue(logic.isComposing)
        type("hey ")
        assertEquals("then they ", editor.toString())
    }

    @Test
    fun reportsInTheMiddleOfAnEditAreHandledAfterIt() {
        // The keyboard's own setup screen reports cursor moves synchronously.
        editor.immediate = true
        start()
        type("how ")
        assertEquals(Slot("are", Slot.Role.PREDICTION), strip.middle)
        pick(strip.middle)
        assertEquals("how are", editor.toString())
        assertFalse("the picked word stays picked", logic.isComposing)
        type("x")
        assertEquals("how are x", editor.toString())
        type(" teh ")
        assertEquals("how are x the ", editor.toString())
        backspace()
        assertEquals("how are x teh ", editor.toString())
        backspace()
        assertTrue(logic.isComposing)
        assertEquals(10 to 13, editor.composeFrom to editor.composeTo)
    }

    @Test
    fun aNewLineAutocorrectsButAFieldActionSendsAsTyped() {
        start()
        type("teh")
        logic.onEnter(isAction = false)
        editor.deliver()
        assertEquals("the", editor.toString())
        type(" teh")
        logic.onEnter(isAction = true)
        editor.deliver()
        assertEquals("the teh", editor.toString())
    }

    @Test
    fun theEngineHearsTheContextOfTheWord() {
        start()
        type("Teh", autoShifted = true)
        assertEquals(WordContext(null, true), engine.lastContext)
        type(" ")
        assertEquals("The ", editor.toString())
        type("teh")
        assertEquals(WordContext("The", false), engine.lastContext)
    }

    @Test
    fun finishingTheFieldLeavesTheWord() {
        start()
        type("teh")
        logic.finish()
        assertEquals("teh", editor.toString())
        assertEquals(-1, editor.composeFrom)
        assertNull(strip.middle)
    }

    private val path = listOf(GlidePoint(0f, 0f), GlidePoint(5f, 1f), GlidePoint(9f, 0f))

    private fun glide(shift: ShiftState = ShiftState.OFF) {
        logic.onGlide(path, shift)
        editor.deliver()
    }

    @Test
    fun aGlideWritesTheBestWordAndOffersTheOthers() {
        start()
        glide()
        assertEquals("hello", editor.toString())
        assertTrue(logic.isComposing)
        assertEquals(listOf("help", "hello", "hell"), words())
        type(" ")
        assertEquals("hello ", editor.toString())
        assertEquals("hello", engine.learned.last().first)
    }

    @Test
    fun aGlideAfterAWordGetsASpace() {
        start(existing = "hi")
        glide()
        assertEquals("hi hello", editor.toString())
        glide()
        assertEquals("hi hello hello", editor.toString())
        assertEquals(WordContext("hello", false), engine.lastGlideContext)
    }

    @Test
    fun aLetterAfterAGlideStartsANewWord() {
        start()
        glide()
        type("x")
        assertEquals("hello x", editor.toString())
    }

    @Test
    fun punctuationAfterAGlideAttaches() {
        start()
        glide()
        type(",")
        assertEquals("hello,", editor.toString())
    }

    @Test
    fun deleteTakesAGlidedWordAway() {
        start(existing = "hi ")
        glide()
        assertEquals("hi hello", editor.toString())
        backspace()
        assertEquals("hi ", editor.toString())
        assertFalse(logic.isComposing)
    }

    @Test
    fun aGlideFollowsShiftAndPickingSwapsTheWord() {
        start()
        glide(ShiftState.ONCE)
        assertEquals("Hello", editor.toString())
        pick(strip.left)
        assertEquals("Help ", editor.toString().let { if (it.endsWith(" ")) it else "$it " })
    }

    @Test
    fun noGlideInPlainFields() {
        start(prose.copy(compose = false, autocorrect = false))
        glide()
        assertEquals("", editor.toString())
    }

    @Test
    fun anEditingKeyLeavesTheWordAsTyped() {
        start()
        type("teh")
        logic.finishWord()
        assertEquals("teh", editor.toString())
        assertFalse(logic.isComposing)
        assertEquals(-1, editor.composeFrom)
        assertTrue(engine.learned.isEmpty())
    }

    @Test
    fun afterAFieldEndsItsRulesEndToo() {
        // A hardware keyboard may type into the next field before the keys
        // show: nothing is composed there, which could be a password.
        start(existing = "hello world")
        logic.finish()
        editor.tap(5)
        assertFalse(logic.isComposing)
        assertEquals(-1, editor.composeFrom)
    }

    @Test
    fun typingRightAfterAWordFinishesThatWord() {
        // "rec" was typed before (in another visit, or before a Delete):
        // the whole word is checked, never its second half alone.
        engine.fixes["eive"] = "I've"
        start(existing = "rec")
        type("eive ")
        assertEquals("receive ", editor.toString())
        assertEquals("receive", engine.learned.last().first)
    }

    @Test
    fun typingRightAfterAWordWhileTheCursorIsUnknown() {
        engine.fixes["eive"] = "I've"
        editor.text.append("rec")
        editor.cursor = 3
        logic.start(prose, -1, -1)
        type("eive ")
        assertEquals("receive ", editor.toString())
    }

    @Test
    fun typingInsideAWordTypesPlainly() {
        start(existing = "helo", cursor = 2)
        type("y")
        assertEquals("heylo", editor.toString())
        assertFalse(logic.isComposing)
    }

    @Test
    fun lateReportsDuringAHeldDeleteKeepTheRightLetters() {
        start()
        type("hello ")
        // Delete held down: each report arrives one repeat late.
        logic.onBackspace()
        var late = editor.takeReports()
        repeat(3) {
            logic.onBackspace()
            val now = editor.takeReports()
            editor.deliver(late)
            late = now
        }
        editor.deliver(late)
        assertEquals("he", editor.toString())
        type("y")
        assertEquals("hey", editor.toString())
    }

    @Test
    fun contextOfText() {
        assertEquals(WordContext(null, true), InputLogic.contextOf(""))
        assertEquals(WordContext(null, true), InputLogic.contextOf("Hi. "))
        assertEquals(WordContext(null, true), InputLogic.contextOf("Hi\n"))
        assertEquals(WordContext("there", false), InputLogic.contextOf("hello there "))
        assertEquals(WordContext("don't", false), InputLogic.contextOf("I don't "))
        assertEquals(WordContext.NONE, InputLogic.contextOf("hello"))
        assertEquals(WordContext.NONE, InputLogic.contextOf("(3) "))
        assertEquals("hello", InputLogic.trailingWord("say hello"))
        assertEquals("e-mail", InputLogic.trailingWord("an e-mail"))
        assertEquals("", InputLogic.trailingWord("an e-"))
    }

    @Test
    fun wordTextInAnyScript() {
        assertTrue(InputLogic.isWordText("a"))
        assertTrue(InputLogic.isWordText("é"))
        assertTrue(InputLogic.isWordText("क"))
        assertTrue(InputLogic.isWordText("ि")) // a Devanagari vowel sign
        assertTrue(InputLogic.isWordText("ש"))
        assertFalse(InputLogic.isWordText("1"))
        assertFalse(InputLogic.isWordText("."))
        assertFalse(InputLogic.isWordText(""))
        assertFalse(InputLogic.isWordText("😀")) // an emoji
    }
}
