// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

enum class ShiftState { OFF, ONCE, LOCKED }

/**
 * Shift: a tap shifts the next letter, a second tap soon after locks caps, a tap
 * while locked turns it off. Typing a letter ends a single shift. Automatic
 * capitals at the start of a sentence set a single shift that a tap cancels.
 */
class ShiftController(private val doubleTapMillis: Long = 350) {
    var state = ShiftState.OFF
        private set

    private var automatic = false

    /** Shift is on only because a sentence starts here, not because the user pressed it. */
    val isAutomatic: Boolean get() = automatic && state == ShiftState.ONCE
    private var lastTapAt: Long? = null

    fun onShiftTap(now: Long) {
        val quickSecondTap = lastTapAt?.let { now - it <= doubleTapMillis } ?: false
        state = when (state) {
            ShiftState.OFF -> ShiftState.ONCE
            ShiftState.ONCE -> if (!automatic && quickSecondTap) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
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
    }
}
