// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * A password manager's suggestions for the field (Android 11 and later), as
 * chips over the word suggestions. The autofill service draws each chip in
 * its own process; the keyboard only places them and never sees what they
 * hold. A tap on a chip goes to the service, which fills the field.
 */
@SuppressLint("ViewConstructor") // only KeyboardService creates it, in code
class AutofillBar(context: Context, private val chipHeight: Int) : HorizontalScrollView(context) {
    private val density = resources.displayMetrics.density
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    /** Bumped on every change, so chips inflated for an older answer are dropped. */
    private var generation = 0

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    @RequiresApi(Build.VERSION_CODES.R)
    fun show(suggestions: List<InlineSuggestion>) {
        val current = ++generation
        row.removeAllViews()
        if (suggestions.isEmpty()) {
            visibility = GONE
            return
        }
        val views = arrayOfNulls<View>(suggestions.size)
        var waiting = suggestions.size
        // Each chip comes back sized to what the password manager drew, at
        // the height asked for in request().
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, chipHeight)
        suggestions.forEachIndexed { i, suggestion ->
            suggestion.inflate(context, size, context.mainExecutor) { view ->
                if (current != generation) return@inflate
                views[i] = view
                if (--waiting > 0) return@inflate
                val gap = (4 * density).toInt()
                for (index in AutofillOrder.order(suggestions.map { it.info.isPinned })) {
                    val chip = views[index] ?: continue
                    // Keep that size. Set to wrap its content, a chip takes all
                    // the room it is offered: the first one filled the row and
                    // pushed the keys off the screen.
                    val drawn = chip.layoutParams
                    val params = LinearLayout.LayoutParams(
                        drawn?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                        drawn?.height ?: chipHeight,
                    )
                    row.addView(chip, params.apply { marginStart = gap })
                }
                visibility = if (row.childCount > 0) VISIBLE else GONE
            }
        }
    }

    fun hide() {
        generation++
        row.removeAllViews()
        visibility = GONE
    }

    companion object {
        private const val MAX_SUGGESTIONS = 6

        /**
         * What the keyboard asks the autofill service for: up to six chips of
         * the strip's height. The style builders' setters are public, but come
         * from a base class the library marks restricted, which lint misreads.
         */
        @SuppressLint("RestrictedApi")
        @RequiresApi(Build.VERSION_CODES.R)
        fun request(context: Context, height: Int, maxWidth: Int, theme: KeyboardTheme): InlineSuggestionsRequest {
            val label = theme.label
            val px = { dp: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt() }
            val chip = Icon.createWithResource(context, R.drawable.chip_background).setTint(theme.key)
            val style = InlineSuggestionUi.newStyleBuilder()
                .setSingleIconChipStyle(ViewStyle.Builder().setBackground(chip).setPadding(0, 0, 0, 0).build())
                .setChipStyle(ViewStyle.Builder().setBackground(chip).setPadding(px(12f), 0, px(12f), 0).build())
                .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                .setTitleStyle(TextViewStyle.Builder().setLayoutMargin(px(4f), 0, px(4f), 0).setTextColor(label).setTextSize(15f).build())
                .setSubtitleStyle(TextViewStyle.Builder().setLayoutMargin(0, 0, px(4f), 0).setTextColor(label).setTextSize(13f).build())
                .setEndIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
                .build()
            val styles = UiVersions.newStylesBuilder().addStyle(style).build()
            val spec = InlinePresentationSpec.Builder(Size(px(48f), height), Size(maxWidth, height)).setStyle(styles).build()
            return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(MAX_SUGGESTIONS).build()
        }
    }
}

/** The order chips go in: the service's order, its pinned chips (its own button) last. */
object AutofillOrder {
    fun order(pinned: List<Boolean>): List<Int> =
        pinned.indices.filter { !pinned[it] } + pinned.indices.filter { pinned[it] }
}
