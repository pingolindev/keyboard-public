// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/** One word in the suggestion strip. */
data class Slot(val text: String, val role: Role) {
    enum class Role {
        /** The word as typed, when a space would change it: tapping keeps it. */
        TYPED,

        /** What a space will put in place of the typed word. */
        AUTOCORRECT,

        /** Another word to pick. */
        SUGGESTION,

        /** The typed word when a space keeps it. */
        CURRENT,

        /** A word that may come next. */
        PREDICTION,

        /** An emoji the word stands for: tapping puts it in the word's place. */
        EMOJI,
    }
}

/** The three places of the strip. The middle one is always what a space does. */
data class Strip(val left: Slot?, val middle: Slot?, val right: Slot?) {
    val slots: List<Slot?> get() = listOf(left, middle, right)

    companion object {
        val EMPTY = Strip(null, null, null)

        /**
         * Lays out the suggestions for [typed]. With an autocorrection a space
         * will apply, the typed word goes left so it can be kept; otherwise the
         * best two other words go to the sides. An emoji for the middle word
         * takes the right place.
         */
        fun arrange(
            typed: String,
            suggestions: WordSuggestions?,
            applyAutocorrect: Boolean = true,
            emojiFor: (String) -> String? = { null },
        ): Strip {
            if (typed.isEmpty() || suggestions == null) return EMPTY
            val auto = suggestions.autocorrect?.takeIf { applyAutocorrect && it != typed }
            val middle = if (auto != null) Slot(auto, Slot.Role.AUTOCORRECT) else Slot(typed, Slot.Role.CURRENT)
            val others = suggestions.candidates.filter { it != typed && it != middle.text }.distinct().iterator()
            val left = if (auto != null) Slot(typed, Slot.Role.TYPED) else others.nextOrNull()?.let { Slot(it, Slot.Role.SUGGESTION) }
            val right = emojiFor(middle.text)?.let { Slot(it, Slot.Role.EMOJI) }
                ?: others.nextOrNull()?.let { Slot(it, Slot.Role.SUGGESTION) }
            return Strip(left, middle, right)
        }

        /** Words that may come next: the likeliest in the middle. */
        fun predictions(words: List<String>): Strip {
            val slots = words.distinct().take(3).map { Slot(it, Slot.Role.PREDICTION) }
            return Strip(slots.getOrNull(1), slots.getOrNull(0), slots.getOrNull(2))
        }

        private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
    }
}
