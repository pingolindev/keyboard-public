// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.view.KeyEvent
import com.pingolin.keyboard.engine.EditCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCodesTest {
    @Test
    fun charactersMapToTheKeysThatTypeThem() {
        assertEquals(KeyEvent.KEYCODE_C to false, KeyCodes.of("c"))
        assertEquals(KeyEvent.KEYCODE_C to true, KeyCodes.of("C"))
        assertEquals(KeyEvent.KEYCODE_7 to false, KeyCodes.of("7"))
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET to false, KeyCodes.of("["))
        assertEquals(KeyEvent.KEYCODE_BACKSLASH to true, KeyCodes.of("|"))
        assertEquals(KeyEvent.KEYCODE_GRAVE to true, KeyCodes.of("~"))
        assertNull("no key types it", KeyCodes.of("é"))
        assertNull(KeyCodes.of("ab"))
    }

    @Test
    fun everyEditCommandIsAKeyOrAFieldAction() {
        val actions = setOf(EditCommand.SELECT, EditCommand.SELECT_ALL, EditCommand.CUT, EditCommand.COPY, EditCommand.PASTE)
        for (command in EditCommand.entries) {
            assertEquals(command.name, command in actions, KeyCodes.of(command) == null)
        }
        assertEquals(KeyEvent.KEYCODE_ESCAPE, KeyCodes.of(EditCommand.ESCAPE))
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, KeyCodes.of(EditCommand.HOME))
    }

    @Test
    fun onlyMovingKeysSelectAndOnlySomeRepeat() {
        assertTrue(KeyCodes.moves(EditCommand.LEFT))
        assertTrue(KeyCodes.moves(EditCommand.END))
        assertFalse(KeyCodes.moves(EditCommand.TAB))
        assertFalse(KeyCodes.moves(EditCommand.FORWARD_DELETE))
        assertTrue(KeyCodes.repeats(EditCommand.FORWARD_DELETE))
        assertFalse(KeyCodes.repeats(EditCommand.ESCAPE))
    }
}
