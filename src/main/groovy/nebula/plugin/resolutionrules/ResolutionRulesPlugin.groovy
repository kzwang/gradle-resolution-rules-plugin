/*
 * Copyright 2015-2016 Netflix, Inc.
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

import com.google.common.io.Files
import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolutionStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ResolutionRulesPlugin implements Plugin<Project> {
    Project project
    Logger logger = LoggerFactory.getLogger(ResolutionRulesPlugin)
    String configurationName = "resolutionRules"

    public void apply(Project project) {
        this.project = project
        Configuration configuration = project.configurations.create(configurationName)
        def extension = project.extensions.create('nebulaResolutionRules', NebulaResolutionRulesExtension)

        project.gradle.projectsEvaluated {
            Rules rules = rulesFromConfiguration(configuration, extension)
            project.configurations.all ( { Configuration config ->
                if (config.name == configurationName) {
                    return
                }
                if (config.state != Configuration.State.UNRESOLVED) {
                    logger.warn("Configuration '{}' has been resolved. Dependency resolution rules will not be applied", config.name)
                    return
                }
                config.resolutionStrategy { ResolutionStrategy rs ->
                    rules.configurationRules().each { ConfigurationRule rule ->
                        rule.apply(config)
                    }
                    rules.resolutionRules().each { ResolutionRule rule ->
                        rule.apply(rs)
                    }
                    rules.projectConfigurationRules().each { ProjectConfigurationRule rule ->
                        rule.apply(project, rs, config, extension)
                    }
                }
            } )
            rules.projectRules().each { ProjectRule rule -> rule.apply(project) }
        }
    }

    private Rules rulesFromConfiguration(Configuration configuration, NebulaResolutionRulesExtension extension) {
        List<Rules> rules = new ArrayList<Rules>();
        Set<File> files = configuration.copyRecursive().resolve()
        if (files.isEmpty()) {
            logger.warn("No resolution rules have been added to the '{}' configuration", configuration.name)
        }
        for (file in files) {
            if (isIncludedRuleFile(file.name, extension)) {
                ResolutionJsonValidator.validateJsonFile(file)
                logger.info("Using $file as a dependency rules source")
                rules.add(parseJsonFile(file))
            } else if (file.name.endsWith(".jar") || file.name.endsWith(".zip")) {
                logger.info("Using $file as a dependency rules source")
                ZipFile jar = new ZipFile(file)
                try {
                    Enumeration<? extends ZipEntry> entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement()
                        if (isIncludedRuleFile(entry.name, extension)) {
                            ResolutionJsonValidator.validateJsonStream(jar.getInputStream(entry))
                            rules.add(parseJsonStream(jar.getInputStream(entry)))
                        }
                    }
                } finally {
                    jar.close()
                }
            } else {
                logger.error("Unsupported rules file extension for $file")
            }
        }
        return flattenRules(rules)
    }

    private static boolean isIncludedRuleFile(String filename, NebulaResolutionRulesExtension extension) {
        if (filename.endsWith(".json")) {
            String nameWithoutExtension = filename.replace(".json", "")
            if (nameWithoutExtension.startsWith("optional-")) {
                return extension.include.contains(nameWithoutExtension.replaceFirst("optional-", ""))
            } else {
                return !extension.exclude.contains(nameWithoutExtension)
            }
        }
        return false
    }

    static Rules parseJsonFile(File file) {
        rulesFromJson(new JsonSlurper().parse(file) as Map)
    }

    static Rules parseJsonText(String json) {
        rulesFromJson(new JsonSlurper().parseText(json) as Map)
    }

    static Rules parseJsonStream(InputStream stream) {
        rulesFromJson(new JsonSlurper().parse(stream) as Map)
    }

    static Rules rulesFromJson(Map json) {
        Rules rules = new Rules()
        rules.replace = json.replace.collect { new ReplaceRule(it) }
        rules.substitute = json.substitute.collect { new SubstituteRule(it) }
        rules.reject = json.reject.collect { new RejectRule(it) }
        rules.deny = json.deny.collect { new DenyRule(it) }
        rules.align = json.align.collect { new AlignRule(it) }

        rules
    }

    private static Rules flattenRules(Iterable<Rules> rules) {
        List<ReplaceRule> replace = rules.collectMany { it.replace }.flatten() as List<ReplaceRule>
        List<SubstituteRule> substitute = rules.collectMany { it.substitute }.flatten() as List<SubstituteRule>
        List<RejectRule> reject = rules.collectMany { it.reject }.flatten() as List<RejectRule>
        List<DenyRule> deny = rules.collectMany { it.deny }.flatten() as List<DenyRule>
        List<AlignRule> align = rules.collectMany { it.align }.flatten() as List<AlignRule>
        return new Rules(replace: replace, substitute: substitute, reject: reject, deny: deny, align: align)
    }
}
