// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.artifact.SingleArtifact
import keyboard.buildlogic.CargoNdkTask
import keyboard.buildlogic.CompileDictionariesTask
import keyboard.buildlogic.UniffiBindingsTask
import keyboard.buildlogic.VerifyDependencyPolicyTask
import keyboard.buildlogic.VerifyManifestPolicyTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

// The Rust engine in ../core is compiled from source on every build.
val coreDir: Directory = rootProject.layout.projectDirectory.dir("../core")
// Word lists, compiled into the APK's assets by the engine's dictc.
val wordListsDir: Directory = rootProject.layout.projectDirectory.dir("../dictionaries")
val engineCrate = "pingolin-keyboard-engine"
val engineLibrary = "pingolin_keyboard_engine"
val engineAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val minSdkLevel = 26

// Release signing uses the keyboard's own key. Without these variables the
// release APK is left unsigned.
val releaseKeystore = providers.environmentVariable("PINGOLIN_KEYBOARD_KEYSTORE").orNull
    ?.let { file(it) }?.takeIf { it.isFile }
val releaseStorePassword = providers.environmentVariable("PINGOLIN_KEYBOARD_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PINGOLIN_KEYBOARD_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PINGOLIN_KEYBOARD_KEY_PASSWORD").orNull ?: releaseStorePassword
val canSignRelease = releaseKeystore != null && !releaseStorePassword.isNullOrEmpty() && !releaseKeyAlias.isNullOrEmpty()

android {
    namespace = "com.pingolin.keyboard"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pingolin.keyboard"
        minSdk = minSdkLevel
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        ndk { abiFilters += engineAbis }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    dependenciesInfo {
        // F-Droid treats the Play dependency metadata in the APK signing block as a
        // non-free blob, so leave it out.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val engineSources = fileTree(coreDir) {
    include(
        "Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml",
        "*/Cargo.toml", "*/uniffi.toml", "*/src/**", "*/build.rs", "*/whisper/**",
    )
}

val engineJniLibs = tasks.register<CargoNdkTask>("buildEngineJniLibs") {
    description = "Compiles the Rust engine for every ABI with cargo-ndk."
    workspaceDir.set(coreDir)
    sources.from(engineSources)
    crate.set(engineCrate)
    // Offline voice typing: whisper.cpp, compiled from the vendored source.
    features.set(listOf("voice"))
    abis.set(engineAbis)
    platform.set(minSdkLevel)
    ndkPath.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    outputDir.set(layout.buildDirectory.dir("generated/engine/jniLibs"))
}

val engineBindings = tasks.register<UniffiBindingsTask>("generateEngineBindings") {
    description = "Generates the Kotlin bindings of the Rust engine."
    workspaceDir.set(coreDir)
    sources.from(engineSources)
    crate.set(engineCrate)
    libraryName.set(engineLibrary)
    outputDir.set(layout.buildDirectory.dir("generated/engine/kotlin"))
}

// English (US) ships in the APK; every language, English included, is also
// a language file for the release page (build/outputs/languages).
val dictionaryAssets = tasks.register<CompileDictionariesTask>("compileDictionaries") {
    description = "Builds the dictionaries from the word lists in ../dictionaries."
    workspaceDir.set(coreDir)
    sources.from(engineSources)
    wordLists.from(fileTree(wordListsDir))
    dictionariesDir.set(wordListsDir)
    builtIn.set(listOf("en-US"))
    outputDir.set(layout.buildDirectory.dir("generated/engine/assets"))
    packsDir.set(layout.buildDirectory.dir("outputs/languages"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(engineJniLibs, CargoNdkTask::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(engineBindings, UniffiBindingsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(dictionaryAssets, CompileDictionariesTask::outputDir)

        // The rule checks (CONTRIBUTING.md) run before every assemble and in check.
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val manifestPolicy = tasks.register<VerifyManifestPolicyTask>("verify${name}ManifestPolicy") {
            description = "Checks the merged ${variant.name} manifest against the keyboard's rules."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            backupRules.set(layout.projectDirectory.file("src/main/res/xml/data_extraction_rules.xml"))
            report.set(layout.buildDirectory.file("reports/policy/${variant.name}-manifest.txt"))
        }
        val dependencyPolicy = tasks.register<VerifyDependencyPolicyTask>("verify${name}DependencyPolicy") {
            description = "Checks the ${variant.name} runtime dependencies against the keyboard's rules."
            rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
            report.set(layout.buildDirectory.file("reports/policy/${variant.name}-dependencies.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach {
            dependsOn(manifestPolicy, dependencyPolicy)
        }
    }
}

dependencies {
    // The engine bindings call Rust through JNA (LGPL-2.1 OR Apache-2.0, used under Apache-2.0).
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // ExploreByTouchHelper, so TalkBack can read the hand-drawn keys.
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.customview:customview:1.2.0")
    // The style a password manager's inline suggestions are drawn with (Apache-2.0, AOSP).
    implementation("androidx.autofill:autofill:1.3.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for unit tests of the export file (Android has its own).
    testImplementation("org.json:json:20260814")
}
