// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class AutofillOrderTest {
    @Test
    fun pinnedChipsGoLastAndTheRestKeepTheirOrder() {
        assertEquals(listOf(1, 3, 0, 2), AutofillOrder.order(listOf(true, false, true, false)))
        assertEquals(listOf(0, 1), AutofillOrder.order(listOf(false, false)))
        assertEquals(emptyList<Int>(), AutofillOrder.order(emptyList()))
    }
}
