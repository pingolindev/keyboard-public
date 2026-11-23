// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.view.KeyEvent
import com.pingolin.keyboard.engine.EditCommand

/** Android key codes for edit keys, and for characters typed with Ctrl or Alt. */
object KeyCodes {
    const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    const val ALT = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    /** The key for [command], or null for commands that are not one key. */
    fun of(command: EditCommand): Int? = when (command) {
        EditCommand.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        EditCommand.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        EditCommand.UP -> KeyEvent.KEYCODE_DPAD_UP
        EditCommand.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        EditCommand.HOME -> KeyEvent.KEYCODE_MOVE_HOME
        EditCommand.END -> KeyEvent.KEYCODE_MOVE_END
        EditCommand.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        EditCommand.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        EditCommand.FORWARD_DELETE -> KeyEvent.KEYCODE_FORWARD_DEL
        EditCommand.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
        EditCommand.TAB -> KeyEvent.KEYCODE_TAB
        EditCommand.SELECT, EditCommand.SELECT_ALL, EditCommand.CUT, EditCommand.COPY, EditCommand.PASTE -> null
    }

    /** Keys that move the cursor, and so select while Select is on. */
    fun moves(command: EditCommand): Boolean = when (command) {
        EditCommand.LEFT, EditCommand.RIGHT, EditCommand.UP, EditCommand.DOWN,
        EditCommand.HOME, EditCommand.END, EditCommand.PAGE_UP, EditCommand.PAGE_DOWN,
        -> true
        else -> false
    }

    /** Keys that repeat while held. */
    fun repeats(command: EditCommand): Boolean = when (command) {
        EditCommand.LEFT, EditCommand.RIGHT, EditCommand.UP, EditCommand.DOWN,
        EditCommand.PAGE_UP, EditCommand.PAGE_DOWN, EditCommand.FORWARD_DELETE,
        -> true
        else -> false
    }

    /**
     * The key that types [text] on a US keyboard, and whether it takes Shift,
     * so Ctrl and Alt can go with it (Ctrl+C, Alt+B). Null for anything else.
     */
    fun of(text: String): Pair<Int, Boolean>? {
        if (text.length != 1) return null
        val c = text[0]
        return when (c) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a') to false
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A') to true
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0') to false
            else -> PLAIN[c]?.let { it to false } ?: SHIFTED[c]?.let { it to true }
        }
    }

    private val PLAIN = mapOf(
        ' ' to KeyEvent.KEYCODE_SPACE,
        '-' to KeyEvent.KEYCODE_MINUS,
        '=' to KeyEvent.KEYCODE_EQUALS,
        '[' to KeyEvent.KEYCODE_LEFT_BRACKET,
        ']' to KeyEvent.KEYCODE_RIGHT_BRACKET,
        '\\' to KeyEvent.KEYCODE_BACKSLASH,
        ';' to KeyEvent.KEYCODE_SEMICOLON,
        '\'' to KeyEvent.KEYCODE_APOSTROPHE,
        '/' to KeyEvent.KEYCODE_SLASH,
        ',' to KeyEvent.KEYCODE_COMMA,
        '.' to KeyEvent.KEYCODE_PERIOD,
        '`' to KeyEvent.KEYCODE_GRAVE,
        '@' to KeyEvent.KEYCODE_AT,
        '#' to KeyEvent.KEYCODE_POUND,
        '*' to KeyEvent.KEYCODE_STAR,
        '+' to KeyEvent.KEYCODE_PLUS,
    )

    private val SHIFTED = mapOf(
        '!' to KeyEvent.KEYCODE_1,
        '$' to KeyEvent.KEYCODE_4,
        '%' to KeyEvent.KEYCODE_5,
        '^' to KeyEvent.KEYCODE_6,
        '&' to KeyEvent.KEYCODE_7,
        '(' to KeyEvent.KEYCODE_9,
        ')' to KeyEvent.KEYCODE_0,
        '_' to KeyEvent.KEYCODE_MINUS,
        '{' to KeyEvent.KEYCODE_LEFT_BRACKET,
        '}' to KeyEvent.KEYCODE_RIGHT_BRACKET,
        '|' to KeyEvent.KEYCODE_BACKSLASH,
        ':' to KeyEvent.KEYCODE_SEMICOLON,
        '"' to KeyEvent.KEYCODE_APOSTROPHE,
        '<' to KeyEvent.KEYCODE_COMMA,
        '>' to KeyEvent.KEYCODE_PERIOD,
        '?' to KeyEvent.KEYCODE_SLASH,
        '~' to KeyEvent.KEYCODE_GRAVE,
    )
}
