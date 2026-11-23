// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import com.pingolin.keyboard.engine.EditCommand
import com.pingolin.keyboard.engine.Key
import com.pingolin.keyboard.engine.KeyAction
import com.pingolin.keyboard.engine.KeyCenter
import com.pingolin.keyboard.engine.KeyboardLayout
import com.pingolin.keyboard.engine.ModifierKey
import com.pingolin.keyboard.engine.initialLayoutId
import java.util.Locale

/**
 * Draws a layout from the engine and turns touches into key actions. Keys act
 * when the finger lifts, with the point where it lifted (null for TalkBack and
 * key repeat); Delete and the arrows act at once and repeat while held. Delete
 * held down deletes whole words, faster and faster.
 * Ctrl, Alt and Select show whether they are on or locked. [onKeysPlaced]
 * gets the letter keys' centers and sizes whenever they move, for the touch
 * model.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class KeyboardView(
    context: Context,
    private val onKey: (KeyAction, PointF?) -> Unit,
    private val onKeysPlaced: (List<KeyCenter>) -> Unit,
    /** A long press on Space: the next language. */
    private val onSpaceLongPress: () -> Unit,
    /** A glide: the finger slid from a letter over the keys (glide typing). */
    private val onGlide: (List<GlidePoint>) -> Unit,
    /** Delete held down: the next word goes. */
    private val onDeleteWord: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var rowHeight = ROW_DP * density
    private val gap = 3f * density
    private val corner = 6f * density

    private var backgroundColor = 0
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val functionKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val spacePaint = Paint(labelPaint)
    private val wordPaint = Paint(labelPaint)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5 * resources.displayMetrics.density
    }
    private val trail = Path()

    /** Glide typing: a touch that starts on a letter and slides becomes a glide. */
    private var glideEnabled = false
    private var glideFrom: PointF? = null
    private var glideKeyWidth = 0f
    private var gliding = false
    private val glidePoints = ArrayList<GlidePoint>()

    private var layout: KeyboardLayout? = null
    private var keys: List<Key> = emptyList()
    private var cells: List<KeyRect> = emptyList()
    private var drawRects: List<RectF> = emptyList()
    private var shift = ShiftState.OFF
    private var locale: Locale = Locale.ROOT
    private var spaceLabel = ""
    private var onKeys: Set<KeyAction> = emptySet()
    private var lockedKeys: Set<KeyAction> = emptySet()

    private var pressed = -1
    private var pointerId = -1
    private var longPressed = false
    private val touch = PointF()
    private val spaceHeld = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSpaceLongPress()
    }
    /** Delete held down deletes words; off where the field takes raw keys, as terminals do. */
    var holdDeletesWords = true

    private var repeating: KeyAction? = null
    private var repeated = 0
    private val repeat = object : Runnable {
        override fun run() {
            val action = repeating ?: return
            if (action == KeyAction.Backspace && holdDeletesWords) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onDeleteWord()
                postDelayed(this, KeyRepeat.wordInterval(repeated++))
            } else {
                onKey(action, null)
                postDelayed(this, KeyRepeat.INTERVAL_MS)
            }
        }
    }

    private val accessibility = KeyboardAccessibility(this)

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        setStyle(KeyboardTheme.LIGHT, 100)
    }

    /** The colors, and the height as a percentage of the usual one. */
    fun setStyle(theme: KeyboardTheme, heightPercent: Int) {
        backgroundColor = theme.background
        trailPaint.color = theme.accent
        trailPaint.alpha = 200
        keyPaint.color = theme.key
        functionKeyPaint.color = theme.functionKey
        pressedPaint.color = theme.pressed
        lockPaint.color = theme.label
        val scale = heightPercent.coerceIn(KeyboardSettings.MIN_HEIGHT, KeyboardSettings.MAX_HEIGHT) / 100f
        rowHeight = ROW_DP * density * scale
        // Labels grow and shrink with the keys, within limits.
        val text = scale.coerceIn(0.85f, 1.15f)
        for ((paint, sp) in listOf(labelPaint to 22f, spacePaint to 14f, wordPaint to 15f)) {
            paint.color = theme.label
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * text, resources.displayMetrics)
        }
        requestLayout()
        placeKeys()
        invalidate()
    }

    val keyCount: Int get() = keys.size

    /**
     * Shows [layout]. Letters are shown in upper case the way [locale] writes
     * them (Turkish i becomes İ); [spaceLabel] names the language on Space.
     */
    fun show(layout: KeyboardLayout, shift: ShiftState, locale: Locale = Locale.ROOT, spaceLabel: String = "") {
        removeCallbacks(repeat)
        removeCallbacks(spaceHeld)
        this.layout = layout
        keys = layout.rows.flatten()
        this.shift = shift
        this.locale = locale
        this.spaceLabel = spaceLabel
        pressed = -1
        pointerId = -1
        requestLayout()
        placeKeys()
        invalidate()
        accessibility.invalidateRoot()
    }

    fun setShift(shift: ShiftState) {
        if (this.shift == shift) return
        this.shift = shift
        invalidate()
        accessibility.invalidateRoot()
    }

    /** Glide typing on or off (off in passwords and other plain fields). */
    fun setGlideEnabled(on: Boolean) {
        glideEnabled = on
        if (!on) endGlide()
    }

    private fun endGlide() {
        glideFrom = null
        if (gliding) {
            gliding = false
            invalidate()
        }
        glidePoints.clear()
    }

    private fun addGlidePoint(x: Float, y: Float) {
        if (glidePoints.size < MAX_GLIDE_POINTS) glidePoints += GlidePoint(x, y)
    }

    /** Ctrl, Alt or Select keys that are [on] for the next key, or [locked]. */
    fun setKeyStates(on: Set<KeyAction>, locked: Set<KeyAction>) {
        if (on == onKeys && locked == lockedKeys) return
        onKeys = on
        lockedKeys = locked
        invalidate()
        accessibility.invalidateRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = layout?.rows?.size ?: DEFAULT_ROWS
        val height = (rows * rowHeight).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeKeys()
    }

    private fun placeKeys() {
        val rows = layout?.rows ?: return
        cells = KeyGeometry.place(rows.map { row -> row.map { it.width } }, width.toFloat(), rowHeight, paddingTop.toFloat())
        drawRects = cells.map { RectF(it.left + gap / 2, it.top + gap / 2, it.right - gap / 2, it.bottom - gap / 2) }
        if (cells.isNotEmpty()) onKeysPlaced(letterKeys())
    }

    private fun letterKeys(): List<KeyCenter> = keys.zip(cells).mapNotNull { (key, cell) ->
        val action = key.action as? KeyAction.Text ?: return@mapNotNull null
        KeyCenter(action.text, cell.centerX, cell.centerY, cell.right - cell.left, cell.bottom - cell.top)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        keys.forEachIndexed { index, key ->
            val rect = drawRects.getOrNull(index) ?: return@forEachIndexed
            val paint = when {
                index == pressed || key.action in onKeys || key.action in lockedKeys -> pressedPaint
                key.action is KeyAction.Text || key.action == KeyAction.Space -> keyPaint
                else -> functionKeyPaint
            }
            canvas.drawRoundRect(rect, corner, corner, paint)
            if (key.action in lockedKeys) {
                // Locked: a bar under the label, as caps lock has its own glyph.
                val bar = 2 * density
                canvas.drawRect(rect.centerX() - 6 * density, rect.bottom - 6 * density, rect.centerX() + 6 * density, rect.bottom - 6 * density + bar, lockPaint)
            }
            val label = labelFor(key)
            val labelPaint = when {
                key.action == KeyAction.Space -> spacePaint
                label.codePointCount(0, label.length) > 2 -> wordPaint
                else -> labelPaint
            }
            if (label.isNotEmpty()) {
                val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(label, rect.centerX(), baseline, labelPaint)
            }
        }
        if (gliding && glidePoints.size > 1) {
            trail.rewind()
            trail.moveTo(glidePoints[0].x, glidePoints[0].y)
            for (i in 1 until glidePoints.size) trail.lineTo(glidePoints[i].x, glidePoints[i].y)
            canvas.drawPath(trail, trailPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // keys are virtual views, see KeyboardAccessibility
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (gliding) {
                    // A second finger ends a glide without typing.
                    endGlide()
                    pressed = -1
                    pointerId = -1
                    return true
                }
                endGlide()
                // A second finger types the key under the first one straight away.
                release(commit = true)
                val index = event.actionIndex
                pointerId = event.getPointerId(index)
                touch.set(event.getX(index), event.getY(index))
                val key = indexAt(touch.x, touch.y)
                press(key)
                if (glideEnabled && isLetterKey(key)) {
                    glideFrom = PointF(touch.x, touch.y)
                    glideKeyWidth = cells[key].right - cells[key].left
                    addGlidePoint(touch.x, touch.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) touch.set(event.getX(index), event.getY(index))
                val from = glideFrom
                if (index >= 0 && from != null) {
                    for (h in 0 until event.historySize) addGlidePoint(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    addGlidePoint(touch.x, touch.y)
                    if (!gliding && Math.hypot((touch.x - from.x).toDouble(), (touch.y - from.y).toDouble()) > GLIDE_START * glideKeyWidth) {
                        gliding = true
                        removeCallbacks(repeat)
                        removeCallbacks(spaceHeld)
                        pressed = -1
                    }
                    if (gliding) {
                        invalidate()
                        return true
                    }
                }
                if (index >= 0 && pressed >= 0 && !repeats(keys[pressed].action)) {
                    val key = indexAt(touch.x, touch.y)
                    if (key != pressed) {
                        removeCallbacks(spaceHeld)
                        pressed = key
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    touch.set(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    if (gliding) {
                        addGlidePoint(touch.x, touch.y)
                        val points = glidePoints.toList()
                        endGlide()
                        pointerId = -1
                        onGlide(points)
                    } else {
                        endGlide()
                        release(commit = true)
                    }
                }
            MotionEvent.ACTION_CANCEL -> {
                endGlide()
                release(commit = false)
            }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onDetachedFromWindow() {
        removeCallbacks(repeat)
        removeCallbacks(spaceHeld)
        super.onDetachedFromWindow()
    }

    fun indexAt(x: Float, y: Float): Int = KeyGeometry.nearest(cells, x, y)

    /** Whether key [index] is Space, which switches language when there are several. */
    fun isLanguageKey(index: Int): Boolean = spaceLabel.isNotEmpty() && keys.getOrNull(index)?.action == KeyAction.Space

    /** The next language, as a long press on Space does. TalkBack uses this. */
    fun nextLanguage() = onSpaceLongPress()

    /** Acts as if key [index] were tapped. TalkBack uses this. */
    fun performKey(index: Int) {
        keys.getOrNull(index)?.let { onKey(it.action, null) }
    }

    fun keyBounds(index: Int, out: Rect) {
        val cell = cells.getOrNull(index)
        if (cell == null) out.set(0, 0, 1, 1) else out.set(cell.left.toInt(), cell.top.toInt(), cell.right.toInt(), cell.bottom.toInt())
    }

    /** What TalkBack reads for key [index]. */
    fun describe(index: Int): String {
        val key = keys.getOrNull(index) ?: return ""
        return when (val action = key.action) {
            is KeyAction.Text -> labelFor(key)
            KeyAction.Shift -> context.getString(if (shift == ShiftState.LOCKED) R.string.key_caps_lock else R.string.key_shift)
            KeyAction.Backspace -> context.getString(R.string.key_delete)
            KeyAction.Enter -> context.getString(R.string.key_enter)
            KeyAction.Space -> if (spaceLabel.isEmpty()) {
                context.getString(R.string.key_space)
            } else {
                context.getString(R.string.key_space_language, spaceLabel)
            }
            is KeyAction.SwitchLayout -> context.getString(
                when (action.layoutId) {
                    initialLayoutId(false) -> R.string.key_letters
                    initialLayoutId(true) -> R.string.key_symbols
                    else -> R.string.key_more_symbols
                },
            )
            is KeyAction.Edit -> withState(context.getString(editName(action.command)), action)
            is KeyAction.Modifier -> withState(
                context.getString(if (action.key == ModifierKey.CTRL) R.string.key_ctrl else R.string.key_alt),
                action,
            )
        }
    }

    private fun withState(name: String, action: KeyAction): String = when (action) {
        in lockedKeys -> context.getString(R.string.key_state_locked, name)
        in onKeys -> context.getString(R.string.key_state_on, name)
        else -> name
    }

    private fun editName(command: EditCommand): Int = when (command) {
        EditCommand.LEFT -> R.string.key_left
        EditCommand.RIGHT -> R.string.key_right
        EditCommand.UP -> R.string.key_up
        EditCommand.DOWN -> R.string.key_down
        EditCommand.HOME -> R.string.key_home
        EditCommand.END -> R.string.key_end
        EditCommand.PAGE_UP -> R.string.key_page_up
        EditCommand.PAGE_DOWN -> R.string.key_page_down
        EditCommand.FORWARD_DELETE -> R.string.key_forward_delete
        EditCommand.ESCAPE -> R.string.key_escape
        EditCommand.TAB -> R.string.key_tab
        EditCommand.SELECT -> R.string.key_select
        EditCommand.SELECT_ALL -> R.string.key_select_all
        EditCommand.CUT -> R.string.key_cut
        EditCommand.COPY -> R.string.key_copy
        EditCommand.PASTE -> R.string.key_paste
    }

    private fun editLabel(command: EditCommand): String = when (command) {
        EditCommand.LEFT -> "←"
        EditCommand.RIGHT -> "→"
        EditCommand.UP -> "↑"
        EditCommand.DOWN -> "↓"
        EditCommand.FORWARD_DELETE -> "⌦"
        EditCommand.PAGE_UP -> context.getString(R.string.label_page_up)
        EditCommand.PAGE_DOWN -> context.getString(R.string.label_page_down)
        EditCommand.ESCAPE -> context.getString(R.string.label_escape)
        else -> context.getString(editName(command))
    }

    private fun labelFor(key: Key): String = when (val action = key.action) {
        is KeyAction.Text -> if (shift == ShiftState.OFF) key.label else upperCase(key.label, locale)
        KeyAction.Space -> spaceLabel
        KeyAction.Shift -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
        KeyAction.Backspace -> "⌫"
        KeyAction.Enter -> "⏎"
        is KeyAction.Edit -> editLabel(action.command)
        is KeyAction.Modifier -> context.getString(if (action.key == ModifierKey.CTRL) R.string.label_ctrl else R.string.label_alt)
        is KeyAction.SwitchLayout -> key.label
    }

    /** A key that types a letter, where a glide may start. */
    private fun isLetterKey(index: Int): Boolean {
        val action = keys.getOrNull(index)?.action as? KeyAction.Text ?: return false
        return action.text.codePointCount(0, action.text.length) == 1 && Character.isLetter(action.text.codePointAt(0))
    }

    private fun repeats(action: KeyAction): Boolean =
        action == KeyAction.Backspace || (action is KeyAction.Edit && KeyCodes.repeats(action.command))

    private fun press(index: Int) {
        pressed = index
        longPressed = false
        if (index < 0) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (isLanguageKey(index)) postDelayed(spaceHeld, LONG_PRESS_MS)
        val action = keys[index].action
        if (repeats(action)) {
            onKey(action, null)
            repeating = action
            repeated = 0
            postDelayed(repeat, KeyRepeat.DELAY_MS)
        }
        invalidate()
    }

    private fun release(commit: Boolean) {
        removeCallbacks(repeat)
        removeCallbacks(spaceHeld)
        val index = pressed
        pressed = -1
        pointerId = -1
        if (index < 0) return
        val action = keys[index].action
        val held = longPressed && action == KeyAction.Space
        longPressed = false
        if (commit && !repeats(action) && !held) onKey(action, PointF(touch.x, touch.y))
        invalidate()
    }

    private companion object {
        /** A touch that slides this many key widths from a letter is a glide. */
        const val GLIDE_START = 0.8
        const val MAX_GLIDE_POINTS = 2_000

        /** A row's height at 100%. */
        const val ROW_DP = 54f
        const val DEFAULT_ROWS = 4
        const val LONG_PRESS_MS = 450L
    }
}
