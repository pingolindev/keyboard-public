// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Shift: each tap moves on, from off to the next letter only, to caps lock, to
 * off. Typing a letter ends a single shift. Automatic capitals at the start of
 * a sentence set a single shift that a tap cancels; a quick second tap then
 * locks caps, as a double tap does anywhere.
 */
class ShiftController(private val doubleTapMillis: Long = 350) {
    var state = ShiftState.OFF
        private set

    private var automatic = false

    /** Shift is on only because a sentence starts here, not because the user pressed it. */
    val isAutomatic: Boolean get() = automatic && state == ShiftState.ONCE
    private var lastTapAt: Long? = null

    /** The last tap cancelled an automatic capital. */
    private var cancelledAutomatic = false

    fun onShiftTap(now: Long) {
        val quickSecondTap = lastTapAt?.let { now - it <= doubleTapMillis } ?: false
        val cancels = state == ShiftState.ONCE && automatic
        state = when (state) {
            ShiftState.OFF -> if (quickSecondTap && cancelledAutomatic) ShiftState.LOCKED else ShiftState.ONCE
            ShiftState.ONCE -> if (automatic) ShiftState.OFF else ShiftState.LOCKED
            ShiftState.LOCKED -> ShiftState.OFF
        }
        cancelledAutomatic = cancels
        automatic = false
        lastTapAt = now
    }

    fun onTyped() {
        if (state == ShiftState.ONCE) {
            state = ShiftState.OFF
            automatic = false
        }
    }

    /** The field says whether the next character should be a capital. */
    fun onAutoCaps(capitalNext: Boolean) {
        when {
            state == ShiftState.LOCKED -> Unit
            capitalNext && state == ShiftState.OFF -> {
                state = ShiftState.ONCE
                automatic = true
            }
            !capitalNext && automatic -> {
                state = ShiftState.OFF
                automatic = false
            }
        }
    }

    fun reset() {
        state = ShiftState.OFF
        automatic = false
        lastTapAt = null
        cancelledAutomatic = false
    }
}
