// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The clipboard history in place of the keys: a tap pastes a clip, the pin
 * keeps it, the cross removes it. Clear removes the unpinned clips.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class ClipboardPanel(
    context: Context,
    private val onPaste: (String) -> Unit,
    private val onLetters: () -> Unit,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val letters = Button(context).apply {
        text = "ABC"
        contentDescription = context.getString(R.string.key_letters)
        setOnClickListener { onLetters() }
    }
    private val clear = Button(context).apply {
        text = context.getString(R.string.clipboard_clear)
        setOnClickListener {
            history?.clear(pinnedToo = false)
            onChanged()
            rebuild()
        }
    }
    private var history: ClipboardHistory? = null
    private var theme = KeyboardTheme.LIGHT

    init {
        orientation = VERTICAL
        header.addView(letters, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(View(context), LayoutParams(0, 1, 1f))
        header.addView(clear, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            ScrollView(context).apply { addView(list) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        visibility = GONE
    }

    /** Shows [history] in [theme], [height] pixels tall (the keys' height). */
    fun show(history: ClipboardHistory, theme: KeyboardTheme, height: Int) {
        this.history = history
        this.theme = theme
        setBackgroundColor(theme.background)
        for (button in listOf(letters, clear)) {
            button.setTextColor(theme.label)
            button.backgroundTintList = ColorStateList.valueOf(theme.functionKey)
        }
        layoutParams = (layoutParams ?: LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)).also { it.height = height }
        rebuild()
        visibility = VISIBLE
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun rebuild() {
        list.removeAllViews()
        val clips = history?.clips().orEmpty()
        clear.isEnabled = clips.any { !it.pinned }
        if (clips.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clipboard_empty)
                    setTextColor(theme.label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
                },
            )
            return
        }
        for (clip in clips) list.addView(row(clip))
    }

    private fun row(clip: ClipboardHistory.Clip): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.key)
                cornerRadius = 8 * density
            }
        }
        val text = TextView(context).apply {
            text = clip.text
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(theme.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
            minHeight = dp(48f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onPaste(clip.text) }
        }
        row.addView(text, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            icon(
                R.drawable.ic_pin,
                if (clip.pinned) R.string.clipboard_unpin else R.string.clipboard_pin,
                if (clip.pinned) theme.accent else theme.label,
            ) {
                history?.pin(clip.text, !clip.pinned)
                onChanged()
                rebuild()
            },
        )
        row.addView(
            icon(R.drawable.ic_delete, R.string.clipboard_delete, theme.label) {
                history?.remove(clip.text)
                onChanged()
                rebuild()
            },
        )
        return LinearLayout(context).apply {
            setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun icon(res: Int, description: Int, color: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        background = null
        contentDescription = context.getString(description)
        minimumWidth = dp(48f)
        minimumHeight = dp(48f)
        setOnClickListener { action() }
    }
}
