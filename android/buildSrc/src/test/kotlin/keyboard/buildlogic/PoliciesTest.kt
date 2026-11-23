// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package keyboard.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPolicyTest {
    private fun manifest(
        permissions: List<String> = emptyList(),
        application: String = """android:allowBackup="false" android:fullBackupContent="false"
            android:dataExtractionRules="@xml/data_extraction_rules"""",
        service: String = """android:permission="android.permission.BIND_INPUT_METHOD" android:directBootAware="true"""",
        manifestAttributes: String = "",
    ) = """<?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.kb" $manifestAttributes>
          ${permissions.joinToString("\n") { """<uses-permission android:name="$it"/>""" }}
          <application $application>
            <service android:name="com.example.kb.Ime" android:exported="true" $service>
              <intent-filter><action android:name="android.view.InputMethod"/></intent-filter>
            </service>
          </application>
        </manifest>"""

    private fun rules(
        cloud: List<String> = ManifestPolicy.BACKUP_DOMAINS,
        device: List<String>? = ManifestPolicy.BACKUP_DOMAINS,
        extraCloud: String = "",
    ): String {
        fun section(tag: String, domains: List<String>, extra: String = "") =
            "<$tag>${domains.joinToString("") { """<exclude domain="$it" path="."/>""" }}$extra</$tag>"
        return "<data-extraction-rules>${section("cloud-backup", cloud, extraCloud)}" +
            (device?.let { section("device-transfer", it) } ?: "") + "</data-extraction-rules>"
    }

    @Test
    fun aCompliantManifestPasses() {
        assertEquals(emptyList<String>(), ManifestPolicy.check(manifest(), rules()))
    }

    @Test
    fun theAppsOwnReceiverPermissionIsAllowed() {
        val problems = ManifestPolicy.check(
            manifest(permissions = listOf("com.example.kb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")), rules(),
        )
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun everyNetworkPermissionFails() {
        for (permission in ManifestPolicy.NETWORK_PERMISSIONS) {
            assertEquals(
                listOf("network permission $permission"),
                ManifestPolicy.check(manifest(permissions = listOf(permission)), rules()),
            )
        }
    }

    @Test
    fun aPermissionOffTheAllowlistFails() {
        val problems = ManifestPolicy.check(manifest(permissions = listOf("android.permission.READ_CONTACTS")), rules())
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().startsWith("permission android.permission.READ_CONTACTS"))
    }

    @Test
    fun anotherAppsReceiverPermissionFails() {
        val problems = ManifestPolicy.check(
            manifest(permissions = listOf("com.other.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")), rules(),
        )
        assertTrue(problems.toString(), problems.single().startsWith("permission "))
    }

    @Test
    fun backupsMustBeOff() {
        val problems = ManifestPolicy.check(
            manifest(application = """android:allowBackup="true""""), rules(),
        )
        assertEquals(
            listOf(
                "android:allowBackup must be \"false\"",
                "android:fullBackupContent must be \"false\"",
                "android:dataExtractionRules is not set",
            ),
            problems,
        )
    }

    @Test
    fun sharedUserIdFails() {
        val problems = ManifestPolicy.check(manifest(manifestAttributes = """android:sharedUserId="com.example""""), rules())
        assertEquals(listOf("android:sharedUserId must not be set"), problems)
    }

    @Test
    fun theInputMethodMustBeProtectedAndDirectBootAware() {
        val problems = ManifestPolicy.check(manifest(service = ""), rules())
        assertEquals(
            listOf(
                "com.example.kb.Ime must require android.permission.BIND_INPUT_METHOD",
                "com.example.kb.Ime must be directBootAware",
            ),
            problems,
        )
    }

    @Test
    fun aMissingDomainFails() {
        val problems = ManifestPolicy.check(manifest(), rules(device = ManifestPolicy.BACKUP_DOMAINS - "device_root"))
        assertEquals(listOf("<device-transfer> does not exclude domain \"device_root\""), problems)
    }

    @Test
    fun aMissingDeviceTransferSectionFails() {
        assertEquals(listOf("<device-transfer> is missing"), ManifestPolicy.checkBackupRules(rules(device = null)))
    }

    @Test
    fun anIncludeFails() {
        val problems = ManifestPolicy.checkBackupRules(rules(extraCloud = """<include domain="file" path="notes"/>"""))
        assertEquals(listOf("<cloud-backup> must not include anything"), problems)
    }
}

class DependencyPolicyTest {
    private val clean = listOf("org.jetbrains.kotlin:kotlin-stdlib:2.3.10", "net.java.dev.jna:jna:5.18.1")

    @Test
    fun cleanDependenciesPass() {
        assertEquals(emptyList<String>(), DependencyPolicy.check(clean))
    }

    @Test
    fun googlePlayServicesFail() {
        val problems = DependencyPolicy.check(clean + "com.google.android.gms:play-services-base:18.5.0")
        assertEquals(listOf("Google Play services: com.google.android.gms:play-services-base:18.5.0"), problems)
    }

    @Test
    fun subgroupsOfADeniedGroupFail() {
        val problems = DependencyPolicy.check(clean + "com.google.firebase.crashlytics:sdk:1.0")
        assertEquals(listOf("Firebase: com.google.firebase.crashlytics:sdk:1.0"), problems)
    }

    @Test
    fun aGroupThatOnlySharesAPrefixPasses() {
        assertEquals(emptyList<String>(), DependencyPolicy.check(clean + "com.google.firebasex:tool:1.0"))
    }

    @Test
    fun networkClientsFail() {
        val problems = DependencyPolicy.check(clean + "com.squareup.okhttp3:okhttp:5.0.0")
        assertEquals(listOf("network client: com.squareup.okhttp3:okhttp:5.0.0"), problems)
    }

    @Test
    fun anEmptyGraphFailsTheControl() {
        val problems = DependencyPolicy.check(emptyList())
        assertTrue(problems.toString(), problems.single().startsWith("control:"))
    }
}
