// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** How held keys repeat. Pure, so it runs in plain JVM tests. */
object KeyRepeat {
    /** The first repeat comes this long after the press. */
    const val DELAY_MS = 400L

    /** Arrows and the other editing keys, and Delete in a terminal: the time between repeats. */
    const val INTERVAL_MS = 50L

    /** Delete held down deletes words: the wait after the [n]th word, shorter each time. */
    fun wordInterval(n: Int): Long = max(WORD_MIN_MS, (WORD_FIRST_MS * WORD_FACTOR.pow(n)).roundToLong())

    private const val WORD_FIRST_MS = 300.0
    private const val WORD_FACTOR = 0.85
    private const val WORD_MIN_MS = 100L
}
