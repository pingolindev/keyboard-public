// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView

/**
 * What is typed into the search over emoji, with a cursor. A tap or a drag
 * moves it. Not an EditText: the keyboard's window never has the focus one
 * needs, and the keys write into [EmojiQuery].
 */
@SuppressLint("ViewConstructor") // only EmojiSearchBar creates it, in code
class EmojiQueryView(context: Context, private val onCursor: (Int) -> Unit) : TextView(context) {
    private val caret = Paint()
    private val caretWidth = CARET_DP * resources.displayMetrics.density
    private var cursor = 0
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    fun setCaretColor(color: Int) {
        caret.color = color
    }

    /** Shows [typed] with the cursor at [cursor]. */
    fun show(typed: String, cursor: Int) {
        if (typed != text.toString()) text = typed
        this.cursor = cursor
        restartBlink()
    }

    /** On at once after every change, so it is seen to move. */
    private fun restartBlink() {
        removeCallbacks(blink)
        blinkOn = true
        invalidate()
        if (isShown) postDelayed(blink, BLINK_MS)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restartBlink() else removeCallbacks(blink)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val line = layout ?: return
        if (!blinkOn) return
        val x = totalPaddingLeft + line.getPrimaryHorizontal(cursor.coerceIn(0, line.text.length))
        val top = totalPaddingTop + line.getLineTop(0)
        val bottom = totalPaddingTop + line.getLineBottom(0)
        canvas.drawRect(x - caretWidth / 2, top.toFloat(), x + caretWidth / 2, bottom.toFloat(), caret)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onCursor(getOffsetForPosition(event.x, event.y))
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    /** A touch is a click too, for TalkBack. */
    override fun performClick(): Boolean = super.performClick()

    /** To TalkBack it is a field. */
    override fun getAccessibilityClassName(): CharSequence = EditText::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isEditable = true
        info.setTextSelection(cursor, cursor)
    }

    private companion object {
        const val BLINK_MS = 500L
        const val CARET_DP = 2f
    }
}
