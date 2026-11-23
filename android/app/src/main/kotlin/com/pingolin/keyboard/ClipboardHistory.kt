// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Text the user copied, newest first. Pinned clips stay until removed. The
 * others go after an hour, or past [MAX_RECENT] when the user keeps the
 * history ([keepAll]). The keyboard decides what to record and where to keep it.
 */
class ClipboardHistory(private val now: () -> Long = System::currentTimeMillis) {
    data class Clip(val text: String, val time: Long, val pinned: Boolean)

    private val clips = ArrayList<Clip>()

    /** The user keeps the history across restarts, and past an hour. */
    var keepAll = false

    /** Records [text]; a clip already there moves to the top and keeps its pin. */
    fun add(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_CHARS) return false
        val old = clips.indexOfFirst { it.text == text }
        val pinned = old >= 0 && clips[old].pinned
        if (old >= 0) clips.removeAt(old)
        clips.add(0, Clip(text, now(), pinned))
        trim()
        return true
    }

    fun pin(text: String, on: Boolean) {
        val i = clips.indexOfFirst { it.text == text }
        if (i < 0) return
        if (on && !clips[i].pinned && clips.count { it.pinned } >= MAX_PINNED) return
        clips[i] = clips[i].copy(pinned = on)
        trim()
    }

    fun remove(text: String) {
        clips.removeAll { it.text == text }
    }

    /** Clears the unpinned clips, or all of them. */
    fun clear(pinnedToo: Boolean) {
        if (pinnedToo) clips.clear() else clips.removeAll { !it.pinned }
    }

    /** What the panel shows: pinned clips first, then the others, newest first. */
    fun clips(): List<Clip> {
        expire()
        return clips.filter { it.pinned } + clips.filter { !it.pinned }
    }

    private fun expire() {
        if (keepAll) return
        val cutoff = now() - HOUR_MS
        clips.removeAll { !it.pinned && it.time < cutoff }
    }

    private fun trim() {
        expire()
        var recent = 0
        clips.removeAll { !it.pinned && ++recent > MAX_RECENT }
    }

    /** The clips as JSON: the pinned ones, and the others too when [everything]. */
    fun toJson(everything: Boolean): String {
        val list = JSONArray()
        for (clip in clips()) {
            if (!clip.pinned && !everything) continue
            list.put(JSONObject().put("text", clip.text).put("time", clip.time).put("pinned", clip.pinned))
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("clips", list).toString()
    }

    /** Adds the clips in [json] (as [toJson] writes them); returns how many, or null if it is not that format. */
    fun mergeJson(json: String): Int? {
        if (json.length > MAX_JSON || KeyboardExport.nesting(json) > MAX_NESTING) return null
        return try {
            val root = JSONObject(json)
            if (root.optString("format") != FORMAT || root.optInt("version") != VERSION) return null
            val list = root.optJSONArray("clips") ?: return null
            var added = 0
            for (i in (minOf(list.length(), MAX_RECENT + MAX_PINNED) - 1) downTo 0) {
                val o = list.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isBlank() || text.length > MAX_CHARS) continue
                val time = o.optLong("time", now()).coerceAtMost(now())
                val pinned = o.optBoolean("pinned", false)
                clips.removeAll { it.text == text }
                clips.add(0, Clip(text, time, pinned))
                added++
            }
            clips.sortByDescending { it.time }
            trim()
            added
        } catch (e: JSONException) {
            null
        }
    }

    companion object {
        const val FORMAT = "pingolin-keyboard-clipboard"
        const val VERSION = 1
        const val MAX_RECENT = 50
        const val MAX_PINNED = 50

        /** Longer clips are not recorded. */
        const val MAX_CHARS = 5_000
        const val HOUR_MS = 60 * 60 * 1000L
        private const val MAX_JSON = 4 * 1024 * 1024
        private const val MAX_NESTING = 8
    }
}
