// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceModelsTest {
    private val official = File("src/main/assets/voice/official.tsv").readText()

    @Test
    fun theOfficialListIsWellFormed() {
        val models = VoiceModels.read(official)
        assertEquals(5, models.size)
        assertTrue(models.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes > 1_000_000 && it.file.endsWith(".bin") })
        assertEquals(models.size, models.map { it.file }.toSet().size)
        assertTrue(models.any { !it.allLanguages } && models.any { it.allLanguages })
    }

    @Test
    fun keyboardLanguagesMapToWhisperCodes() {
        assertEquals("en", VoiceModels.whisperLanguage("en-US"))
        assertEquals("pt", VoiceModels.whisperLanguage("pt-BR"))
        assertEquals("no", VoiceModels.whisperLanguage("nb"))
        assertEquals("de", VoiceModels.whisperLanguage("de"))
    }

    @Test
    fun bracketedSoundsAreDropped() {
        assertEquals("", VoiceInput.clean(" [BLANK_AUDIO] "))
        assertEquals("Hello there", VoiceInput.clean("Hello (wind blowing) there"))
    }
}
