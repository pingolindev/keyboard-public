// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeometryTest {
    // Four keys on top, two centred below.
    private val keys = KeyGeometry.place(listOf(List(4) { 1f }, List(2) { 1f }), width = 400f, rowHeight = 50f)

    @Test
    fun theWidestRowFillsTheWidthAndShorterRowsAreCentred() {
        assertEquals(KeyRect(0f, 0f, 100f, 50f), keys[0])
        assertEquals(KeyRect(300f, 0f, 400f, 50f), keys[3])
        assertEquals(KeyRect(100f, 50f, 200f, 100f), keys[4])
        assertEquals(KeyRect(200f, 50f, 300f, 100f), keys[5])
    }

    @Test
    fun widerKeysTakeMoreUnits() {
        val row = KeyGeometry.place(listOf(listOf(1.5f, 1f, 1.5f)), width = 400f, rowHeight = 50f)
        assertEquals(listOf(0f, 150f, 250f), row.map { it.left })
        assertEquals(listOf(150f, 250f, 400f), row.map { it.right })
    }

    @Test
    fun aTouchInsideAKeyHitsThatKey() {
        assertEquals(1, KeyGeometry.nearest(keys, 150f, 25f))
        assertEquals(5, KeyGeometry.nearest(keys, 250f, 75f))
    }

    @Test
    fun aTouchInARowMarginHitsThatRowNotTheRowAbove() {
        assertEquals(4, KeyGeometry.nearest(keys, 10f, 75f))
        assertEquals(5, KeyGeometry.nearest(keys, 390f, 60f))
    }

    @Test
    fun nothingToPlaceMeansNoKeys() {
        assertTrue(KeyGeometry.place(emptyList(), 400f, 50f).isEmpty())
        assertTrue(KeyGeometry.place(listOf(listOf(1f)), 0f, 50f).isEmpty())
        assertEquals(-1, KeyGeometry.nearest(emptyList(), 1f, 1f))
    }
}
