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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat

/**
 * The search over emoji, above the keys: what is typed, and what matches it.
 * The keys type here, not in the field.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class EmojiSearchBar(
    context: Context,
    private val recents: EmojiRecents,
    private val listener: Listener,
) : LinearLayout(context) {
    interface Listener {
        fun onBack()

        fun onEmoji(text: String)

        fun onClear()
    }

    private val density = resources.displayMetrics.density
    private val back = icon(R.drawable.ic_back, R.string.emoji_back) { listener.onBack() }
    private val query = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine()
        // A scrolling line would put right-to-left text out of sight.
        setHorizontallyScrolling(false)
        ellipsize = TextUtils.TruncateAt.START
        hint = context.getString(R.string.emoji_search)
        setPadding(dp(12f), 0, dp(12f), 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val clear = icon(R.drawable.ic_delete, R.string.emoji_clear_search) { listener.onClear() }
    private val typedRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(back, LayoutParams(dp(BUTTON_DP), MATCH))
        addView(query, LayoutParams(0, MATCH, 1f).apply { setMargins(0, dp(5f), 0, dp(5f)) })
        addView(clear, LayoutParams(dp(BUTTON_DP), MATCH))
    }
    private val results = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val scroll = Results(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(results, ViewGroup.LayoutParams(WRAP, MATCH))
    }
    private val nothing = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), 0, dp(16f), 0)
        text = context.getString(R.string.emoji_no_match)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val found = FrameLayout(context).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(nothing, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    private var theme = KeyboardTheme.LIGHT

    init {
        visibility = GONE
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(R.string.emoji_search))
        addView(typedRow)
        addView(found)
    }

    /** Shows the search in [theme]: two rows, or one where [width] pixels have room for it. */
    fun show(theme: KeyboardTheme, width: Int) {
        this.theme = theme
        setBackgroundColor(theme.background)
        for (button in listOf(back, clear)) button.imageTintList = ColorStateList.valueOf(theme.label)
        query.background = GradientDrawable().apply {
            setColor(theme.key)
            cornerRadius = 8 * density
        }
        query.setTextColor(theme.label)
        query.setHintTextColor(ColorUtils.blendARGB(theme.label, theme.key, 0.3f))
        nothing.setTextColor(theme.label)
        query.text = ""
        nothing.visibility = GONE
        results.removeAllViews() // made for other colors
        val row = dp(EmojiCellView.SIZE_DP)
        if (width >= dp(WIDE_DP)) {
            orientation = HORIZONTAL
            typedRow.layoutParams = LayoutParams(0, row, 2f)
            found.layoutParams = LayoutParams(0, row, 3f)
        } else {
            orientation = VERTICAL
            typedRow.layoutParams = LayoutParams(MATCH, row)
            found.layoutParams = LayoutParams(MATCH, row)
        }
        visibility = VISIBLE
    }

    /** What is [typed] so far, and the emoji to offer: what matches, or the recent ones before a search. */
    fun update(typed: String, cells: List<EmojiCell>) {
        query.text = typed
        val none = typed.isNotBlank() && cells.isEmpty()
        nothing.visibility = if (none) VISIBLE else GONE
        scroll.visibility = if (none) GONE else VISIBLE
        while (results.childCount < cells.size) {
            results.addView(EmojiCellView(context, theme), LayoutParams(dp(EmojiCellView.SIZE_DP), MATCH))
        }
        for (i in 0 until results.childCount) {
            val place = results.getChildAt(i) as EmojiCellView
            val cell = cells.getOrNull(i)
            place.visibility = if (cell == null) View.GONE else View.VISIBLE
            if (cell != null) place.bind(cell.item, recents.shown(cell)) { listener.onEmoji(it) }
        }
        scroll.showStart()
    }

    private fun icon(res: Int, description: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        background = null
        contentDescription = context.getString(description)
        setOnClickListener { action() }
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    /** The results. New ones show from their start: the right end in right-to-left languages. */
    private class Results(context: Context) : HorizontalScrollView(context) {
        private var toStart = false

        fun showStart() {
            toStart = true
            requestLayout()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (!toStart) return
            toStart = false
            val overflow = getChildAt(0).width - width
            scrollTo(if (layoutDirection == LAYOUT_DIRECTION_RTL) overflow else 0, 0)
        }
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val BUTTON_DP = 52f

        /** From this width on, the search and its results share a row (landscape). */
        const val WIDE_DP = 560f
    }
}
