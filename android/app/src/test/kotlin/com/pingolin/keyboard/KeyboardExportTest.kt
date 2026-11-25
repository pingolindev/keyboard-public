// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardExportTest {
    private val settings = SettingsSnapshot(
        suggestions = true,
        autocorrect = false,
        learnWords = true,
        languages = listOf("en-US", "de"),
        language = "de",
    )
    private val words = """{"format":"pingolin-keyboard-words","version":1,"words":[{"word":"Pingolin","count":2}]}"""

    @Test
    fun roundTrip() {
        val text = KeyboardExport.write(KeyboardExport.Contents(settings, words))
        val back = KeyboardExport.read(text)!!
        assertEquals(settings, back.settings)
        assertEquals(org.json.JSONObject(words).toString(), back.wordsJson)
    }

    @Test
    fun eachPartIsOptional() {
        val onlySettings = KeyboardExport.read(KeyboardExport.write(KeyboardExport.Contents(settings, null)))!!
        assertEquals(settings, onlySettings.settings)
        assertNull(onlySettings.wordsJson)
        val onlyWords = KeyboardExport.read(KeyboardExport.write(KeyboardExport.Contents(null, words)))!!
        assertNull(onlyWords.settings)
    }

    @Test
    fun otherFilesAreRefused() {
        for (bad in listOf(
            "",
            "[]",
            "{}",
            """{"format":"something-else","version":1,"settings":{}}""",
            """{"format":"pingolin-keyboard-export","version":2,"settings":{}}""",
            """{"format":"pingolin-keyboard-export","version":1}""",
            "{\"format\":\"pingolin-keyboard-export\"",
        )) {
            assertNull(bad, KeyboardExport.read(bad))
        }
    }

    @Test
    fun badValuesAreDroppedNotTrusted() {
        val text = """{"format":"pingolin-keyboard-export","version":1,
            "settings":{"suggestions":"yes","languages":["en-US","not a tag!","de",3],"language":"../etc"}}"""
        val s = KeyboardExport.read(text)!!.settings!!
        assertEquals(listOf("en-US", "de"), s.languages)
        assertEquals("en-US", s.language)
        assertEquals(true, s.suggestions)
    }

    @Test
    fun deeplyNestedFilesAreRefusedBeforeParsing() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertNull(KeyboardExport.read("""{"format":"pingolin-keyboard-export","version":1,"x":$deep}"""))
        // Brackets inside strings do not count.
        assertEquals(1, KeyboardExport.nesting("""{"a":"[[[[{{{{\"]]"}"""))
    }

    @Test
    fun colorsAndHeightTravelAndAreChecked() {
        val snapshot = SettingsSnapshot(true, false, true, listOf("en-US"), "en-US", KeyboardTheme.BLACK_ID, 115)
        val back = KeyboardExport.read(KeyboardExport.write(KeyboardExport.Contents(snapshot, null)))!!.settings!!
        assertEquals(KeyboardTheme.BLACK_ID, back.theme)
        assertEquals(115, back.heightPercent)
        val odd = KeyboardExport.read(
            """{"format":"pingolin-keyboard-export","version":1,"settings":{"theme":"neon","height":900}}""",
        )!!.settings!!
        assertEquals(KeyboardTheme.SYSTEM, odd.theme)
        assertEquals(KeyboardSettings.MAX_HEIGHT, odd.heightPercent)
    }

    @Test
    fun laterSwitchesTravelAndDefaultToOn() {
        val off = SettingsSnapshot(
            true, true, true, listOf("en-US"), "en-US", autoIncognito = false, autoSpace = false, suggestEmoji = false,
        )
        val back = KeyboardExport.read(KeyboardExport.write(KeyboardExport.Contents(off, null)))!!.settings!!
        assertEquals(false, back.autoIncognito)
        assertEquals(false, back.autoSpace)
        assertEquals(false, back.suggestEmoji)
        val older = KeyboardExport.read("""{"format":"pingolin-keyboard-export","version":1,"settings":{"suggestions":true}}""")!!
        assertEquals(true, older.settings!!.autoIncognito)
        assertEquals(true, older.settings!!.autoSpace)
        assertEquals(true, older.settings!!.suggestEmoji)
    }

    @Test
    fun pinnedClipsTravelInTheirOwnPart() {
        val clips = ClipboardHistory()
        clips.add("address")
        clips.pin("address", true)
        val file = KeyboardExport.write(KeyboardExport.Contents(null, null, clips.toJson(everything = false)))
        val back = KeyboardExport.read(file)!!
        assertNull(back.settings)
        assertNull(back.wordsJson)
        val restored = ClipboardHistory()
        assertEquals(1, restored.mergeJson(back.clipboardJson!!))
        assertEquals("address", restored.clips().single().text)
    }
}
