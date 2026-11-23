// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package keyboard.buildlogic

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The keyboard's rules (CONTRIBUTING.md) applied to a merged AndroidManifest.xml
 * and the backup rules file. Each function returns one line per problem, none
 * when the input passes.
 */
object ManifestPolicy {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** No network access, whatever a library asks for. */
    val NETWORK_PERMISSIONS = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.NEARBY_WIFI_DEVICES",
    )

    /**
     * The only permissions the keyboard may hold. The microphone is for voice
     * typing, asked for at its first use.
     */
    val ALLOWED_PERMISSIONS: Set<String> = setOf("android.permission.RECORD_AUDIO")

    /** androidx.core declares this signature permission, private to the app itself. */
    private const val OWN_RECEIVER_PERMISSION = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    /** Every storage domain Android can back up or move to a new phone. */
    val BACKUP_DOMAINS = listOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    fun check(manifestXml: String, backupRulesXml: String): List<String> {
        val problems = mutableListOf<String>()
        val manifest = parse(manifestXml).documentElement
        val packageName = manifest.getAttribute("package")

        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            for (permission in manifest.children(tag).map { it.android("name") }) {
                when {
                    permission in NETWORK_PERMISSIONS -> problems += "network permission $permission"
                    permission in ALLOWED_PERMISSIONS -> Unit
                    packageName.isNotEmpty() && permission == packageName + OWN_RECEIVER_PERMISSION -> Unit
                    else -> problems += "permission $permission is not in ManifestPolicy.ALLOWED_PERMISSIONS"
                }
            }
        }
        if (manifest.hasAttributeNS(ANDROID_NS, "sharedUserId")) {
            problems += "android:sharedUserId must not be set"
        }

        val application = manifest.children("application").singleOrNull()
            ?: return problems + "the manifest has no <application>"
        if (application.android("allowBackup") != "false") problems += "android:allowBackup must be \"false\""
        if (application.android("fullBackupContent") != "false") {
            problems += "android:fullBackupContent must be \"false\""
        }
        if (application.android("dataExtractionRules").isEmpty()) problems += "android:dataExtractionRules is not set"
        problems += checkBackupRules(backupRulesXml)

        val inputMethods = application.children("service").filter { service ->
            service.children("intent-filter").any { filter ->
                filter.children("action").any { it.android("name") == "android.view.InputMethod" }
            }
        }
        if (inputMethods.isEmpty()) problems += "no input method service in the manifest"
        for (service in inputMethods) {
            val name = service.android("name")
            if (service.android("permission") != "android.permission.BIND_INPUT_METHOD") {
                problems += "$name must require android.permission.BIND_INPUT_METHOD"
            }
            if (service.android("directBootAware") != "true") problems += "$name must be directBootAware"
        }
        return problems
    }

    /** Cloud backup and device-to-device transfer both exclude every domain. */
    fun checkBackupRules(xml: String): List<String> {
        val root = parse(xml).documentElement
        if (root.tagName != "data-extraction-rules") return listOf("the backup rules must be <data-extraction-rules>")
        val problems = mutableListOf<String>()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = root.children(section).singleOrNull()
            if (element == null) {
                problems += "<$section> is missing"
                continue
            }
            if (element.children("include").isNotEmpty()) problems += "<$section> must not include anything"
            val excluded = element.children("exclude").filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }.toSet()
            for (domain in BACKUP_DOMAINS.filterNot { it in excluded }) {
                problems += "<$section> does not exclude domain \"$domain\""
            }
        }
        return problems
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)
}

/** The keyboard's rules applied to the modules an app variant ships with. */
object DependencyPolicy {
    /** Group prefixes the keyboard must never depend on, and why. */
    val DENIED: List<Pair<String, String>> = listOf(
        "com.google.android.gms" to "Google Play services",
        "com.google.firebase" to "Firebase",
        "com.google.android.play" to "Google Play libraries",
        "com.google.android.datatransport" to "Google data transport",
        "com.google.mlkit" to "ML Kit",
        "com.google.android.ads" to "ads",
        "com.crashlytics" to "crash reporting",
        "io.fabric" to "crash reporting",
        "io.sentry" to "crash reporting",
        "com.bugsnag" to "crash reporting",
        "com.microsoft.appcenter" to "analytics",
        "com.mixpanel" to "analytics",
        "com.amplitude" to "analytics",
        "com.segment.analytics" to "analytics",
        "com.datadoghq" to "analytics",
        "com.newrelic" to "analytics",
        "com.flurry" to "analytics",
        "com.appsflyer" to "analytics",
        "com.adjust" to "analytics",
        "com.onesignal" to "push messaging",
        "com.facebook" to "Facebook SDK",
        "com.squareup.okhttp" to "network client",
        "com.squareup.okhttp3" to "network client",
        "com.squareup.retrofit2" to "network client",
        "io.ktor" to "network client",
        "org.chromium.net" to "network client",
        "com.android.volley" to "network client",
    )

    /** Every Kotlin app ships this. If it is missing, the graph was not really read. */
    const val CONTROL_MODULE = "org.jetbrains.kotlin:kotlin-stdlib"

    /** [modules] are `group:name:version` strings. */
    fun check(modules: Collection<String>): List<String> {
        val problems = mutableListOf<String>()
        if (modules.none { it.startsWith("$CONTROL_MODULE:") }) {
            problems += "control: $CONTROL_MODULE is not among ${modules.size} modules, so this check read nothing"
        }
        for (module in modules.sorted()) {
            val group = module.substringBefore(':')
            DENIED.firstOrNull { (prefix, _) -> group == prefix || group.startsWith("$prefix.") }
                ?.let { (_, rule) -> problems += "$rule: $module" }
        }
        return problems
    }
}

private fun parse(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return factory.newDocumentBuilder().parse(xml.byteInputStream())
}

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
