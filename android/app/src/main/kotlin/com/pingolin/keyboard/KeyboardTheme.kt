// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi

/** The keyboard's colors, as ARGB. */
data class KeyboardTheme(
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val pressed: Int,
    val label: Int,
    val accent: Int,
    /** The strip in incognito. */
    val incognito: Int,
) {
    companion object {
        const val SYSTEM = "system"
        const val LIGHT_ID = "light"
        const val DARK_ID = "dark"
        const val BLACK_ID = "black"

        /** The wallpaper's colors (Material You), on Android 12 and later. */
        const val DYNAMIC = "dynamic"

        /** Every theme id, in the order the setup screen lists them. */
        val IDS = listOf(SYSTEM, LIGHT_ID, DARK_ID, BLACK_ID, DYNAMIC)

        // Pingolin's colors.
        val LIGHT = KeyboardTheme(
            background = 0xFFE9E8F0.toInt(),
            key = 0xFFFFFFFF.toInt(),
            functionKey = 0xFFCDCBDA.toInt(),
            pressed = 0xFFB9AEFF.toInt(),
            label = 0xFF1A1040.toInt(),
            accent = 0xFF6D4AFF.toInt(),
            incognito = 0xFFD6CEFF.toInt(),
        )
        val DARK = KeyboardTheme(
            background = 0xFF16151D.toInt(),
            key = 0xFF34333F.toInt(),
            functionKey = 0xFF25242E.toInt(),
            pressed = 0xFF5A4BC4.toInt(),
            label = 0xFFF0F0F5.toInt(),
            accent = 0xFF8B7AFF.toInt(),
            incognito = 0xFF2E2450.toInt(),
        )

        /** Black, for OLED screens. */
        val BLACK = KeyboardTheme(
            background = 0xFF000000.toInt(),
            key = 0xFF1E1D25.toInt(),
            functionKey = 0xFF111016.toInt(),
            pressed = 0xFF5A4BC4.toInt(),
            label = 0xFFF0F0F5.toInt(),
            accent = 0xFF8B7AFF.toInt(),
            incognito = 0xFF221A3C.toInt(),
        )

        fun isId(id: String): Boolean = id in IDS

        /** The theme [id] stands for now: "system" and the wallpaper's colors follow the phone's dark mode. */
        fun of(context: Context, id: String): KeyboardTheme {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return when (id) {
                LIGHT_ID -> LIGHT
                DARK_ID -> DARK
                BLACK_ID -> BLACK
                DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamic(context, night) else if (night) DARK else LIGHT
                else -> if (night) DARK else LIGHT
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun dynamic(context: Context, night: Boolean): KeyboardTheme {
            fun c(id: Int) = context.getColor(id)
            return if (night) {
                KeyboardTheme(
                    background = c(android.R.color.system_neutral1_900),
                    key = c(android.R.color.system_neutral1_800),
                    functionKey = c(android.R.color.system_neutral2_800),
                    pressed = c(android.R.color.system_accent1_700),
                    label = c(android.R.color.system_neutral1_50),
                    accent = c(android.R.color.system_accent1_200),
                    incognito = c(android.R.color.system_accent2_800),
                )
            } else {
                KeyboardTheme(
                    background = c(android.R.color.system_neutral1_100),
                    key = c(android.R.color.system_neutral1_10),
                    functionKey = c(android.R.color.system_neutral2_200),
                    pressed = c(android.R.color.system_accent1_200),
                    label = c(android.R.color.system_neutral1_900),
                    accent = c(android.R.color.system_accent1_600),
                    incognito = c(android.R.color.system_accent2_100),
                )
            }
        }
    }
}
