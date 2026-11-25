// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package keyboard.buildlogic

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Compiles the Rust engine for each Android ABI with cargo-ndk, in the jniLibs
 * layout (`<abi>/lib<name>.so`).
 */
abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    /** The Cargo workspace (`core/`). Changes are tracked through [sources]. */
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val crate: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    /** Minimum Android API level the libraries are linked for. */
    @get:Input
    abstract val platform: Property<Int>

    /** Cargo features to build with. */
    @get:Input
    abstract val features: ListProperty<String>

    /** The NDK to use. Without it, cargo-ndk looks for one itself. */
    @get:Input
    @get:Optional
    abstract val ndkPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val cargoArgs = mutableListOf("ndk", "--platform", platform.get().toString())
        abis.get().forEach { cargoArgs += listOf("--target", it) }
        cargoArgs += listOf(
            "--output-dir", out.absolutePath,
            "build", "--release", "--locked", "--package", crate.get(),
        )
        if (features.get().isNotEmpty()) cargoArgs += listOf("--features", features.get().joinToString(","))
        exec.exec {
            workingDir = workspaceDir.get().asFile
            executable = "cargo"
            args(cargoArgs)
            ndkPath.orNull?.let { environment("ANDROID_NDK_HOME", it) }
        }
        val missing = abis.get().filter { abi -> File(out, abi).listFiles().orEmpty().none { it.extension == "so" } }
        check(missing.isEmpty()) { "cargo-ndk succeeded but produced no library for $missing in $out" }
    }
}

/**
 * Generates the Kotlin bindings of the engine with uniffi-bindgen, from a host
 * build of the library. The bindgen tool is a crate of the same workspace, so
 * its UniFFI version always matches the engine's.
 */
abstract class UniffiBindingsTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val crate: Property<String>

    /** The library name, as in `lib<name>.so`. */
    @get:Input
    abstract val libraryName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val workspace = workspaceDir.get().asFile
        exec.exec {
            workingDir = workspace
            executable = "cargo"
            args("build", "--release", "--locked", "--package", crate.get(), "--lib")
        }
        val library = hostLibrary(workspace)
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        exec.exec {
            workingDir = workspace
            executable = "cargo"
            args(
                "run", "--release", "--locked", "--package", "uniffi-bindgen", "--",
                "generate", "--library", library.absolutePath,
                "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
            )
        }
        check(out.walkTopDown().any { it.isFile && it.extension == "kt" }) {
            "uniffi-bindgen succeeded but wrote no Kotlin file to $out"
        }
    }

    private fun hostLibrary(workspace: File): File {
        val os = System.getProperty("os.name").lowercase()
        val name = libraryName.get()
        val fileName = when {
            "mac" in os -> "lib$name.dylib"
            "windows" in os -> "$name.dll"
            else -> "lib$name.so"
        }
        // Cargo builds into CARGO_TARGET_DIR when it is set, else into <workspace>/target.
        val targetDir = System.getenv("CARGO_TARGET_DIR")?.takeIf { it.isNotBlank() }
            ?.let { File(it).let { dir -> if (dir.isAbsolute) dir else File(workspace, it) } }
            ?: File(workspace, "target")
        val file = File(targetDir, "release/$fileName")
        check(file.isFile) { "host library not found at $file" }
        return file
    }
}

/**
 * Builds a language file in [packsDir] from each word list in
 * `dictionaries/<locale>/`, with the engine's compiler (dictc). The languages in
 * [builtIn] also go into the APK's assets, with `languages/official.tsv` listing
 * every language file of this build: the files the app accepts.
 */
abstract class CompileDictionariesTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    /** The Rust sources, dictc included. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Every file under [dictionariesDir], so editing a word list rebuilds. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wordLists: ConfigurableFileCollection

    @get:Internal
    abstract val dictionariesDir: DirectoryProperty

    /** Languages built into the APK. */
    @get:Input
    abstract val builtIn: ListProperty<String>

    /** Assets: the built-in dictionaries and the list of official files. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Every language file. */
    @get:OutputDirectory
    abstract val packsDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val packs = packsDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val assets = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val lists = dictionariesDir.get().asFile.listFiles().orEmpty()
            .filter { File(it, "words.tsv").isFile }
            .sortedBy { it.name }
        check(lists.isNotEmpty()) { "no word lists under ${dictionariesDir.get().asFile}" }
        val missing = builtIn.get().filter { tag -> lists.none { it.name == tag } }
        check(missing.isEmpty()) { "no word list for the built-in languages $missing" }
        val official = StringBuilder("# tag<TAB>sha256<TAB>name<TAB>layout\n")
        for (dir in lists) {
            val pack = File(packs, "${dir.name}.pkd")
            exec.exec {
                workingDir = workspaceDir.get().asFile
                executable = "cargo"
                args(
                    "run", "--release", "--locked", "--quiet", "--package", "pingolin-keyboard-dictc", "--",
                    "compile", dir.absolutePath, pack.absolutePath,
                )
            }
            check(pack.isFile) { "dictc succeeded but wrote no dictionary for ${dir.name}" }
            val meta = File(dir, "metadata.tsv").readLines()
                .filter { !it.startsWith("#") && '\t' in it }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
            official.append(dir.name).append('\t').append(sha256(pack)).append('\t')
                .append(meta["name"] ?: dir.name).append('\t').append(meta["layout"] ?: "letters").append('\n')
            if (dir.name in builtIn.get()) {
                pack.copyTo(File(assets, "dictionaries/${dir.name}.pkd"), overwrite = true)
            }
        }
        File(assets, "languages").mkdirs()
        File(assets, "languages/official.tsv").writeText(official.toString())
    }

    private fun sha256(file: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

/** The emoji tables, as assets/emoji/list.tsv and assets/emoji/names/<tag>.tsv, with their license. */
abstract class EmojiAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tablesDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val from = tablesDir.get().asFile
        val into = File(outputDir.get().asFile.apply { deleteRecursively() }, "emoji")
        val names = File(from, "names").listFiles { f -> f.name.endsWith(".tsv") }.orEmpty()
        check(names.isNotEmpty()) { "no emoji names under $from" }
        File(from, "list.tsv").copyTo(File(into, "list.tsv"))
        names.forEach { it.copyTo(File(into, "names/${it.name}")) }
        license.get().asFile.copyTo(File(into, "LICENSE.txt"))
    }
}
