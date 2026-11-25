// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRecentsTest {
    private val wave = EmojiItem("👋", 1, "waving hand", listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿"))
    private val pizza = EmojiItem("🍕", 3, "pizza", emptyList())

    @Test
    fun theLatestComesFirstAndOnlyOnce() {
        val recents = EmojiRecents()
        for (text in listOf("🍕", "😀", "🍕", "👋🏽")) recents.used(text)
        assertEquals(listOf("👋🏽", "🍕", "😀"), recents.recent())
        recents.clear()
        assertTrue(recents.recent().isEmpty())
    }

    @Test
    fun theOldestGoAndOddTextIsNotKept() {
        val recents = EmojiRecents()
        for (i in 0 until EmojiRecents.MAX_RECENT + 5) recents.used("e$i")
        assertEquals(EmojiRecents.MAX_RECENT, recents.recent().size)
        assertEquals("e${EmojiRecents.MAX_RECENT + 4}", recents.recent().first())
        recents.used("")
        recents.used("x".repeat(65))
        assertEquals("e${EmojiRecents.MAX_RECENT + 4}", recents.recent().first())
    }

    @Test
    fun aToneIsRememberedPerEmoji() {
        val recents = EmojiRecents()
        assertEquals("👋", recents.variant(wave))
        recents.setTone("👋", 4)
        assertEquals("👋🏾", recents.variant(wave))
        assertEquals("👋🏾", recents.shown(EmojiCell(wave)))
        // A recent one is typed again as it was.
        assertEquals("👋🏻", recents.shown(EmojiCell(wave, "👋🏻")))
        assertEquals("🍕", recents.variant(pizza))
        recents.setTone("👋", 0)
        assertEquals("👋", recents.variant(wave))
        recents.setTone("👋", 9)
        assertEquals("👋", recents.variant(wave))
        // Clear forgets what was used, not the tones.
        recents.setTone("👋", 2)
        recents.used("👋🏼")
        recents.clear()
        assertEquals(2, recents.tone("👋"))
    }

    @Test
    fun roundTrip() {
        val recents = EmojiRecents()
        recents.used("🍕")
        recents.used("👋🏽")
        recents.setTone("👋", 3)
        val back = EmojiRecents()
        assertTrue(back.readJson(recents.toJson()))
        assertEquals(listOf("👋🏽", "🍕"), back.recent())
        assertEquals(3, back.tone("👋"))
    }

    @Test
    fun otherFilesChangeNothing() {
        val recents = EmojiRecents()
        recents.used("🍕")
        for (bad in listOf(
            "",
            "[]",
            "{}",
            """{"format":"pingolin-keyboard-words","version":1,"recent":["x"]}""",
            """{"format":"pingolin-keyboard-emoji","version":2,"recent":["x"]}""",
            """{"format":"pingolin-keyboard-emoji","version":1,"recent":["x"]""",
            """{"format":"pingolin-keyboard-emoji","version":1,"x":[[[[[[]]]]]]}""",
        )) {
            assertFalse(bad, recents.readJson(bad))
        }
        assertEquals(listOf("🍕"), recents.recent())
    }

    @Test
    fun badValuesAreDropped() {
        val recents = EmojiRecents()
        val long = "x".repeat(65)
        assertTrue(
            recents.readJson(
                """{"format":"pingolin-keyboard-emoji","version":1,"recent":["🍕","","🍕","$long","😀"],
                    "tones":{"👋":3,"👍":77,"🙏":"two"}}""",
            ),
        )
        assertEquals(listOf("🍕", "😀"), recents.recent())
        assertEquals(3, recents.tone("👋"))
        assertEquals(0, recents.tone("👍"))
        assertEquals(0, recents.tone("🙏"))
    }

    @Test
    fun rowsHoldTheRecentOnesThenEveryGroup() {
        val all = listOf(wave, pizza) + (1..5).map { EmojiItem("s$it", 0, "smiley $it", emptyList()) }
        val rows = EmojiRows.build(listOf(EmojiCell(wave, "👋🏿")), all, 3)
        assertEquals(
            listOf(
                EmojiRow.Title(EmojiRows.RECENT),
                EmojiRow.Cells(EmojiRows.RECENT, listOf(EmojiCell(wave, "👋🏿"))),
                EmojiRow.Title(0),
                EmojiRow.Cells(0, all.subList(2, 5).map { EmojiCell(it) }),
                EmojiRow.Cells(0, all.subList(5, 7).map { EmojiCell(it) }),
                EmojiRow.Title(1),
                EmojiRow.Cells(1, listOf(EmojiCell(wave))),
                EmojiRow.Title(3),
                EmojiRow.Cells(3, listOf(EmojiCell(pizza))),
            ),
            rows,
        )
        // Nothing used yet, nothing read yet.
        assertEquals(EmojiRow.Title(0), EmojiRows.build(emptyList(), all, 3).first())
        assertTrue(EmojiRows.build(emptyList(), emptyList(), 3).isEmpty())
        // The recent ones leave room for the rest.
        val many = List(40) { EmojiCell(pizza, "r$it") }
        assertEquals(3, EmojiRows.build(many, all, 8).count { it.group == EmojiRows.RECENT && it is EmojiRow.Cells })
    }

    @Test
    fun theQueryIsEditedAtItsCursor() {
        val query = EmojiQuery()
        query.type("hart")
        assertEquals(4, query.cursor)
        query.moveTo(1)
        query.type("e")
        assertEquals("heart" to 2, query.toString() to query.cursor)
        query.delete()
        assertEquals("hart" to 1, query.toString() to query.cursor)
        query.delete()
        assertEquals("art" to 0, query.toString() to query.cursor)
        query.delete()
        assertEquals("art" to 0, query.toString() to query.cursor)
        // Out of the text, the nearest end.
        query.moveTo(99)
        query.type("s")
        assertEquals("arts" to 4, query.toString() to query.cursor)
        query.moveTo(-3)
        query.type("p")
        assertEquals("parts" to 1, query.toString() to query.cursor)
        query.clear()
        assertEquals("" to 0, query.toString() to query.cursor)
    }

    @Test
    fun theQueryHasNoSpacesTooMany() {
        val query = EmojiQuery()
        query.type("red heart")
        query.moveTo(3)
        query.type(" ")
        // Over the space that is there, not a second one.
        assertEquals("red heart" to 4, query.toString() to query.cursor)
        query.type(" ")
        assertEquals("red heart" to 4, query.toString() to query.cursor)
        query.moveTo(3)
        repeat(3) { query.delete() }
        assertEquals("heart" to 0, query.toString() to query.cursor)
        query.type(" ")
        assertEquals("heart" to 0, query.toString() to query.cursor)
        query.moveTo(2)
        query.type(" ")
        assertEquals("he art" to 3, query.toString() to query.cursor)
    }

    @Test
    fun theCursorIsNeverInsideACharacter() {
        val query = EmojiQuery()
        query.type("a\uD835\uDC00e\u0301b")
        query.moveTo(2)
        assertEquals(3, query.cursor)
        query.moveTo(4)
        assertEquals(5, query.cursor)
        query.delete()
        assertEquals("a\uD835\uDC00b" to 3, query.toString() to query.cursor)
        query.delete()
        assertEquals("ab" to 1, query.toString() to query.cursor)
    }

    @Test
    fun theQueryHoldsWhatNamesAreMadeOf() {
        val query = EmojiQuery()
        assertTrue(query.isEmpty)
        for (key in listOf(" ", "t", "h", "!", "u", "m", "b", "s", " ", " ", "😀", "u", "p")) query.type(key)
        assertEquals("thumbs up", query.toString())
        query.delete()
        query.delete()
        assertEquals("thumbs ", query.toString())
        query.clear()
        assertTrue(query.isEmpty)
        query.delete()
        assertTrue(query.isEmpty)
        // A letter goes with its marks; a character outside the BMP whole.
        query.type("e\u0301")
        query.delete()
        assertTrue(query.isEmpty)
        query.type("\uD835\uDC00")
        query.delete()
        assertTrue(query.isEmpty)
        query.type("d'or ü-ß 2")
        assertEquals("d'or ü-ß 2", query.toString())
        query.clear()
        repeat(60) { query.type("a") }
        assertEquals(40, query.toString().length)
    }
}
