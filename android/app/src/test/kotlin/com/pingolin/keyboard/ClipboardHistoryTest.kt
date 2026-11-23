// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {
    private var time = 1_000_000L
    private val history = ClipboardHistory { time }

    private fun texts() = history.clips().map { it.text }

    @Test
    fun newestFirstAndACopyAgainMovesToTheTop() {
        history.add("one")
        time += 1
        history.add("two")
        time += 1
        history.add("one")
        assertEquals(listOf("one", "two"), texts())
        assertFalse(history.add("   "))
        assertFalse(history.add("x".repeat(ClipboardHistory.MAX_CHARS + 1)))
    }

    @Test
    fun unpinnedClipsGoAfterAnHourUnlessKept() {
        history.add("pinned")
        history.pin("pinned", true)
        history.add("recent")
        time += ClipboardHistory.HOUR_MS + 1
        assertEquals(listOf("pinned"), texts())
        history.keepAll = true
        history.add("kept")
        time += 10 * ClipboardHistory.HOUR_MS
        assertEquals(listOf("pinned", "kept"), texts())
    }

    @Test
    fun pinnedFirstAndClearLeavesThem() {
        history.add("a")
        history.add("b")
        history.pin("a", true)
        assertEquals(listOf("a", "b"), texts())
        history.clear(pinnedToo = false)
        assertEquals(listOf("a"), texts())
        history.clear(pinnedToo = true)
        assertTrue(texts().isEmpty())
    }

    @Test
    fun onlyFiftyRecentClips() {
        history.keepAll = true
        repeat(60) {
            time += 1
            history.add("clip $it")
        }
        assertEquals(ClipboardHistory.MAX_RECENT, texts().size)
        assertEquals("clip 59", texts().first())
    }

    @Test
    fun theFileKeepsPinsAndTheRestOnlyWhenAsked() {
        history.add("secret plan")
        history.add("pinned")
        history.pin("pinned", true)
        val pinsOnly = ClipboardHistory { time }
        assertEquals(1, pinsOnly.mergeJson(history.toJson(everything = false)))
        assertEquals(listOf("pinned"), pinsOnly.clips().map { it.text })
        val all = ClipboardHistory { time }
        assertEquals(2, all.mergeJson(history.toJson(everything = true)))
        assertNull(all.mergeJson("""{"format":"something-else","version":1,"clips":[]}"""))
        assertNull(all.mergeJson("[".repeat(100)))
    }
}
