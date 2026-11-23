// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LatchTest {
    @Test
    fun oneTapHoldsForOneKey() {
        val latch = Latch()
        latch.tap(0)
        assertEquals(Latch.State.ONCE, latch.state)
        latch.used()
        assertEquals(Latch.State.OFF, latch.state)
    }

    @Test
    fun twoQuickTapsLockUntilTheNextTap() {
        val latch = Latch()
        latch.tap(0)
        latch.tap(200)
        assertEquals(Latch.State.LOCKED, latch.state)
        latch.used()
        assertEquals(Latch.State.LOCKED, latch.state)
        latch.tap(5_000)
        assertEquals(Latch.State.OFF, latch.state)
    }

    @Test
    fun aSlowSecondTapTurnsItOff() {
        val latch = Latch()
        latch.tap(0)
        latch.tap(2_000)
        assertEquals(Latch.State.OFF, latch.state)
    }
}
