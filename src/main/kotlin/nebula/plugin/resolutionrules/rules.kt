/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package nebula.plugin.resolutionrules

import com.netflix.nebula.interop.VersionWithSelector
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.dsl.ModuleVersionSelectorParsers
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import java.io.Serializable

interface Rule {
    fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    )
}

interface BasicRule : Rule {
    var ruleSet: String?
    val reason: String
    val author: String
    val date: String
}

interface ModuleRule : BasicRule {
    val module: String
}

data class RuleSet(
    var name: String?,
    val replace: List<ReplaceRule> = emptyList(),
    val substitute: List<SubstituteRule> = emptyList(),
    val reject: List<RejectRule> = emptyList(),
    val deny: List<DenyRule> = emptyList(),
    val exclude: List<ExcludeRule> = emptyList(),
    val align: List<AlignRule> = emptyList()
) {

    fun dependencyRulesPartOne() =
        listOf(replace, substitute, reject, deny, exclude).flatten()

    fun dependencyRulesPartTwo(coreAlignmentEnabled: Boolean) =
        if (coreAlignmentEnabled)
            listOf(align).flatten()
        else
            emptyList()

    fun resolveRules(coreAlignmentEnabled: Boolean) =
        if (coreAlignmentEnabled)
            emptyList()
        else
            listOf(AlignRules(align))

    fun generateAlignmentBelongsToName() {
        align.forEachIndexed { index, alignRule ->
            var abbreviatedAlignGroup = alignRule.group.toString()
                .replace("|", "-or-")

            val onlyAlphabeticalRegex = Regex("[^A-Za-z.\\-]")
            abbreviatedAlignGroup = onlyAlphabeticalRegex.replace(abbreviatedAlignGroup, "")

            alignRule.belongsToName = "$name-$index-for-$abbreviatedAlignGroup"
        }
    }
}

fun RuleSet.withName(ruleSetName: String): RuleSet {
    name = ruleSetName
    listOf(replace, substitute, reject, deny, exclude, align).flatten().forEach { it.ruleSet = ruleSetName }
    generateAlignmentBelongsToName()
    return this
}

fun Collection<RuleSet>.flatten() = RuleSet(
    "flattened",
    flatMap { it.replace },
    flatMap { it.substitute },
    flatMap { it.reject },
    flatMap { it.deny },
    flatMap { it.exclude },
    flatMap { it.align })

data class ReplaceRule(
    override val module: String,
    val with: String,
    override var ruleSet: String?,
    override val reason: String,
    override val author: String,
    override val date: String
) : ModuleRule {
    private val moduleId = ModuleIdentifier.valueOf(module)
    private val withModuleId = ModuleIdentifier.valueOf(with)

    override fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    ) {
        project.dependencies.modules.module(moduleId.toString()) {
            val details = it as ComponentModuleMetadataDetails
            val message =
                "replaced ${moduleId.organization}:${moduleId.name} -> ${withModuleId.organization}:${withModuleId.name} because '$reason' by rule $ruleSet"
            details.replacedBy(withModuleId.toString(), message)
        }
    }
}

data class SubstituteRule(
    val module: String, val with: String, override var ruleSet: String?,
    override val reason: String, override val author: String, override val date: String
) : BasicRule, Serializable {
    private lateinit var substitutedModule: ComponentSelector
    private lateinit var withComponentSelector: ModuleComponentSelector
    private lateinit var withVersionSelector: ModuleVersionSelector
    private val versionSelector by lazy {
        val version = (substitutedModule as ModuleComponentSelector).version
        VersionWithSelector(version).asSelector()
    }

    override fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    ) {
        val substitution = resolutionStrategy.dependencySubstitution
        if (!this::substitutedModule.isInitialized) {
            substitutedModule = substitution.module(module)
            val withModule = substitution.module(with)
            if (withModule !is ModuleComponentSelector) {
                throw SubstituteRuleMissingVersionException(with, this)
            }
            withComponentSelector = substitution.module(with) as ModuleComponentSelector
            withVersionSelector = ModuleVersionSelectorParsers.parser().parseNotation(withComponentSelector.displayName)
        }

        if (substitutedModule is ModuleComponentSelector) {
            // We use eachDependency because dependencySubstitutions.all causes configuration task dependencies to resolve at configuration time
            resolutionStrategy.eachDependency { details ->
                val requested = details.requested
                val moduleSelector = substitutedModule as ModuleComponentSelector
                if (requested.group == moduleSelector.group && requested.module.name == moduleSelector.module) {
                    val requestedSelectorVersion = requested.version
                    if (versionSelector.accept(requestedSelectorVersion)
                        && !requested.toString().contains(".+")
                        && !requested.toString().contains("latest")
                    ) {
                        // Note on `useTarget`:
                        // Forcing modules via ResolutionStrategy.force(Object...) uses this capability.
                        // from https://docs.gradle.org/current/javadoc/org/gradle/api/artifacts/DependencyResolveDetails.html
                        details.useTarget(withVersionSelector) // We can't pass a ModuleComponentSelector here so we take the conversion hit
                        details.because("substituted $substitutedModule with $withComponentSelector because '$reason' by rule $ruleSet")
                    }
                }
            }
        } else {
            var message = "substituted $withComponentSelector because '$reason' by rule $ruleSet"

            val selectorNameSections = substitutedModule.displayName.split(":")
            if (selectorNameSections.size > 2) {
                val selectorGroupAndArtifact = "${selectorNameSections[0]}:${selectorNameSections[1]}"
                message =
                    "substituted $selectorGroupAndArtifact with $withComponentSelector because '$reason' by rule $ruleSet"
            }

            resolutionStrategy.dependencySubstitution {
                it.substitute(substitutedModule)
                    .because(message)
                    .with(withComponentSelector)
            }
        }

    }
}

data class RejectRule(
    override val module: String,
    override var ruleSet: String?,
    override val reason: String,
    override val author: String,
    override val date: String
) : ModuleRule {
    private val moduleId = ModuleVersionIdentifier.valueOf(module)
    private val versionSelector = VersionWithSelector(moduleId.version).asSelector()

    override fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    ) {
        resolutionStrategy.componentSelection.all { selection ->
            val candidate = selection.candidate
            if (candidate.group == moduleId.organization && candidate.module == moduleId.name) {
                if (!moduleId.hasVersion() || versionSelector.accept(candidate.version)) {
                    val message = "rejected by rule $ruleSet because '$reason'"
                    selection.reject(message)
                }
            }
        }
    }
}

data class DenyRule(
    override val module: String,
    override var ruleSet: String?,
    override val reason: String,
    override val author: String,
    override val date: String
) : ModuleRule {
    private val moduleId = ModuleVersionIdentifier.valueOf(module)

    override fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    ) {
        val match = configuration.allDependencies.find {
            it is ExternalModuleDependency && it.group == moduleId.organization && it.name == moduleId.name
        }
        if (match != null && (!moduleId.hasVersion() || match.version == moduleId.version)) {
            resolutionStrategy.componentSelection.withModule(
                "${moduleId.organization}:${moduleId.name}",
                Action<ComponentSelection> { selection ->
                    val message = "denied by rule $ruleSet because '$reason'"
                    selection.reject(message)
                })
            throw DependencyDeniedException(moduleId, this)
        }
    }
}

data class ExcludeRule(
    override val module: String,
    override var ruleSet: String?,
    override val reason: String,
    override val author: String,
    override val date: String
) : ModuleRule {
    private val logger: Logger = Logging.getLogger(ExcludeRule::class.java)
    private val moduleId = ModuleIdentifier.valueOf(module)

    @Override
    override fun apply(
        project: Project,
        configuration: Configuration,
        resolutionStrategy: ResolutionStrategy,
        extension: NebulaResolutionRulesExtension
    ) {
        val message =
            "excluded ${moduleId.organization}:${moduleId.name} and transitive dependencies for all dependencies of this configuration by rule $ruleSet"
        logger.debug(message)
        // TODO: would like a core Gradle feature that accepts a reason
        configuration.exclude(moduleId.organization, moduleId.name)

        resolutionStrategy.componentSelection.withModule("${moduleId.organization}:${moduleId.name}") { selection ->
            selection.reject(message)
        }
    }
}

class DependencyDeniedException(moduleId: ModuleVersionIdentifier, rule: DenyRule) :
    Exception("Dependency $moduleId denied by rule ${rule.ruleSet}")

class SubstituteRuleMissingVersionException(moduleId: String, rule: SubstituteRule) :
    Exception("The dependency to be substituted ($moduleId) must have a version. Rule ${rule.ruleSet} is invalid")

fun Configuration.exclude(group: String, module: String) {
    exclude(mapOf("group" to group, "module" to module))
}