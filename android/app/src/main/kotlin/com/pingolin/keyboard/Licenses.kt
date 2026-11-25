// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

/** A part of the app, its license, and the files its page shows. */
data class LicensePage(val part: String, val license: String, val files: List<String>)

/** Reads the Licenses screen's files. Pure, so it runs in plain JVM tests. */
object Licenses {
    /** The pages of an index: a part, its license and its files to a line. */
    fun pages(index: String): List<LicensePage> = index.lines().mapNotNull { line ->
        val fields = line.split('\t')
        if (line.startsWith("#") || fields.size != 3) return@mapNotNull null
        LicensePage(fields[0], fields[1], fields[2].split(','))
    }

    /** [text] without its line breaks, except where a paragraph or a list item starts. */
    fun paragraphs(text: String): List<String> {
        val paragraphs = ArrayList<String>()
        for (block in text.split(BLANK_LINE)) {
            val paragraph = StringBuilder()
            for (line in block.lines().map(String::trim)) {
                if (line.isEmpty() || line.all { it in "-=*" }) continue // a line drawn under a title
                if (paragraph.isNotEmpty() && ITEM.containsMatchIn(line)) {
                    paragraphs.add(paragraph.toString())
                    paragraph.setLength(0)
                }
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
            if (paragraph.isNotEmpty()) paragraphs.add(paragraph.toString())
        }
        return paragraphs
    }

    private val BLANK_LINE = Regex("\\n\\s*\\n")

    /** "* ", "- ", "1. ", "a) " and "(a) " start an item. */
    private val ITEM = Regex("^([*•-]|\\(?\\w{1,3}[.)])\\s")
}
