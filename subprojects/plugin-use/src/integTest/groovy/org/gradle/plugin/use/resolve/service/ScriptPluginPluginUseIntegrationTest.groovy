/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.plugin.use.resolve.service

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.test.matchers.UserAgentMatcher
import org.gradle.util.GradleVersion
import org.junit.Rule

import static org.hamcrest.CoreMatchers.allOf
import static org.hamcrest.CoreMatchers.endsWith
import static org.hamcrest.CoreMatchers.startsWith


class ScriptPluginPluginUseIntegrationTest extends AbstractIntegrationSpec {

    @Rule
    HttpServer server = new HttpServer()

    def "project script can apply from script file using the plugins block"() {

        given:
        file("gradle/other.gradle") << "println('Hello from the other side')"

        and:
        buildFile << """

            plugins {
                id "other" from "gradle/other.gradle"
            }

            plugins.withId("other") {
                println("The other plugin was found: \$it")
            }
            
        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side")
        output.contains("The other plugin was found: id 'other' from 'gradle/other.gradle'")
    }

    def "script plugin can be applied using the plugins block from a remote URL"() {

        given:
        def script = file("other.gradle") << "println('Hello from the other side')"

        and:
        server.expectUserAgent(UserAgentMatcher.matchesNameAndVersion("Gradle", GradleVersion.current().getVersion()))
        server.expectGet("/remote-script-plugin.gradle", script)
        server.start()

        and:
        buildFile << """

            plugins {
                id "other" from "http://localhost:${server.port}/remote-script-plugin.gradle"
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side")
    }

    def "script plugin applied using the plugins block can use classpath dependencies via the buildscript block"() {

        given:
        file("gradle/other.gradle") << """

            buildscript {
                repositories { jcenter() }
                dependencies {
                    classpath("org.apache.commons:commons-lang3:3.6")
                }
            }
            
            println(org.apache.commons.lang3.StringUtils.reverse("Gradle"))

        """.stripIndent()

        and:
        buildFile << """

            plugins {
                id "other" from "gradle/other.gradle"
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("eldarG")
    }

    def "reasonable error message when script plugin file applied from the plugins block does not exists"() {

        given:
        buildFile << """
            plugins {
                id "other" from "gradle/other.gradle"
            }
        """.stripIndent()

        when:
        fails "help"

        then:
        failure.assertHasCause("Failed to apply plugin [id 'other']")
        failure.assertThatCause(allOf(startsWith("Could not read script"), endsWith("other.gradle' as it does not exist.")))
    }

    def "reasonable error message when script plugin URL applied from the plugins block does not exists"() {

        given:
        server.expectUserAgent(UserAgentMatcher.matchesNameAndVersion("Gradle", GradleVersion.current().getVersion()))
        server.expectGetMissing("/do-not-exists.gradle")
        server.start()

        and:
        buildFile << """
            plugins {
                id "other" from "http://localhost:${server.port}/do-not-exists.gradle"
            }
        """.stripIndent()

        when:
        fails "help"

        then:
        failure.assertHasCause("Failed to apply plugin [id 'other']")
        failure.assertHasCause("Could not read script 'http://localhost:${server.port}/do-not-exists.gradle' as it does not exist.")
    }
}
