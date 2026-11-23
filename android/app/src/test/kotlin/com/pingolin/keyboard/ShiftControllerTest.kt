// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import com.pingolin.keyboard.ShiftState.LOCKED
import com.pingolin.keyboard.ShiftState.OFF
import com.pingolin.keyboard.ShiftState.ONCE
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftControllerTest {
    private val shift = ShiftController(doubleTapMillis = 350)

    @Test
    fun aTapShiftsTheNextLetterOnly() {
        shift.onShiftTap(now = 0)
        assertEquals(ONCE, shift.state)
        shift.onTyped()
        assertEquals(OFF, shift.state)
    }

    @Test
    fun aQuickSecondTapLocksCapsUntilTheNextTap() {
        shift.onShiftTap(now = 0)
        shift.onShiftTap(now = 200)
        assertEquals(LOCKED, shift.state)
        shift.onTyped()
        assertEquals(LOCKED, shift.state)
        shift.onShiftTap(now = 5_000)
        assertEquals(OFF, shift.state)
    }

    @Test
    fun tapsCycleThroughOneCapitalCapsLockAndOffAtAnyPace() {
        shift.onShiftTap(now = 0)
        assertEquals(ONCE, shift.state)
        shift.onShiftTap(now = 5_000)
        assertEquals(LOCKED, shift.state)
        shift.onShiftTap(now = 10_000)
        assertEquals(OFF, shift.state)
        shift.onShiftTap(now = 15_000)
        assertEquals(ONCE, shift.state)
    }

    @Test
    fun automaticCapitalsFollowTheField() {
        shift.onAutoCaps(capitalNext = true)
        assertEquals(ONCE, shift.state)
        shift.onAutoCaps(capitalNext = false)
        assertEquals(OFF, shift.state)
    }

    @Test
    fun aTapCancelsAnAutomaticCapitalInsteadOfLocking() {
        shift.onAutoCaps(capitalNext = true)
        shift.onShiftTap(now = 0)
        assertEquals(OFF, shift.state)
    }

    @Test
    fun aQuickDoubleTapAtASentenceStartLocksCaps() {
        shift.onAutoCaps(capitalNext = true)
        shift.onShiftTap(now = 0)
        shift.onShiftTap(now = 200)
        assertEquals(LOCKED, shift.state)
    }

    @Test
    fun aSlowTapAfterCancellingAnAutomaticCapitalShiftsOnce() {
        shift.onAutoCaps(capitalNext = true)
        shift.onShiftTap(now = 0)
        shift.onShiftTap(now = 2_000)
        assertEquals(ONCE, shift.state)
        shift.onShiftTap(now = 4_000)
        assertEquals(LOCKED, shift.state)
    }

    @Test
    fun aManualShiftOutlastsTheFieldWantingNoCapital() {
        shift.onShiftTap(now = 0)
        shift.onAutoCaps(capitalNext = false)
        assertEquals(ONCE, shift.state)
    }

    @Test
    fun automaticCapitalsLeaveCapsLockAlone() {
        shift.onShiftTap(now = 0)
        shift.onShiftTap(now = 100)
        shift.onAutoCaps(capitalNext = false)
        assertEquals(LOCKED, shift.state)
        shift.onAutoCaps(capitalNext = true)
        assertEquals(LOCKED, shift.state)
    }

    @Test
    fun resetForgetsEverything() {
        shift.onShiftTap(now = 0)
        shift.reset()
        assertEquals(OFF, shift.state)
        shift.onShiftTap(now = 100)
        assertEquals("a tap after reset is a first tap", ONCE, shift.state)
    }
}

class UpperCaseTest {
    @org.junit.Test
    fun lettersBecomeOneUpperCaseLetter() {
        org.junit.Assert.assertEquals("A", upperCase("a", java.util.Locale.ROOT))
        org.junit.Assert.assertEquals("İ", upperCase("i", java.util.Locale.forLanguageTag("tr")))
        org.junit.Assert.assertEquals("I", upperCase("ı", java.util.Locale.forLanguageTag("tr")))
        org.junit.Assert.assertEquals("ß", upperCase("ß", java.util.Locale.forLanguageTag("de")))
        org.junit.Assert.assertEquals("Ü", upperCase("ü", java.util.Locale.forLanguageTag("de")))
    }
}
