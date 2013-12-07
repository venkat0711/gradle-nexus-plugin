/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.plugins.nexus

import org.gradle.api.plugins.MavenPlugin
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.Task
import spock.lang.Specification

import static org.spockframework.util.Assert.fail

class NexusPluginSpec extends Specification {
    final static M2_HOME_DIR = new File(System.properties['user.home'], '.m2/repository')
    File integTestDir
    File buildFile

    def setup() {
        integTestDir = new File('build/integTest')

        if(!integTestDir.mkdirs()) {
            fail('Unable to create integration test directory.')
        }

        buildFile = new File(integTestDir, 'build.gradle')

        if(!buildFile.createNewFile()) {
            fail('Unable to create Gradle build script.')
        }

        buildFile << """
buildscript {
    dependencies {
        classpath files('../classes/main')
    }
}

apply plugin: 'java'
apply plugin: org.gradle.api.plugins.nexus.NexusPlugin

"""
    }

    def cleanup() {
        if(!buildFile.delete()) {
            fail('Unable to delete Gradle build script.')
        }

        if(!integTestDir.deleteDir()) {
            fail('Unable to delete integration test directory.')
        }
    }

    def "Adds sources and Javadoc JAR tasks by default for Java project"() {
        when:
        GradleProject project = runTasks(integTestDir, 'tasks')

        then:
        Task sourcesJarTask = project.tasks.find { task -> task.name == 'sourcesJar' }
        sourcesJarTask
        sourcesJarTask.description == 'Assembles a jar archive containing the main sources of this project.'
        Task javadocJarTask = project.tasks.find { task -> task.name == 'javadocJar' }
        javadocJarTask
        javadocJarTask.description == 'Assembles a jar archive containing the generated Javadoc API documentation of this project.'
        !project.tasks.find { task -> task.name == 'testsJar' }
    }

    def "Adds sources and Javadoc JAR tasks by default for Groovy project"() {
        when:
        GradleProject project = runTasks(integTestDir, 'tasks')

        then:
        Task sourcesJarTask = project.tasks.find { task -> task.name == 'sourcesJar' }
        sourcesJarTask
        sourcesJarTask.description == 'Assembles a jar archive containing the main sources of this project.'
        Task javadocJarTask = project.tasks.find { task -> task.name == 'javadocJar' }
        javadocJarTask
        javadocJarTask.description == 'Assembles a jar archive containing the generated Javadoc API documentation of this project.'
        !project.tasks.find { task -> task.name == 'testsJar' }
    }

    def "Adds tests JAR task if configured"() {
        when:
        buildFile << """
nexus {
    attachTests = true
}
"""
        GradleProject project = runTasks(integTestDir, 'tasks')

        then:
        Task sourcesJarTask = project.tasks.find { task -> task.name == 'sourcesJar' }
        sourcesJarTask
        sourcesJarTask.description == 'Assembles a jar archive containing the main sources of this project.'
        Task javadocJarTask = project.tasks.find { task -> task.name == 'javadocJar' }
        javadocJarTask
        javadocJarTask.description == 'Assembles a jar archive containing the generated Javadoc API documentation of this project.'
        Task testsJarTask = project.tasks.find { task -> task.name == 'testsJar'}
        testsJarTask
        testsJarTask.description == 'Assembles a jar archive containing the test sources of this project.'
    }

    def "Disables additional JAR creation"() {
        when:
        buildFile << """
nexus {
    attachSources = false
    attachJavadoc = false
}
"""
        GradleProject project = runTasks(integTestDir, 'tasks')

        then:
        !project.tasks.find { task -> task.name == 'sourcesJar'}
        !project.tasks.find { task -> task.name == 'javadocJar'}
        !project.tasks.find { task -> task.name == 'testsJar'}
    }

    def "Creates all configured JARs"() {
        when:
        buildFile << """
nexus {
    attachTests = true
}
"""
        GradleProject project = runTasks(integTestDir, 'assemble')

        then:
        File libsDir = new File(integTestDir, 'build/libs')
        def assembledFileNames = libsDir.listFiles()*.name
        assembledFileNames.find { it ==~ "${project.name}.jar" }
        assembledFileNames.find { it ==~ "${project.name}-javadoc.jar" }
        assembledFileNames.find { it ==~ "${project.name}-sources.jar" }
        assembledFileNames.find { it ==~ "${project.name}-tests.jar" }
    }

    def "Uploads all configured JARs and metadata for release version"() {
        when:
        buildFile << """
version = '1.0'
group = 'org.gradle.mygroup'

nexus {
    attachTests = true
    repositoryUrl = 'file://$integTestDir.canonicalPath/repo'
}
"""
        GradleProject project = runTasks(integTestDir, 'uploadArchives')

        then:
        File repoDir = new File(integTestDir, 'repo/org/gradle/mygroup/integTest/1.0')
        def repoFileNames = repoDir.listFiles()*.name
        repoFileNames.find { it ==~ "${project.name}-1.0.jar" }
        repoFileNames.find { it ==~ "${project.name}-1.0.pom" }
        repoFileNames.find { it ==~ "${project.name}-1.0-javadoc.jar" }
        repoFileNames.find { it ==~ "${project.name}-1.0-sources.jar" }
        repoFileNames.find { it ==~ "${project.name}-1.0-tests.jar" }
    }

    def "Uploads all configured JARs and metadata for snapshot version"() {
        when:
        buildFile << """
version = '1.0-SNAPSHOT'
group = 'org.gradle.mygroup'

nexus {
    attachTests = true
    snapshotRepositoryUrl = 'file://$integTestDir.canonicalPath/repo'
}
"""
        GradleProject project = runTasks(integTestDir, 'uploadArchives')

        then:
        File repoDir = new File(integTestDir, 'repo/org/gradle/mygroup/integTest/1.0-SNAPSHOT')
        def repoFileNames = repoDir.listFiles()*.name
        repoFileNames.find { it ==~ "${project.name}-1\\.0-\\d+\\.\\d+-1\\.jar" }
        repoFileNames.find { it ==~ "${project.name}-1\\.0-\\d+\\.\\d+-1\\.pom" }
        repoFileNames.find { it ==~ "${project.name}-1\\.0-\\d+\\.\\d+-1\\-javadoc.jar" }
        repoFileNames.find { it ==~ "${project.name}-1\\.0-\\d+\\.\\d+-1\\-sources.jar" }
        repoFileNames.find { it ==~ "${project.name}-1\\.0-\\d+\\.\\d+-1\\-tests.jar" }
    }

    def "Installs all configured JARs and metadata for release version"() {
        setup:
        def projectCoordinates = [group: 'org.gradle.mygroup', name: 'integTest', version: '1.0']
        File installationDir = new File(M2_HOME_DIR, createInstallationDir(projectCoordinates))
        deleteMavenLocalInstallationDir(installationDir)

        when:
        buildFile << """
version = '$projectCoordinates.version'
group = '$projectCoordinates.group'

nexus {
    attachTests = true
}
"""
        runTasks(integTestDir, MavenPlugin.INSTALL_TASK_NAME)

        then:
        def repoFileNames = installationDir.listFiles()*.name
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}.pom" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-javadoc.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-sources.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-tests.jar" }
    }

    def "Installs all configured JARs and metadata for snapshot version"() {
        setup:
        def projectCoordinates = [group: 'org.gradle.mygroup', name: 'integTest', version: '1.0-SNAPSHOT']
        File installationDir = new File(M2_HOME_DIR, createInstallationDir(projectCoordinates))
        deleteMavenLocalInstallationDir(installationDir)

        when:
        buildFile << """
version = '$projectCoordinates.version'
group = '$projectCoordinates.group'

nexus {
    attachTests = true
}
"""
        runTasks(integTestDir, MavenPlugin.INSTALL_TASK_NAME)

        then:
        def repoFileNames = installationDir.listFiles()*.name
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}.pom" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-javadoc.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-sources.jar" }
        repoFileNames.find { it ==~ "${projectCoordinates.name}-${projectCoordinates.version}-tests.jar" }
    }

    private void deleteMavenLocalInstallationDir(File installationDir) {
        if(installationDir.exists()) {
            boolean success = installationDir.deleteDir()

            if(!success) {
                fail("Unable to delete existing Maven Local repository directory '$installationDir.canonicalPath'.")
            }
        }
    }

    private String createInstallationDir(projectCoordinates) {
        "${projectCoordinates.group.replaceAll('\\.', '/')}/$projectCoordinates.name/$projectCoordinates.version"
    }

    private GradleProject runTasks(File projectDir, String... tasks) {
        ProjectConnection connection = GradleConnector.newConnector().forProjectDirectory(projectDir).connect()

        try {
            BuildLauncher builder = connection.newBuild()
            builder.forTasks(tasks).run()
            return connection.getModel(GradleProject)
        }
        finally {
            connection?.close()
        }
    }
}