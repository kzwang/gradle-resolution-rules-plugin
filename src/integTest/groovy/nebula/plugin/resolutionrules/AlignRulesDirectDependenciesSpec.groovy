package nebula.plugin.resolutionrules

import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import spock.lang.Unroll

class AlignRulesDirectDependenciesSpec extends AbstractAlignRulesSpec {

    @Unroll
    def 'can align direct dependencies if necessary'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:0.15.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:0.15.0')
                .build()
        File mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen").generateTestMavenRepo()

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories {
                maven { url '${mavenrepo.absolutePath}' }
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
                implementation 'test.nebula:b:0.15.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=$coreAlignment")

        then:
        result.output.contains '+--- test.nebula:a:1.0.0\n'
        result.output.contains '\\--- test.nebula:b:0.15.0 -> 1.0.0\n'

        where:
        coreAlignment << [false, true]
    }

    @Unroll
    def 'can align direct dependencies from ivy repositories'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:0.15.0')
                .addModule('test.nebula:b:1.0.0')
                .addModule('test.nebula:b:0.15.0')
                .build()
        GradleDependencyGenerator ivyrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen")
        ivyrepo.generateTestIvyRepo()

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories {
                ${ivyrepo.ivyRepositoryBlock}
            }
            dependencies {
                implementation 'test.nebula:a:1.0.0'
                implementation 'test.nebula:b:0.15.0'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=$coreAlignment")

        then:
        result.output.contains '+--- test.nebula:a:1.0.0\n'
        result.output.contains '\\--- test.nebula:b:0.15.0 -> 1.0.0\n'

        where:
        coreAlignment << [false, true]
    }

    @Unroll
    def 'can align dynamic dependencies'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.0.1')
                .build()
        File mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen").generateTestMavenRepo()

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories {
                maven { url '${mavenrepo.absolutePath}' }
            }
            dependencies {
                implementation 'test.nebula:a:1.+'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=$coreAlignment")

        then:
        result.output.contains '\\--- test.nebula:a:1.+ -> 1.0.1\n'

        where:
        coreAlignment << [false, true]
    }

    @Unroll
    def 'can align dynamic range dependencies'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:a:1.0.0')
                .addModule('test.nebula:a:1.0.1')
                .build()
        File mavenrepo = new GradleDependencyGenerator(graph, "${projectDir}/testrepogen").generateTestMavenRepo()

        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "test.nebula",
                        "reason": "Align test.nebula dependencies",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories {
                maven { url '${mavenrepo.absolutePath}' }
            }
            dependencies {
                implementation 'test.nebula:a:[1.0.0, 2.0.0)'
            }
        """.stripIndent()

        when:
        def result = runTasks('dependencies', '--configuration', 'compileClasspath', "-Dnebula.features.coreAlignmentSupport=$coreAlignment")

        then:
        result.output.contains '\\--- test.nebula:a:[1.0.0, 2.0.0) -> 1.0.1\n'

        where:
        coreAlignment << [false, true]
    }

    @Unroll
    def 'unresolvable dependencies cause assemble to fail'() {
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "com.google.guava",
                        "reason": "Align guava",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories { mavenCentral() }
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.21'
                implementation 'com.google.guava:guava:oops'
            }
        """

        writeHelloWorld('com.netflix.nebula')

        when:
        org.gradle.testkit.runner.BuildResult result = runTasksAndFail('assemble', "-Dnebula.features.coreAlignmentSupport=$coreAlignment")

        then:
        result.output.contains("Could not resolve all files for configuration ':compileClasspath'.")
        result.output.contains("Could not find com.google.guava:guava:oops.")

        where:
        coreAlignment << [false, true]
    }

    @Unroll('unresolvable dependencies do not cause #tasks to fail')
    def 'unresolvable dependencies do not cause dependencies tasks to fail'() {
        buildFile.delete()
        buildFile << """\
            apply plugin: 'java'

            repositories { mavenCentral() }

            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.21'
                implementation 'com.google.guava:guava:oops'
            }
        """.stripIndent()

        when:
        runTasks(*tasks)

        then:
        noExceptionThrown()

        where:
        tasks | _
        ['dependencies', '--configuration', 'compileClasspath'] | _
        ['dependencyInsight', '--dependency', 'guava'] | _
    }

    @Unroll('unresolvable dependencies do not cause #tasks to fail when align rules present')
    def 'unresolvable dependencies do not cause tasks to fail when align rules present'() {
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "name": "testNebula",
                        "group": "com.google.guava",
                        "reason": "Align guava",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            repositories { mavenCentral() }
            dependencies {
                implementation 'org.slf4j:slf4j-api:1.7.21'
                implementation 'com.google.guava:guava:oops'
            }
        """

        when:
        def result = runTasks(*tasks)

        then:
        noExceptionThrown()
        result.output.contains("Resolution rules could not resolve all dependencies to align configuration ':compileClasspath'")

        where:
        tasks | _
        ['dependencies', '--configuration', 'compileClasspath'] | _
        ['dependencyInsight', '--dependency', 'guava', '--configuration', 'compileClasspath'] | _
    }

    @Unroll('unresolvable dependencies caused by alignment do not cause #tasks to fail')
    def 'unresolvable dependencies caused by alignment do not cause the build to fail'() {
        rulesJsonFile << '''\
            {
                "deny": [], "reject": [], "substitute": [], "replace": [],
                "align": [
                    {
                        "group": "io.netty",
                        "reason": "Align Netty",
                        "author": "Example Person <person@example.org>",
                        "date": "2016-03-17T20:21:20.368Z"
                    }
                ]
            }
        '''.stripIndent()

        buildFile << """\
            configurations.all {
                resolutionStrategy {
                    force 'io.netty:netty-all:4.0.43.Final'
                }
            }

            repositories { mavenCentral() }

            dependencies {
                implementation 'io.grpc:grpc-netty:1.3.0' // grpc-netty brings in dependencies added in Netty 4.1, and will be broken by the force
                implementation 'io.netty:netty-all:4.0.43.Final'
            }
        """

        when:
        def result = runTasks(*tasks)

        then:
        noExceptionThrown()
        result.output.contains("Resolution rules could not resolve all dependencies to align configuration ':compileClasspath'")

        where:
        tasks | _
        ['dependencies', '--configuration', 'compileClasspath'] | _
        ['dependencyInsight', '--dependency', 'guava', '--configuration', 'compileClasspath'] | _
    }
}
