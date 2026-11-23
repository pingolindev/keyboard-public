// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/** What a field allows, decided when it gains focus. */
data class InputPolicy(
    /** Type words as composing text, with suggestions and predictions. */
    val compose: Boolean,
    /** A space may replace the typed word with its autocorrection. */
    val autocorrect: Boolean,
    /**
     * Use and add to what the keyboard knows of the user: learned words, next
     * words, undone corrections. Never in incognito (the field's or the
     * user's), in passwords, or before the first unlock.
     */
    val personal: Boolean,
    /** Learn the words typed here, on the phone: [personal] and the user's setting. */
    val learn: Boolean,
    /** Punctuation that belongs right after a word: the space before it goes. */
    val attaching: String = ".,!?:;",
) {
    companion object {
        val PLAIN = InputPolicy(compose = false, autocorrect = false, personal = false, learn = false)
    }
}

/**
 * Turns key presses into edits: the word being typed is composing text with
 * suggestions; a space or punctuation commits it, autocorrected if the engine
 * says so; Delete right after an autocorrection puts back what was typed.
 * After a word, the strip shows the words that may come next. Moving the
 * cursor to the end of a word, or deleting the space after one, makes it the
 * word being typed again, so a suggestion can replace it.
 *
 * Positions are UTF-16 offsets, as Android reports them. The field reports
 * the cursor some time after each edit, so updates for earlier edits can
 * still arrive; an update that matches none of the recent positions means
 * the user moved the cursor, and the word being typed is left as it is.
 */
class InputLogic(
    private val field: TextField,
    private val suggester: WordSuggester,
    private val onStrip: (Strip) -> Unit,
) {
    private var policy = InputPolicy.PLAIN
    private val composing = StringBuilder()
    private val keys = ArrayList<TypedKey>()
    private var sentenceStart = false
    private var context = WordContext.NONE
    private var suggestions: WordSuggestions? = null

    /** The word being typed was already in the field: a space keeps it as it is. */
    private var resumed = false

    /** The word being typed came from a glide: the next glide or letter starts a new word. */
    private var glided = false

    /** What the predictions on the strip follow. */
    private var predictionContext = WordContext.NONE

    /** The last autocorrection, until the next key. */
    private var undo: Undo? = null

    /** A word was picked: the next word gets a space before it. */
    private var phantomSpace = false

    /** The space just typed after a word: attaching punctuation replaces it. */
    private var weakSpace = false

    private var cursor = UNKNOWN
    private var composeStart = UNKNOWN
    private val recent = ArrayDeque<Int>()

    /**
     * Cursor reports that arrived during an edit. An editor in the keyboard's
     * own process (its setup screen) reports synchronously, in the middle of
     * the edit; they are handled once the edit is done, like any late report.
     */
    private var depth = 0
    private val pending = ArrayDeque<IntArray>()

    private data class Undo(val typed: String, val committed: String, val separator: String)

    val isComposing: Boolean get() = composing.isNotEmpty()

    fun start(policy: InputPolicy, selStart: Int, selEnd: Int) = edit {
        pending.clear()
        clearWord()
        resetSpaces()
        this.policy = policy
        recent.clear()
        cursor = UNKNOWN
        if (selStart >= 0 && selStart == selEnd) moveTo(selStart)
        if (policy.compose && cursor != UNKNOWN && field.textAfterCursor(1).isNullOrEmpty()) {
            showPredictions()
        } else {
            onStrip(Strip.EMPTY)
        }
    }

    fun finish() = edit {
        if (composing.isNotEmpty()) field.finishComposingText()
        clearWord()
        resetSpaces()
        policy = InputPolicy.PLAIN
        onStrip(Strip.EMPTY)
    }

    /**
     * A glide over the keys: the best word becomes the word being typed, the
     * others go to the strip. What was being typed is committed first, and a
     * space goes before the new word after another word. [shift]: the case.
     */
    fun onGlide(points: List<GlidePoint>, shift: ShiftState) = edit {
        if (!policy.compose || points.size < 2) return@edit
        if (composing.isNotEmpty()) commitWord(" ", autocorrect = true)
        val before = field.textBeforeCursor(1)
        if (phantomSpace || before?.lastOrNull()?.let(::isWordChar) == true) {
            field.commitText(" ")
            advance(1)
        }
        resetSpaces()
        val wordContext = contextOf(field.textBeforeCursor(CONTEXT) ?: "")
        val words = suggester.glide(points, wordContext, policy.personal).map { cased(it, shift) }.distinct()
        val word = words.firstOrNull() ?: return@edit
        composeStart = cursor
        context = wordContext
        sentenceStart = wordContext.sentenceStart
        composing.append(word)
        var i = 0
        while (i < word.length) {
            val n = Character.charCount(word.codePointAt(i))
            keys += TypedKey(word.substring(i, i + n), null, null)
            i += n
        }
        glided = true
        field.setComposingText(word)
        afterCompose()
        val result = WordSuggestions(word, typedIsWord = true, autocorrect = null, candidates = words.drop(1))
        suggestions = result
        onStrip(Strip.arrange(word, result, applyAutocorrect = false))
    }

    private fun cased(word: String, shift: ShiftState): String = when (shift) {
        ShiftState.OFF -> word
        ShiftState.LOCKED -> word.uppercase()
        else -> word.replaceFirstChar { it.uppercase() }
    }

    /** Pastes [text] from the clipboard history; the word being typed stays as typed. */
    fun onPaste(text: String) = edit {
        if (composing.isNotEmpty()) {
            field.finishComposingText()
            clearWord()
        }
        resetSpaces()
        field.commitText(text)
        advance(text.length)
        onStrip(Strip.EMPTY)
    }

    /** Writes what voice typing heard, with a space before it after a word. */
    fun onVoice(text: String) = edit {
        if (composing.isNotEmpty()) {
            field.finishComposingText()
            clearWord()
        }
        resetSpaces()
        val before = field.textBeforeCursor(1)
        val spaced = if (before.isNullOrEmpty() || before.last().isWhitespace()) text else " $text"
        field.commitText(spaced)
        advance(spaced.length)
        onStrip(Strip.EMPTY)
    }

    /** Before an editing key: the word being typed stays as typed. */
    fun finishWord() = edit {
        if (composing.isNotEmpty()) {
            field.finishComposingText()
            clearWord()
        }
        resetSpaces()
        onStrip(Strip.EMPTY)
    }

    /** The same field with other rules (the language or incognito changed). */
    fun updatePolicy(policy: InputPolicy) = edit {
        this.policy = policy
        if (composing.isNotEmpty()) refresh() else showPredictions()
    }

    /** A key typed [text]. [x] and [y] are where it was touched, if known. */
    fun onText(text: String, x: Float?, y: Float?, autoShifted: Boolean) = edit {
        // A letter after a glided word starts the next word.
        if (glided && isWordText(text)) commitWord(" ", autocorrect = false)
        val continues = composing.isNotEmpty() && isConnector(text)
        if (!policy.compose || !(isWordText(text) || continues)) {
            separator(text)
            return@edit
        }
        if (composing.isEmpty()) {
            if (!phantomSpace && field.textBeforeCursor(1)?.lastOrNull()?.let(::isWordChar) == true) {
                // Right after a word, the word grows, so it is checked whole.
                // Inside a word, or before the cursor is known, typed as it is.
                resume()
                if (composing.isEmpty()) {
                    field.commitText(text)
                    advance(text.length)
                    resetSpaces()
                    onStrip(Strip.EMPTY)
                    return@edit
                }
            } else {
                startWord(autoShifted)
            }
        }
        composing.append(text)
        keys += TypedKey(text, x, y)
        resumed = false
        field.setComposingText(composing.toString())
        afterCompose()
        refresh()
    }

    fun onSpace() = edit {
        if (composing.isNotEmpty()) {
            commitWord(" ", autocorrect = true)
            return@edit
        }
        field.commitText(" ")
        advance(1)
        weakSpace = phantomSpace
        phantomSpace = false
        undo = null
        showPredictions()
    }

    fun onBackspace() = edit { backspace() }

    private fun backspace() {
        if (glided) {
            // A glided word goes as a whole, as it came.
            val start = composeStart
            field.setComposingText("")
            field.finishComposingText()
            clearWord()
            if (start != UNKNOWN) moveTo(start) else cursor = UNKNOWN
            showPredictions()
            return
        }
        if (composing.isNotEmpty()) {
            val last = keys.removeAt(keys.lastIndex)
            composing.setLength(composing.length - last.text.length)
            resumed = false
            if (composing.isEmpty()) {
                val start = composeStart
                field.setComposingText("")
                field.finishComposingText()
                clearWord()
                if (start != UNKNOWN) moveTo(start) else cursor = UNKNOWN
                onStrip(Strip.EMPTY)
            } else {
                field.setComposingText(composing.toString())
                afterCompose()
                refresh()
            }
            return
        }
        val lastCorrection = undo
        resetSpaces()
        onStrip(Strip.EMPTY)
        if (lastCorrection != null) {
            val committed = lastCorrection.committed + lastCorrection.separator
            if (field.textBeforeCursor(committed.length) == committed) {
                val restored = lastCorrection.typed + lastCorrection.separator
                field.beginBatchEdit()
                field.deleteBeforeCursor(committed.length)
                field.commitText(restored)
                field.endBatchEdit()
                advance(restored.length - committed.length)
                keep(lastCorrection.typed)
                return
            }
        }
        // Deleting the space after a word goes back into the word.
        val tail = if (policy.compose && cursor != UNKNOWN) field.textBeforeCursor(2) else null
        if (tail != null && tail.length == 2 && tail[1] == ' ' && isWordChar(tail[0])) {
            field.deleteBeforeCursor(1)
            advance(-1)
            resume()
            return
        }
        field.sendBackspace()
        // Usually one character; the field's report corrects an emoji or a selection.
        if (cursor > 0) moveTo(cursor - 1) else cursor = UNKNOWN
    }

    /** Delete held down: the word before the cursor goes as a whole, then the one before it. */
    fun onDeleteWord() = edit { deleteWord() }

    private fun deleteWord() {
        undo = null
        resetSpaces()
        onStrip(Strip.EMPTY)
        if (composing.isNotEmpty()) {
            val start = composeStart
            field.setComposingText("")
            field.finishComposingText()
            clearWord()
            if (start != UNKNOWN) moveTo(start) else cursor = UNKNOWN
            return
        }
        val count = field.textBeforeCursor(DELETE_LOOKBACK)?.let(::lastWordLength) ?: 0
        if (count == 0) {
            // Nothing to read (an empty field, or one that hides its text): one Delete.
            field.sendBackspace()
            if (cursor > 0) moveTo(cursor - 1) else cursor = UNKNOWN
            return
        }
        field.deleteBeforeCursor(count)
        if (cursor != UNKNOWN && cursor >= count) moveTo(cursor - count) else cursor = UNKNOWN
    }

    /** A word from the strip was tapped. */
    fun onPick(slot: Slot) = edit {
        if (composing.isEmpty()) {
            if (slot.role == Slot.Role.PREDICTION) pickPrediction(slot.text)
            return@edit
        }
        val typed = composing.toString()
        val start = composeStart
        val wordContext = context
        field.commitText(slot.text)
        clearWord()
        if (start != UNKNOWN) moveTo(start + slot.text.length) else cursor = UNKNOWN
        if (slot.role == Slot.Role.TYPED) keep(typed) else learn(slot.text, wordContext)
        undo = null
        weakSpace = false
        phantomSpace = needsSpaceAfter()
        showPredictions()
    }

    /**
     * Before Enter acts. A new line autocorrects like a space; a field action
     * (Send, Go) sends the word as typed.
     */
    fun onEnter(isAction: Boolean) = edit {
        if (composing.isNotEmpty()) commitWord("", autocorrect = !isAction)
        resetSpaces()
        cursor = UNKNOWN
        onStrip(Strip.EMPTY)
    }

    /** The field reports where its cursor and composing text are. */
    fun onSelectionUpdate(newStart: Int, newEnd: Int, composeFrom: Int, composeTo: Int) {
        pending.addLast(intArrayOf(newStart, newEnd, composeFrom, composeTo))
        if (depth == 0) edit { }
    }

    /** Runs an edit, then the cursor reports that arrived meanwhile, in order. */
    private inline fun edit(block: () -> Unit) {
        depth++
        try {
            block()
        } finally {
            depth--
        }
        if (depth == 0) {
            depth++
            try {
                while (pending.isNotEmpty()) {
                    val r = pending.removeFirst()
                    selection(r[0], r[1], r[2], r[3])
                }
            } finally {
                depth--
            }
        }
    }

    private fun selection(newStart: Int, newEnd: Int, composeFrom: Int, composeTo: Int) {
        val collapsed = newStart == newEnd
        if (composing.isNotEmpty()) {
            // Ours: the word as it is, or as it was a few edits ago (a longer
            // word before a Delete) ending where the cursor recently was.
            val ours = collapsed && composeFrom >= 0 && composeTo == newEnd &&
                (composeTo - composeFrom <= composing.length || newEnd in recent) &&
                (composeStart == UNKNOWN || composeFrom == composeStart)
            if (ours) {
                if (composeStart == UNKNOWN) composeStart = composeFrom
                if (composeTo - composeFrom == composing.length) moveTo(newEnd)
                return
            }
            if (collapsed && composeFrom < 0 && newStart in recent) return // late news of an earlier edit
            field.finishComposingText()
            clearWord()
            resetSpaces()
            onStrip(Strip.EMPTY)
            adopt(newStart, newEnd)
            resume()
            return
        }
        if (collapsed && cursor == UNKNOWN) {
            moveTo(newStart) // learn where an edit of unknown size left the cursor
            return
        }
        if (collapsed && (newStart == cursor || newStart in recent)) return
        resetSpaces()
        onStrip(Strip.EMPTY)
        adopt(newStart, newEnd)
        resume()
    }

    private fun startWord(autoShifted: Boolean) {
        if (phantomSpace) {
            field.commitText(" ")
            advance(1)
        }
        resetSpaces()
        composeStart = cursor
        context = contextOf(field.textBeforeCursor(CONTEXT) ?: "")
        sentenceStart = autoShifted || context.sentenceStart
    }

    /** Makes the word just before the cursor the word being typed again. */
    private fun resume() {
        if (!policy.compose || cursor == UNKNOWN) return
        val after = field.textAfterCursor(1) ?: return
        if (after.isNotEmpty() && isWordChar(after[0])) return
        val before = field.textBeforeCursor(MAX_WORD + CONTEXT) ?: return
        val word = trailingWord(before)
        if (word.isEmpty() || word.length > MAX_WORD) return
        val start = cursor - word.length
        field.setComposingRegion(start, cursor)
        composeStart = start
        composing.append(word)
        var i = 0
        while (i < word.length) {
            val n = Character.charCount(word.codePointAt(i))
            keys += TypedKey(word.substring(i, i + n), null, null)
            i += n
        }
        context = contextOf(before.substring(0, before.length - word.length))
        sentenceStart = context.sentenceStart
        resumed = true
        refresh()
    }

    private fun separator(text: String) {
        if (composing.isNotEmpty()) {
            commitWord(text, autocorrect = true)
            return
        }
        val attaches = text.length == 1 && policy.attaching.contains(text)
        if (attaches && weakSpace && field.textBeforeCursor(1) == " ") {
            field.beginBatchEdit()
            field.deleteBeforeCursor(1)
            field.commitText(text)
            field.endBatchEdit()
            advance(text.length - 1)
        } else {
            if (phantomSpace && !attaches) {
                field.commitText(" ")
                advance(1)
            }
            field.commitText(text)
            advance(text.length)
        }
        resetSpaces()
        onStrip(Strip.EMPTY)
    }

    private fun commitWord(separator: String, autocorrect: Boolean) {
        val typed = composing.toString()
        val result = suggestions
        val corrected = result?.autocorrect
            ?.takeIf { autocorrect && policy.autocorrect && !resumed && !glided && it != typed }
        val word = corrected ?: typed
        val start = composeStart
        val wordContext = context
        // A word the engine would correct is a typo, even where autocorrect
        // is off: only words, and words it has no correction for, are learned.
        // A word picked up again from the field was not typed now: not learned.
        val learnable = !resumed &&
            (corrected != null || result == null || result.typedIsWord || result.autocorrect == null)
        field.commitText(word + separator)
        clearWord()
        if (start != UNKNOWN) moveTo(start + word.length + separator.length) else cursor = UNKNOWN
        undo = corrected?.let { Undo(typed, it, separator) }
        if (learnable) learn(word, wordContext)
        phantomSpace = false
        weakSpace = separator == " "
        if (separator == " ") showPredictions() else onStrip(Strip.EMPTY)
    }

    private fun pickPrediction(word: String) {
        val wordContext = predictionContext
        val text = if (phantomSpace) " $word" else word
        field.commitText(text)
        advance(text.length)
        learn(word, wordContext)
        resetSpaces()
        phantomSpace = needsSpaceAfter()
        showPredictions()
    }

    private fun showPredictions() {
        if (!policy.compose || cursor == UNKNOWN) {
            onStrip(Strip.EMPTY)
            return
        }
        val before = field.textBeforeCursor(CONTEXT) ?: ""
        val wordContext = contextOf(if (phantomSpace) "$before " else before)
        // Only where a new word starts: after a space, or at a sentence start.
        if (!wordContext.sentenceStart && wordContext.previous == null) {
            onStrip(Strip.EMPTY)
            return
        }
        predictionContext = wordContext
        onStrip(Strip.predictions(suggester.predict(wordContext, policy.personal)))
    }

    private fun refresh() {
        // Corrections are always asked for: the policy decides whether a space
        // applies them, and a word the engine would correct is not learned.
        val result = suggester.suggest(keys.toList(), !resumed, WordContext(context.previous, sentenceStart), policy.personal)
        suggestions = result
        onStrip(Strip.arrange(composing.toString(), result, applyAutocorrect = policy.autocorrect && !resumed))
    }

    private fun learn(word: String, wordContext: WordContext) {
        if (policy.learn) suggester.learn(word, wordContext)
    }

    /** The user kept a word against an autocorrection. */
    private fun keep(typed: String) {
        when {
            policy.learn -> suggester.keep(typed)
            policy.personal -> suggester.reject(typed)
        }
    }

    /** A picked word gets a space before the next word, unless one follows already. */
    private fun needsSpaceAfter(): Boolean = field.textAfterCursor(1)?.firstOrNull()?.isWhitespace() != true

    private fun clearWord() {
        composing.setLength(0)
        keys.clear()
        suggestions = null
        composeStart = UNKNOWN
        sentenceStart = false
        context = WordContext.NONE
        resumed = false
        glided = false
    }

    private fun resetSpaces() {
        undo = null
        phantomSpace = false
        weakSpace = false
    }

    private fun afterCompose() {
        if (composeStart != UNKNOWN) moveTo(composeStart + composing.length) else cursor = UNKNOWN
    }

    private fun advance(delta: Int) {
        if (cursor != UNKNOWN) moveTo(cursor + delta)
    }

    private fun moveTo(position: Int) {
        cursor = position
        recent.addLast(position)
        while (recent.size > RECENT) recent.removeFirst()
    }

    private fun adopt(start: Int, end: Int) {
        recent.clear()
        if (start == end && start >= 0) moveTo(start) else cursor = UNKNOWN
    }

    companion object {
        private const val UNKNOWN = -1
        private const val RECENT = 8
        /** Characters read before the cursor to find the previous word. */
        private const val CONTEXT = 64
        /** The longest word that can be typed again. */
        private const val MAX_WORD = 32

        /** Characters read before the cursor for Delete held down; a longer word goes in parts. */
        private const val DELETE_LOOKBACK = 128

        fun isWordChar(c: Char): Boolean = Character.isLetter(c) || when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

        /** Letters and the marks that belong to them, in any script. */
        fun isWordText(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch { cp ->
            Character.isLetter(cp) || when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
                else -> false
            }
        }

        /** Apostrophes and hyphens continue a word ("don't", "e-mail"). */
        fun isConnector(text: String): Boolean = text == "'" || text == "’" || text == "-" || text == "‐"

        private fun isConnector(c: Char): Boolean = c == '\'' || c == '’' || c == '-' || c == '‐'

        /** The word ending [text]: letters, with apostrophes and hyphens inside it. */
        fun trailingWord(text: String): String {
            var i = text.length
            while (i > 0) {
                val c = text[i - 1]
                if (isWordChar(c) || (isConnector(c) && i >= 2 && isWordChar(text[i - 2]) && i < text.length)) {
                    i--
                } else {
                    break
                }
            }
            return text.substring(i)
        }

        /**
         * How many characters (UTF-16 units) Delete held down takes from the end
         * of [before]: a line break alone; else the spaces there, then the word
         * before them (letters and digits with their marks, apostrophes and
         * hyphens inside it) or a run of other symbols, an emoji whole.
         */
        fun lastWordLength(before: String): Int {
            val end = before.length
            if (end == 0) return 0
            if (before[end - 1] == '\n') return 1
            var i = end
            while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == '\t')) i--
            if (i == 0 || before[i - 1] == '\n') return end - i
            val inWord = isWordBase(before.codePointAt(clusterStart(before, i)))
            var taken = false
            while (i > 0) {
                val start = clusterStart(before, i)
                val base = before.codePointAt(start)
                val keep = if (inWord) {
                    isWordBase(base) || (
                        taken && isConnector(base) && start > 0 &&
                            isWordBase(before.codePointAt(clusterStart(before, start)))
                        )
                } else {
                    base != ' '.code && base != '\t'.code && base != '\n'.code && !isWordBase(base)
                }
                if (!keep) break
                i = start
                taken = true
            }
            return end - i
        }

        private fun isWordBase(cp: Int): Boolean = Character.isLetterOrDigit(cp) || cp == '_'.code

        private fun isConnector(cp: Int): Boolean = cp == '\''.code || cp == 0x2019 || cp == '-'.code || cp == 0x2010

        /** Code points that belong to the character before them: marks, joiners, variation selectors, skin tones. */
        private fun isExtender(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> cp == 0x200D || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
        }

        /** Where the character ending at [end] starts, with its marks; joined emoji count as one. */
        private fun clusterStart(text: String, end: Int): Int {
            var j = end
            while (j > 0) {
                val cp = text.codePointBefore(j)
                j -= Character.charCount(cp)
                if (isExtender(cp)) continue
                if (j > 0 && text.codePointBefore(j) == 0x200D) continue
                break
            }
            return j
        }

        /**
         * What a word typed after [before] follows: the previous word when
         * spaces separate them, or a sentence start (empty text, a new line,
         * or . ! ? before the spaces).
         */
        fun contextOf(before: String): WordContext {
            var i = before.length
            while (i > 0 && before[i - 1] == ' ') i--
            if (i == 0) return WordContext(null, true)
            val c = before[i - 1]
            if (c == '\n' || c == '.' || c == '!' || c == '?') return WordContext(null, true)
            if (i == before.length) return WordContext.NONE // no space: nothing to follow
            val word = trailingWord(before.substring(0, i))
            return if (word.isEmpty()) WordContext.NONE else WordContext(word, false)
        }
    }
}
