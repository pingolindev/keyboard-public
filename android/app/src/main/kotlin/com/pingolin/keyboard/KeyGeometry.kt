// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/** A key's cell in view pixels. Plain data, so the layout maths runs in JVM tests. */
data class KeyRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

object KeyGeometry {
    /**
     * Places rows of keys, given each key's width in letter-key units. One unit
     * has the same size in every row: the widest row fills [width] and shorter
     * rows are centred.
     */
    fun place(rowWidths: List<List<Float>>, width: Float, rowHeight: Float, top: Float = 0f): List<KeyRect> {
        val widest = rowWidths.maxOfOrNull { it.sum() } ?: return emptyList()
        if (widest <= 0f || width <= 0f) return emptyList()
        val unit = width / widest
        val keys = ArrayList<KeyRect>()
        rowWidths.forEachIndexed { row, widths ->
            var x = (width - widths.sum() * unit) / 2f
            val y = top + row * rowHeight
            for (w in widths) {
                keys += KeyRect(x, y, x + w * unit, y + rowHeight)
                x += w * unit
            }
        }
        return keys
    }

    /**
     * The key a touch at ([x], [y]) belongs to. The row comes first: a touch in
     * the margin of a centred row goes to that row's nearest key, not to a key
     * of the row above. Returns -1 when there are no keys.
     */
    fun nearest(keys: List<KeyRect>, x: Float, y: Float): Int {
        var best = -1
        var bestDy = Float.MAX_VALUE
        var bestDx = Float.MAX_VALUE
        keys.forEachIndexed { index, key ->
            val dx = maxOf(key.left - x, 0f, x - key.right)
            val dy = maxOf(key.top - y, 0f, y - key.bottom)
            if (dy < bestDy || (dy == bestDy && dx < bestDx)) {
                best = index
                bestDy = dy
                bestDx = dx
            }
        }
        return best
    }
}
