// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** An emoji the panel can show. [tones]: the same one from light to dark skin, or empty. */
data class EmojiItem(val text: String, val group: Int, val name: String, val tones: List<String>)

/**
 * A place in the panel. [exact]: what a tap types, for a recent emoji; null
 * for [item] with the skin tone last picked for it.
 */
data class EmojiCell(val item: EmojiItem, val exact: String? = null)

/**
 * The emoji used lately, newest first, and the skin tone last picked for each
 * that has them. Pure, so it runs in plain JVM tests.
 */
class EmojiRecents {
    private val recent = ArrayList<String>()
    private val tones = LinkedHashMap<String, Int>()

    fun recent(): List<String> = recent.toList()

    /** Adds [text], as typed (its skin tone included), at the front. */
    fun used(text: String) {
        if (text.isEmpty() || text.length > MAX_LENGTH) return
        recent.remove(text)
        recent.add(0, text)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
    }

    /** The tone last picked for [base]: 0 for none, else 1 (light) to 5 (dark). */
    fun tone(base: String): Int = tones[base] ?: 0

    fun setTone(base: String, tone: Int) {
        tones.remove(base)
        if (tone in 1..TONES && base.length <= MAX_LENGTH) tones[base] = tone
        while (tones.size > MAX_TONES) tones.remove(tones.keys.first())
    }

    /** What a tap on [cell] types. */
    fun shown(cell: EmojiCell): String = cell.exact ?: variant(cell.item)

    /** [item] with the skin tone last picked for it. */
    fun variant(item: EmojiItem): String = item.tones.getOrNull(tone(item.text) - 1) ?: item.text

    /** Forgets the emoji used; the skin tones picked stay. */
    fun clear() = recent.clear()

    fun toJson(): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("recent", JSONArray(recent))
        .put("tones", JSONObject().also { t -> tones.forEach { (k, v) -> t.put(k, v) } })
        .toString()

    /** Reads [json]; false, and nothing changes, when it is not one of ours. */
    fun readJson(json: String): Boolean {
        if (json.length > MAX_JSON || KeyboardExport.nesting(json) > MAX_NESTING) return false
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT || root.optInt("version") != VERSION) return false
            recent.clear()
            val list = root.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until minOf(list.length(), MAX_RECENT)) {
                val text = list.optString(i)
                if (text.isNotEmpty() && text.length <= MAX_LENGTH && text !in recent) recent.add(text)
            }
            tones.clear()
            root.optJSONObject("tones")?.let { t -> for (key in t.keys()) setTone(key, t.optInt(key)) }
            true
        } catch (e: JSONException) {
            false
        }
    }

    companion object {
        const val FORMAT = "pingolin-keyboard-emoji"
        const val VERSION = 1
        const val MAX_RECENT = 40
        private const val TONES = 5
        private const val MAX_TONES = 500
        private const val MAX_LENGTH = 64
        private const val MAX_JSON = 256 * 1024
        private const val MAX_NESTING = 4
    }
}

/** One row of the panel: a section's name, or up to a screen's width of emoji. */
sealed interface EmojiRow {
    /** The section: [EmojiRows.RECENT], or 0 (smileys) to 8 (flags). */
    val group: Int

    data class Title(override val group: Int) : EmojiRow

    data class Cells(override val group: Int, val cells: List<EmojiCell>) : EmojiRow
}

object EmojiRows {
    /** The recent ones' section. */
    const val RECENT = -1

    /** The sections in the panel's order. */
    val SECTIONS = listOf(RECENT) + (0..8)

    /** How many rows the recent ones take at most. */
    private const val RECENT_ROWS = 3

    /** The panel's rows: the recent emoji if any, then every group, [columns] to a row. */
    fun build(recent: List<EmojiCell>, all: List<EmojiItem>, columns: Int): List<EmojiRow> {
        val perRow = maxOf(1, columns)
        val rows = ArrayList<EmojiRow>()
        fun section(group: Int, cells: List<EmojiCell>) {
            if (cells.isEmpty()) return
            rows.add(EmojiRow.Title(group))
            cells.chunked(perRow).forEach { rows.add(EmojiRow.Cells(group, it)) }
        }
        section(RECENT, recent.take(perRow * RECENT_ROWS))
        val groups = all.groupBy { it.group }
        for (group in SECTIONS.drop(1)) section(group, groups[group].orEmpty().map { EmojiCell(it) })
        return rows
    }
}

/** What is typed into the search over emoji. Pure, so it runs in plain JVM tests. */
class EmojiQuery {
    private val text = StringBuilder()

    override fun toString(): String = text.toString()

    val isEmpty: Boolean get() = text.isEmpty()

    /** Types [s]. Only what names are made of counts: letters, digits, marks, spaces, apostrophes and hyphens. */
    fun type(s: String) {
        if (text.length + s.length > MAX) return
        s.codePoints().forEach { cp ->
            val space = cp == ' '.code
            // No space at the start, or after another.
            if (space && (text.isEmpty() || text.last() == ' ')) return@forEach
            if (space || isNamePart(cp)) text.appendCodePoint(cp)
        }
    }

    /** Deletes the last character, with its marks. */
    fun delete() {
        while (text.isNotEmpty()) {
            val cp = text.codePointBefore(text.length)
            text.setLength(text.length - Character.charCount(cp))
            if (!isMark(cp)) return
        }
    }

    fun clear() = text.setLength(0)

    private fun isNamePart(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || isMark(cp) || cp == '\''.code || cp == 0x2019 || cp == '-'.code

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private companion object {
        const val MAX = 40
    }
}
