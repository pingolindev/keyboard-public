// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/**
 * A modifier key that holds for the next key: one tap turns it on for one
 * key, a second tap in a row locks it, the next tap turns it off.
 */
class Latch(private val doubleTapMillis: Long = DOUBLE_TAP_MS) {
    enum class State { OFF, ONCE, LOCKED }

    var state = State.OFF
        private set
    private var lastTap = Long.MIN_VALUE / 2

    val isOn: Boolean get() = state != State.OFF

    fun tap(now: Long) {
        state = when (state) {
            State.OFF -> State.ONCE
            State.ONCE -> if (now - lastTap <= doubleTapMillis) State.LOCKED else State.OFF
            State.LOCKED -> State.OFF
        }
        lastTap = now
    }

    /** A key was typed with it. */
    fun used() {
        if (state == State.ONCE) state = State.OFF
    }

    fun reset() {
        state = State.OFF
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
    }
}
