// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/** Which languages a field gets. Pure, so it runs in plain JVM tests. */
object LanguageChoice {
    /**
     * The [enabled] languages, the one to start with first: the [current] one,
     * or where a field takes only ASCII ([asciiOnly]) one that is [latin].
     * [english] comes along when none of them is: addresses and most passwords
     * need Latin letters.
     */
    fun order(
        enabled: List<Language>,
        english: Language?,
        current: String,
        asciiOnly: Boolean,
        latin: (Language) -> Boolean,
    ): List<Language> {
        val all = if (enabled.any(latin)) enabled else (enabled + listOfNotNull(english)).distinct()
        val usual = all.firstOrNull { it.tag == current } ?: all.firstOrNull() ?: return emptyList()
        val first = if (asciiOnly && !latin(usual)) all.firstOrNull(latin) ?: usual else usual
        return listOf(first) + all.filter { it != first }
    }
}
