// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package keyboard.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails the build when a merged manifest breaks [ManifestPolicy]. */
abstract class VerifyManifestPolicyTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val backupRules: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        val problems = ManifestPolicy.check(manifest.readText(), backupRules.get().asFile.readText())
        report.get().asFile.writeText(if (problems.isEmpty()) "OK\n" else problems.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException("Manifest policy failed for $manifest:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}

/** Fails the build when a variant's runtime dependencies break [DependencyPolicy]. */
abstract class VerifyDependencyPolicyTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val (modules, unresolved) = walk(rootComponent.get())
        val problems = DependencyPolicy.check(modules) + unresolved.map { "unresolved dependency: $it" }
        val lines = modules.sorted() + "" + (problems.ifEmpty { listOf("OK") })
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException("Dependency policy failed:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    private fun walk(root: ResolvedComponentResult): Pair<Set<String>, Set<String>> {
        val seen = mutableSetOf<ComponentIdentifier>()
        val modules = sortedSetOf<String>()
        val unresolved = sortedSetOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { modules += "${it.group}:${it.module}:${it.version}" }
            for (dependency in component.dependencies) {
                when (dependency) {
                    is ResolvedDependencyResult -> queue += dependency.selected
                    is UnresolvedDependencyResult -> unresolved += dependency.requested.displayName
                }
            }
        }
        return modules to unresolved
    }
}
