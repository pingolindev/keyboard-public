// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/** Shows each key to TalkBack as a button with a name and a click action. */
class KeyboardAccessibility(private val view: KeyboardView) : ExploreByTouchHelper(view) {
    private val bounds = Rect()

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val index = view.indexAt(x, y)
        return if (index >= 0) index else ExploreByTouchHelper.INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (index in 0 until view.keyCount) virtualViewIds.add(index)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        node.className = Button::class.java.name
        node.contentDescription = view.describe(virtualViewId)
        view.keyBounds(virtualViewId, bounds)
        @Suppress("DEPRECATION") // ExploreByTouchHelper still needs bounds in the parent
        node.setBoundsInParent(bounds)
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        if (view.isLanguageKey(virtualViewId)) {
            node.addAction(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.action_next_language,
                    view.context.getString(R.string.action_next_language),
                ),
            )
        }
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.action_next_language && view.isLanguageKey(virtualViewId)) {
            view.nextLanguage()
            return true
        }
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        view.performKey(virtualViewId)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }
}
