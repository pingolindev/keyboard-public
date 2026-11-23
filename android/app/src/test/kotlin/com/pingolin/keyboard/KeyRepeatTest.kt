// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatTest {
    @Test
    fun heldDeleteDeletesWordsFasterAndFaster() {
        val waits = (0 until 12).map(KeyRepeat::wordInterval)
        assertEquals(300L, waits.first())
        assertTrue(waits.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(waits[3] < waits[0])
        assertEquals(100L, waits.last())
        assertEquals(100L, KeyRepeat.wordInterval(1_000))
    }
}
