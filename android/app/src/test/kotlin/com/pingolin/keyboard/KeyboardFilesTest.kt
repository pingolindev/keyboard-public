// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardFilesTest {
    @Test
    fun filesAreToldApartByTheirFirstBytes() {
        assertEquals(KeyboardFileKind.LANGUAGE, KeyboardFiles.kindOf("PKBDICT\u0000rest".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(KeyboardFileKind.VOICE, KeyboardFiles.kindOf(byteArrayOf(0x6c, 0x6d, 0x67, 0x67, 1, 2)))
        assertEquals(KeyboardFileKind.UNKNOWN, KeyboardFiles.kindOf("%PDF-1.7".toByteArray()))
        assertEquals(KeyboardFileKind.UNKNOWN, KeyboardFiles.kindOf(byteArrayOf(0x50)))
    }

    @Test
    fun languageLinksPointAtTheReleaseOfThisVersion() {
        assertEquals(
            "https://github.com/pingolindev/keyboard-public/releases/download/v0.1.0/de.pkd",
            KeyboardFiles.languageUrl("0.1.0-debug", "de"),
        )
        assertEquals(
            "https://github.com/pingolindev/keyboard-public/releases/download/v1.2.3/pt-BR.pkd",
            KeyboardFiles.languageUrl("1.2.3", "pt-BR"),
        )
    }
}
