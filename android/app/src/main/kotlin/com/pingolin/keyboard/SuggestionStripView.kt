// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/** What the tools row of the strip offers. */
enum class Tool(val icon: Int, val label: Int, val checkable: Boolean) {
    VOICE(R.drawable.ic_mic, R.string.tool_voice, false),
    INCOGNITO(R.drawable.ic_incognito, R.string.tool_incognito, true),
    TEXT_EDITING(R.drawable.ic_text_editing, R.string.tool_text_editing, false),
    TERMINAL(R.drawable.ic_terminal, R.string.tool_terminal, true),
    CLIPBOARD(R.drawable.ic_clipboard, R.string.tool_clipboard, false),
    SETTINGS(R.drawable.ic_settings, R.string.tool_settings, false),
}

/**
 * The strip above the keys: a tools button, then three words, the middle one
 * being what a space will do. An autocorrection shows in bold; the typed word
 * it would replace shows in quotes, so a tap keeps it. The tools button swaps
 * the words for the tools row. In incognito the strip is tinted and the
 * button shows the incognito mark.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class SuggestionStripView(
    context: Context,
    private val onPick: (Slot) -> Unit,
    private val onTool: (Tool) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val stripHeight = (44 * density).toInt()
    private val padding = 8 * density
    private val iconSize = (24 * density).toInt()
    private var strip = Strip.EMPTY
    private var toolsOpen = false
    private var incognito = false
    private var checked: Set<Tool> = emptySet()
    private var pressed = NONE
    private val rect = RectF()

    private var labelColor = 0
    private var accentColor = 0
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
    }
    private val boldPaint = TextPaint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD }
    private val dividerPaint = Paint()
    private val pressedPaint = Paint()
    private val checkedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundColor = 0
    private var incognitoColor = 0
    private val icons = HashMap<Int, Drawable>()

    private val accessibility = StripAccessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        setTheme(KeyboardTheme.LIGHT)
    }

    fun setTheme(theme: KeyboardTheme) {
        labelColor = theme.label
        accentColor = theme.accent
        textPaint.color = theme.label
        boldPaint.color = theme.label
        dividerPaint.color = theme.functionKey
        pressedPaint.color = theme.pressed
        checkedPaint.color = theme.functionKey
        backgroundColor = theme.background
        incognitoColor = theme.incognito
        invalidate()
    }

    fun show(strip: Strip) {
        if (strip == this.strip) return
        this.strip = strip
        if (!toolsOpen) pressed = NONE
        changed()
    }

    /** Marks the strip: nothing is learned here and learned words are not used. */
    fun setIncognito(on: Boolean) {
        if (incognito == on) return
        incognito = on
        changed()
    }

    /** The tools shown as switched on. */
    fun setChecked(tools: Set<Tool>) {
        if (checked == tools) return
        checked = tools
        changed()
    }

    fun closeTools() {
        if (!toolsOpen) return
        toolsOpen = false
        pressed = NONE
        changed()
    }

    private fun changed() {
        invalidate()
        accessibility.invalidateRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), stripHeight)
    }

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun cells(): Int = if (toolsOpen) TOOLS.size else SLOTS

    /** Item [id]: [BUTTON], or cell id - 1 counted from the left of the screen. */
    private fun bounds(id: Int, out: RectF) {
        val button = height.toFloat()
        if (id == BUTTON) {
            if (rtl) out.set(width - button, 0f, width.toFloat(), height.toFloat()) else out.set(0f, 0f, button, height.toFloat())
            return
        }
        val start = if (rtl) 0f else button
        val w = (width - button) / cells()
        val left = start + (id - 1) * w
        out.set(left, 0f, left + w, height.toFloat())
    }

    private fun itemAt(x: Float): Int {
        val button = height.toFloat()
        if (if (rtl) x >= width - button else x < button) return BUTTON
        val start = if (rtl) 0f else button
        val w = (width - button) / cells()
        return 1 + ((x - start) / w).toInt().coerceIn(0, cells() - 1)
    }

    /** The word in cell [id]; the middle stays in the middle. */
    private fun slotAt(id: Int): Slot? {
        if (toolsOpen || id == BUTTON) return null
        val index = id - 1
        return strip.slots.getOrNull(if (rtl) SLOTS - 1 - index else index)
    }

    /** The tool in cell [id], the first next to the button. */
    private fun toolAt(id: Int): Tool? {
        if (!toolsOpen || id == BUTTON) return null
        val index = id - 1
        return TOOLS.getOrNull(if (rtl) TOOLS.size - 1 - index else index)
    }

    private fun hasItem(id: Int): Boolean = id == BUTTON || slotAt(id) != null || toolAt(id) != null

    private fun buttonIcon(): Int = when {
        toolsOpen -> R.drawable.ic_tools_close
        incognito -> R.drawable.ic_incognito
        else -> R.drawable.ic_tools_open
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (incognito) incognitoColor else backgroundColor)
        bounds(BUTTON, rect)
        if (pressed == BUTTON) canvas.drawRect(rect, pressedPaint)
        drawIcon(canvas, buttonIcon(), rect, labelColor)
        for (id in 1..cells()) {
            bounds(id, rect)
            if (id == pressed && hasItem(id)) canvas.drawRect(rect, pressedPaint)
            val tool = toolAt(id)
            if (tool != null) {
                val on = tool in checked
                if (on) {
                    val inset = 4 * density
                    canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, 8 * density, 8 * density, checkedPaint)
                }
                drawIcon(canvas, tool.icon, rect, if (on) accentColor else labelColor)
                continue
            }
            if (id > 1) canvas.drawRect(rect.left, height * 0.25f, rect.left + density, height * 0.75f, dividerPaint)
            val slot = slotAt(id) ?: continue
            val paint = if (slot.role == Slot.Role.AUTOCORRECT) boldPaint else textPaint
            val label = TextUtils.ellipsize(display(slot), paint, rect.width() - 2 * padding, TextUtils.TruncateAt.END)
            val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, 0, label.length, rect.centerX(), baseline, paint)
        }
    }

    private fun drawIcon(canvas: Canvas, res: Int, area: RectF, color: Int) {
        val icon = icons.getOrPut(res) { requireNotNull(context.getDrawable(res)).mutate() }
        val left = (area.centerX() - iconSize / 2f).toInt()
        val top = (area.centerY() - iconSize / 2f).toInt()
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.setTint(color)
        icon.draw(canvas)
    }

    private fun display(slot: Slot): String =
        if (slot.role == Slot.Role.TYPED) "“${slot.text}”" else slot.text

    private fun describe(id: Int): String {
        if (id == BUTTON) {
            return context.getString(
                when {
                    toolsOpen -> R.string.tools_close
                    incognito -> R.string.tools_incognito
                    else -> R.string.tools
                },
            )
        }
        toolAt(id)?.let { return context.getString(it.label) }
        return slotAt(id)?.let { display(it) } ?: ""
    }

    private fun activate(id: Int) {
        if (id == BUTTON) {
            toolsOpen = !toolsOpen
            changed()
            return
        }
        val tool = toolAt(id)
        if (tool != null) {
            if (!tool.checkable) closeTools()
            onTool(tool)
            return
        }
        slotAt(id)?.let(onPick)
    }

    @SuppressLint("ClickableViewAccessibility") // items are virtual views, see StripAccessibility
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val item = itemAt(event.x)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = item
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (item != pressed && pressed != NONE) {
                pressed = NONE
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val hit = item == pressed && hasItem(item)
                pressed = NONE
                invalidate()
                if (hit) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activate(item)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = NONE
                invalidate()
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private inner class StripAccessibility : ExploreByTouchHelper(this@SuggestionStripView) {
        private val box = Rect()
        private val area = RectF()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val item = itemAt(x)
            return if (hasItem(item)) item else INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.add(BUTTON)
            for (id in 1..cells()) if (hasItem(id)) virtualViewIds.add(id)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            node.className = Button::class.java.name
            node.contentDescription = describe(virtualViewId)
            toolAt(virtualViewId)?.takeIf { it.checkable }?.let {
                node.isCheckable = true
                node.isChecked = it in checked
            }
            bounds(virtualViewId, area)
            box.set(area.left.toInt(), 0, area.right.toInt(), height.coerceAtLeast(1))
            @Suppress("DEPRECATION") // ExploreByTouchHelper still needs bounds in the parent
            node.setBoundsInParent(box)
            if (hasItem(virtualViewId)) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || !hasItem(virtualViewId)) return false
            activate(virtualViewId)
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    private companion object {
        const val SLOTS = 3
        const val BUTTON = 0
        const val NONE = -1
        val TOOLS = Tool.entries
    }
}
