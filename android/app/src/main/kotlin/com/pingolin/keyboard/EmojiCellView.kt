// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import kotlin.math.min

/** One emoji to tap, in the panel and among the search results. Read out by its name. */
@SuppressLint("ViewConstructor") // only created in code
class EmojiCellView(context: Context, theme: KeyboardTheme) : TextView(context) {
    init {
        // Large text enlarges the emoji as far as its place allows.
        val metrics = resources.displayMetrics
        val text = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, min(text, MAX_TEXT_DP * metrics.density))
        // Opaque: an emoji is drawn as see-through as the text color is.
        setTextColor(theme.label)
        gravity = Gravity.CENTER
        includeFontPadding = false
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(theme.pressed), null, ColorDrawable(Color.WHITE))
    }

    /** Shows [shown], a variant of [item], and types it on a tap. */
    fun bind(item: EmojiItem, shown: String, onPick: (String) -> Unit) {
        text = shown
        contentDescription = describe(item, item.tones.indexOf(shown) + 1)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPick(shown)
        }
    }

    private fun describe(item: EmojiItem, tone: Int): String {
        val name = item.name.ifEmpty { item.text }
        return if (tone == 0) name else context.getString(R.string.emoji_with_tone, name, context.getString(TONES[tone - 1]))
    }

    /** To TalkBack it is a button. */
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!isLongClickable) return
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_LONG_CLICK,
                context.getString(R.string.emoji_tones),
            ),
        )
    }

    companion object {
        /** A cell's width and height. */
        const val SIZE_DP = 48f
        private const val TEXT_SP = 25f
        private const val MAX_TEXT_DP = 32f

        /** Light to dark. */
        private val TONES = listOf(
            R.string.emoji_tone_light, R.string.emoji_tone_medium_light, R.string.emoji_tone_medium,
            R.string.emoji_tone_medium_dark, R.string.emoji_tone_dark,
        )
    }
}
