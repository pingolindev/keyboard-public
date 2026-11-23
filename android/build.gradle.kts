// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

// AGP builds the Kotlin code itself (built-in Kotlin). The Kotlin plugin line
// only pins its version.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
}
