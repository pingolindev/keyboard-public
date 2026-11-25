// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesTest {
    /** The checkout, from the module the tests run in. */
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "licenses/index.tsv").isFile }

    private fun page(name: String) = listOf(File(root, "licenses/$name"), File(root, "LICENSES/$name")).first { it.isFile }

    @Test
    fun theIndexListsPartsWithTheirFiles() {
        val pages = Licenses.pages("# a comment\nJNA\tApache 2.0\tjna.txt,Apache-2.0.txt\nnot a page\n\nlibffi\tMIT\tlibffi.txt\n")
        assertEquals(
            listOf(
                LicensePage("JNA", "Apache 2.0", listOf("jna.txt", "Apache-2.0.txt")),
                LicensePage("libffi", "MIT", listOf("libffi.txt")),
            ),
            pages,
        )
    }

    @Test
    fun wrappedLinesAreJoined() {
        assertEquals(
            listOf("MIT License", "Permission is hereby granted, free of charge, to any person.", "THE SOFTWARE IS PROVIDED \"AS IS\"."),
            Licenses.paragraphs("MIT License\n\n   Permission is hereby granted,\n   free of charge, to any\n   person.\n  \nTHE SOFTWARE IS\nPROVIDED \"AS IS\".\n"),
        )
    }

    @Test
    fun listItemsStayApartAndLinesUnderTitlesGo() {
        assertEquals(
            listOf("1. Definitions", "You may:", "a) copy it and share it", "(b) change it", "* sell it", "2.1. Grants"),
            Licenses.paragraphs("1. Definitions\n--------------\n\nYou may:\n a) copy it\n    and share it\n (b) change it\n * sell it\n\n2.1. Grants\n====\n"),
        )
        assertTrue(Licenses.paragraphs("\n \n----\n").isEmpty())
    }

    @Test
    fun everyPageSaysUnderWhichLicenseItsPartIs() {
        val pages = Licenses.pages(File(root, "licenses/index.tsv").readText())
        assertTrue("${pages.size} pages", pages.size >= 13)
        // What the first paragraphs of each license's text start with.
        val titles = mapOf(
            "GPL 3.0 or later" to "GNU GENERAL PUBLIC LICENSE", "Apache 2.0" to "Apache License", "MIT" to "Permission is hereby granted",
            "MPL 2.0" to "Mozilla Public License Version 2.0", "CC BY 2.0 FR" to "Creative Commons Paternité 2.0 France", "Unicode 3.0" to "UNICODE LICENSE V3",
        )
        for (page in pages) {
            val text = page.files.flatMap { Licenses.paragraphs(page(it).readText()) }
            for (license in page.license.split(", ")) {
                val title = titles.getValue(license)
                assertTrue("${page.part}: no $license text", text.any { it.startsWith(title) })
            }
        }
    }

    @Test
    fun joiningLinesLosesNoWord() {
        val files = listOf("licenses", "LICENSES").flatMap { File(root, it).listFiles { f -> f.name.endsWith(".txt") }.orEmpty().toList() }
        assertTrue("${files.size} files", files.size > 15)
        for (file in files) {
            val text = file.readText()
            val words = text.split(Regex("\\s+")).filter { w -> w.isNotEmpty() && !w.all { it in "-=*" } }
            val shown = Licenses.paragraphs(text).flatMap { it.split(Regex("\\s+")) }.filter { w -> w.isNotEmpty() && !w.all { it in "-=*" } }
            assertEquals(file.name, words, shown)
        }
    }
}
