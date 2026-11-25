// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import kotlin.math.min

/**
 * Every emoji, in place of the strip and the keys: the recent ones, then each
 * group. A tap types an emoji and a long press offers its skin tones.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class EmojiPanel(
    context: Context,
    private val recents: EmojiRecents,
    private val listener: Listener,
) : LinearLayout(context) {
    interface Listener {
        fun onEmoji(text: String)

        /** A skin tone was picked for [base]: 0 for none, 1 (light) to 5 (dark). */
        fun onTone(base: String, tone: Int)

        fun onClearRecent()

        fun onSearch()

        fun onDelete()

        fun onLetters()
    }

    private val density = resources.displayMetrics.density
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(Color.TRANSPARENT)
    }
    private val toneBar = LinearLayout(context).apply { orientation = HORIZONTAL }

    /** The skin tones over the list: a tap anywhere else closes them. Placed from the left in any language. */
    private val toneLayer = FrameLayout(context).apply {
        layoutDirection = LAYOUT_DIRECTION_LTR
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(R.string.emoji_tones))
        val scrim = View(context).apply {
            contentDescription = context.getString(R.string.emoji_tones_close)
            setOnClickListener { hideTones() }
        }
        addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(toneBar, FrameLayout.LayoutParams(WRAP, WRAP))
    }
    private val letters = Button(context).apply {
        text = "ABC"
        contentDescription = context.getString(R.string.key_letters)
        setOnClickListener { listener.onLetters() }
    }
    private val search = Button(context).apply {
        text = context.getString(R.string.emoji_search)
        isAllCaps = false
        setOnClickListener { listener.onSearch() }
    }
    private val delete = Button(context).apply {
        text = "⌫"
        contentDescription = context.getString(R.string.key_delete)
        setOnClickListener { listener.onDelete() }
        setOnTouchListener(Held(this) { listener.onDelete() })
    }
    private val adapter = Rows()
    private var rows: List<EmojiRow> = emptyList()
    private var tables: EmojiData.Tables? = null
    private var theme = KeyboardTheme.LIGHT
    private var columns = MIN_COLUMNS

    /** The section the tabs mark as shown. */
    private var section = EmojiRows.RECENT

    init {
        orientation = VERTICAL
        visibility = GONE
        // TalkBack says what came in the keys' place.
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(R.string.key_emoji))
        for (group in EmojiRows.SECTIONS) tabs.addView(newTab(group), LayoutParams(0, MATCH, 1f))
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = markSection()
        })
        val middle = FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(toneLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            for ((button, weight) in listOf(letters to 1.5f, search to 7f, delete to 1.5f)) {
                // Drawn like the keys, without a button's own shadow and size.
                button.stateListAnimator = null
                button.minWidth = 0
                button.minimumWidth = 0
                val gap = dp(GAP_DP)
                addView(button, LayoutParams(0, MATCH, weight).apply { setMargins(gap, gap, gap, gap) })
            }
        }
        addView(tabs, LayoutParams(MATCH, dp(BAR_DP)))
        addView(middle, LayoutParams(MATCH, 0, 1f))
        addView(bottom, LayoutParams(MATCH, dp(BAR_DP)))
        hideTones()
    }

    /** Shows the panel in [theme], [width] by [height] pixels: the strip's and the keys' place. */
    fun show(theme: KeyboardTheme, width: Int, height: Int) {
        this.theme = theme
        columns = maxOf(MIN_COLUMNS, width / dp(EmojiCellView.SIZE_DP))
        setBackgroundColor(theme.background)
        for (button in listOf(letters, search, delete)) {
            button.setTextColor(theme.label)
            button.background = RippleDrawable(ColorStateList.valueOf(theme.pressed), rounded(theme.functionKey), null)
            // Large text enlarges it as far as the row allows.
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP, resources.displayMetrics)
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(size, MAX_BUTTON_DP * density))
            button.setPadding(dp(8f), 0, dp(8f), 0)
        }
        toneBar.background = rounded(theme.functionKey).apply {
            setStroke(dp(1f), ColorUtils.setAlphaComponent(theme.label, EDGE_ALPHA))
        }
        layoutParams = (layoutParams ?: LayoutParams(MATCH, height)).also { it.height = height }
        // Rows made for other colors or columns are not used again.
        list.adapter = adapter
        rebuild()
        visibility = VISIBLE
    }

    /** The emoji to list: read by now, or read again for other languages. */
    fun setTables(tables: EmojiData.Tables) {
        if (tables === this.tables) return
        this.tables = tables
        rebuild()
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = CORNER_DP * density
    }

    private fun rebuild() {
        hideTones()
        val shown = tables
        rows = if (shown == null) emptyList() else EmojiRows.build(shown.recent(recents), shown.items, columns)
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        val there = rows.mapTo(HashSet()) { it.group }
        EmojiRows.SECTIONS.forEachIndexed { i, group ->
            val tab = tabs.getChildAt(i)
            tab.isEnabled = group in there
            tab.alpha = if (group in there) 1f else OFF_ALPHA
        }
        section = NONE
        markSection()
    }

    private fun newTab(group: Int): View = Tab(context).apply {
        text = ICONS.getValue(group)
        contentDescription = context.getString(nameOf(group))
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        setTextColor(Color.BLACK) // opaque, see EmojiCellView
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = true
        setOnClickListener {
            hideTones()
            val at = rows.indexOfFirst { it.group == group }
            if (at >= 0) list.setSelection(at)
        }
    }

    /** Marks the tab of the section at the top of the list. */
    private fun markSection() {
        val group = rows.getOrNull(list.firstVisiblePosition)?.group ?: NONE
        if (group == section) return
        section = group
        EmojiRows.SECTIONS.forEachIndexed { i, g ->
            val tab = tabs.getChildAt(i)
            tab.isSelected = g == group
            tab.background = if (g == group) rounded(theme.key) else null
        }
    }

    private fun showTones(item: EmojiItem, anchor: View) {
        toneBar.removeAllViews()
        (listOf(item.text) + item.tones).forEachIndexed { tone, text ->
            val choice = EmojiCellView(context, theme)
            choice.bind(item, text) {
                hideTones()
                listener.onTone(item.text, tone)
                listener.onEmoji(it)
                adapter.notifyDataSetChanged()
            }
            toneBar.addView(choice, LayoutParams(dp(EmojiCellView.SIZE_DP), dp(EmojiCellView.SIZE_DP)))
        }
        val size = dp(EmojiCellView.SIZE_DP)
        val width = size * toneBar.childCount
        val cell = IntArray(2)
        val area = IntArray(2)
        anchor.getLocationInWindow(cell)
        list.getLocationInWindow(area)
        val left = cell[0] - area[0] + anchor.width / 2 - width / 2
        val above = cell[1] - area[1] - size
        val top = if (above >= 0) above else cell[1] - area[1] + anchor.height
        toneBar.translationX = left.coerceIn(0, maxOf(0, list.width - width)).toFloat()
        toneBar.translationY = top.coerceIn(0, maxOf(0, list.height - size)).toFloat()
        toneLayer.visibility = VISIBLE
        // TalkBack goes to the skin tones, not to the emoji behind them.
        list.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun hideTones() {
        toneLayer.visibility = GONE
        list.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun nameOf(group: Int): Int = when (group) {
        EmojiRows.RECENT -> R.string.emoji_recent
        0 -> R.string.emoji_smileys
        1 -> R.string.emoji_people
        2 -> R.string.emoji_animals
        3 -> R.string.emoji_food
        4 -> R.string.emoji_travel
        5 -> R.string.emoji_activities
        6 -> R.string.emoji_objects
        7 -> R.string.emoji_symbols
        else -> R.string.emoji_flags
    }

    /** The list: a row with a section's name (and Clear, for the recent ones), or a row of emoji. */
    private inner class Rows : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (rows[position] is EmojiRow.Title) 0 else 1

        // The emoji in a row are tapped, not the row.
        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is EmojiRow.Title -> (convertView as? LinearLayout ?: newTitle()).also { bind(it, row) }
            is EmojiRow.Cells -> (convertView as? LinearLayout ?: newCells()).also { bind(it, row) }
        }

        private fun newTitle() = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val name = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
                ViewCompat.setAccessibilityHeading(this, true)
            }
            val clear = Button(context).apply {
                text = context.getString(R.string.emoji_clear_recent)
                isAllCaps = false
                background = null
                setOnClickListener {
                    listener.onClearRecent()
                    rebuild()
                }
            }
            addView(name, LayoutParams(0, WRAP, 1f))
            addView(clear, LayoutParams(WRAP, dp(BAR_DP)))
        }

        private fun bind(view: LinearLayout, row: EmojiRow.Title) {
            (view.getChildAt(0) as TextView).run {
                text = context.getString(nameOf(row.group))
                setTextColor(theme.label)
            }
            (view.getChildAt(1) as Button).run {
                setTextColor(theme.label)
                visibility = if (row.group == EmojiRows.RECENT) VISIBLE else GONE
            }
        }

        private fun newCells() = LinearLayout(context).apply {
            orientation = HORIZONTAL
            repeat(columns) {
                addView(EmojiCellView(context, theme), LayoutParams(0, dp(EmojiCellView.SIZE_DP), 1f))
            }
        }

        private fun bind(view: LinearLayout, row: EmojiRow.Cells) {
            for (i in 0 until view.childCount) {
                val place = view.getChildAt(i) as EmojiCellView
                val cell = row.cells.getOrNull(i)
                place.visibility = if (cell == null) INVISIBLE else VISIBLE
                if (cell == null) continue
                place.bind(cell.item, recents.shown(cell)) { listener.onEmoji(it) }
                if (cell.item.tones.isEmpty()) {
                    place.setOnLongClickListener(null)
                    place.isLongClickable = false
                } else {
                    place.setOnLongClickListener { v ->
                        showTones(cell.item, v)
                        true
                    }
                }
            }
        }
    }

    /** To TalkBack a tab is a button. */
    private class Tab(context: Context) : TextView(context) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    /** Repeats a button held down, as Delete among the keys does. */
    private class Held(private val button: View, private val action: () -> Unit) : OnTouchListener, Runnable {
        private var repeated = false

        override fun run() {
            if (!button.isShown) return stop()
            repeated = true
            action()
            button.postDelayed(this, KeyRepeat.INTERVAL_MS)
        }

        @SuppressLint("ClickableViewAccessibility") // a short press is a click
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeated = false
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    v.postDelayed(this, KeyRepeat.DELAY_MS)
                }
                MotionEvent.ACTION_UP -> {
                    stop()
                    if (!repeated) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> stop()
            }
            return true
        }

        private fun stop() {
            button.removeCallbacks(this)
            button.isPressed = false
        }
    }

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val NONE = -2
        const val MIN_COLUMNS = 6
        const val OFF_ALPHA = 0.35f
        const val CORNER_DP = 6f

        /** Half the space between two buttons, as between two keys. */
        const val GAP_DP = 1.5f
        const val EDGE_ALPHA = 70
        const val BUTTON_SP = 16f
        const val MAX_BUTTON_DP = 20f

        /** The height of the tabs and of the buttons. */
        const val BAR_DP = 48f

        /** A tab shows one of its emoji. */
        val ICONS = mapOf(
            EmojiRows.RECENT to "🕘", 0 to "😀", 1 to "👋", 2 to "🐻", 3 to "🍔", 4 to "✈️",
            5 to "⚽", 6 to "💡", 7 to "💟", 8 to "🏁",
        )
    }
}
