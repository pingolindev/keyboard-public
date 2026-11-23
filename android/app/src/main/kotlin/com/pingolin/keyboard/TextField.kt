// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.view.inputmethod.InputConnection

/**
 * What the input logic does to the text field. The app passes the field's
 * InputConnection; tests pass a fake editor.
 */
interface TextField {
    /** Shows [text] as the word being typed, replacing the previous one. */
    fun setComposingText(text: String)

    /** Puts [text] in the field, replacing the word being typed if any. */
    fun commitText(text: String)

    /** Leaves the word being typed in the field as it is. */
    fun finishComposingText()

    fun deleteBeforeCursor(count: Int)

    fun textBeforeCursor(count: Int): String?

    fun textAfterCursor(count: Int): String?

    /** Makes the text between [start] and [end] the word being typed. */
    fun setComposingRegion(start: Int, end: Int)

    /** A Delete key press, for what the field handles itself (a selection, an emoji). */
    fun sendBackspace()

    fun beginBatchEdit()

    fun endBatchEdit()
}

class ConnectionTextField(
    private val connection: () -> InputConnection?,
    private val backspace: () -> Unit,
) : TextField {
    override fun setComposingText(text: String) {
        connection()?.setComposingText(text, 1)
    }

    override fun commitText(text: String) {
        connection()?.commitText(text, 1)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun deleteBeforeCursor(count: Int) {
        connection()?.deleteSurroundingText(count, 0)
    }

    override fun textBeforeCursor(count: Int): String? = connection()?.getTextBeforeCursor(count, 0)?.toString()

    override fun textAfterCursor(count: Int): String? = connection()?.getTextAfterCursor(count, 0)?.toString()

    override fun setComposingRegion(start: Int, end: Int) {
        connection()?.setComposingRegion(start, end)
    }

    override fun sendBackspace() = backspace()

    override fun beginBatchEdit() {
        connection()?.beginBatchEdit()
    }

    override fun endBatchEdit() {
        connection()?.endBatchEdit()
    }
}
