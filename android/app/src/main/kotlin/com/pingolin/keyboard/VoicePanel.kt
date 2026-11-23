// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Voice typing in place of the keys: what is happening, the loudness, and what can be done. */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class VoicePanel(
    context: Context,
    private val onLetters: () -> Unit,
    private val onAction: (State) -> Unit,
) : LinearLayout(context) {
    enum class State(val message: Int, val action: Int?) {
        LISTENING(R.string.voice_listening, R.string.voice_stop),
        WRITING(R.string.voice_writing, null),
        NOTHING(R.string.voice_nothing, R.string.voice_again),
        FAILED(R.string.voice_failed, R.string.voice_again),
        NO_MODEL(R.string.voice_no_model, R.string.voice_set_up),
        NO_MIC(R.string.voice_no_mic, R.string.voice_allow),
        LOCKED(R.string.voice_locked, null),
    }

    var state = State.LISTENING
        private set
    private val density = resources.displayMetrics.density
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        // TalkBack reads each new state.
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val level = LevelView(context)
    private val letters = Button(context).apply {
        text = "ABC"
        contentDescription = context.getString(R.string.key_letters)
        setOnClickListener { onLetters() }
    }
    private val action = Button(context).apply { setOnClickListener { onAction(state) } }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (16 * density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(level, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (8 * density).toInt()).apply { bottomMargin = pad })
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(letters, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(action, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(buttons, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /** Shows the panel in [theme], [height] pixels tall (the keys' height). */
    fun show(theme: KeyboardTheme, height: Int) {
        setBackgroundColor(theme.background)
        status.setTextColor(theme.label)
        level.color = theme.accent
        for (button in listOf(letters, action)) {
            button.setTextColor(theme.label)
            button.backgroundTintList = ColorStateList.valueOf(theme.functionKey)
        }
        layoutParams = (layoutParams ?: LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)).also { it.height = height }
        visibility = VISIBLE
    }

    fun setState(state: State) {
        this.state = state
        status.text = context.getString(state.message)
        action.visibility = if (state.action == null) INVISIBLE else VISIBLE
        state.action?.let { action.text = context.getString(it) }
        level.visibility = if (state == State.LISTENING) VISIBLE else INVISIBLE
        if (state != State.LISTENING) level.level = 0f
    }

    fun setLevel(value: Float) {
        level.level = value
    }

    /** A bar as long as the voice is loud. */
    private class LevelView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var color: Int
            get() = paint.color
            set(value) {
                paint.color = value
                invalidate()
            }
        var level = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val w = width * level
            val left = (width - w) / 2
            canvas.drawRoundRect(left, 0f, left + w, height.toFloat(), height / 2f, height / 2f, paint)
        }
    }
}
