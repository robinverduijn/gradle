/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.tooling.internal.consumer.protocoladapter

import org.gradle.tooling.internal.consumer.versioning.VersionDetails
import org.gradle.tooling.internal.idea.DefaultIdeaModuleDependency
import org.gradle.tooling.internal.idea.DefaultIdeaSingleEntryLibraryDependency
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.gradle.util.Matchers
import spock.lang.Specification
import org.gradle.tooling.internal.consumer.*

/**
 * by Szczepan Faber, created at: 4/2/12
 */
class ProtocolToModelAdapterTest extends Specification {
    final ProtocolToModelAdapter adapter = new ProtocolToModelAdapter()
    final VersionDetails versionDetails = Mock()

    def createsProxyAdapterForProtocolModel() {
        TestProtocolModel protocolModel = Mock()

        expect:
        adapter.adapt(TestModel.class, protocolModel, versionDetails) instanceof TestModel
    }

    def proxiesAreEqualWhenTargetProtocolObjectsAreEqual() {
        TestProtocolModel protocolModel1 = Mock()
        TestProtocolModel protocolModel2 = Mock()

        def model = adapter.adapt(TestModel.class, protocolModel1, versionDetails)
        def equal = adapter.adapt(TestModel.class, protocolModel1, versionDetails)
        def different = adapter.adapt(TestModel.class, protocolModel2, versionDetails)

        expect:
        Matchers.strictlyEquals(model, equal)
        model != different
    }

    def methodInvocationOnModelDelegatesToTheProtocolModelObject() {
        TestProtocolModel protocolModel = Mock()
        _ * protocolModel.getName() >> 'name'

        expect:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.name == 'name'
    }

    def createsProxyAdapterForMethodReturnValue() {
        TestProtocolModel protocolModel = Mock()
        TestProtocolProject protocolProject = Mock()
        _ * protocolModel.getProject() >> protocolProject
        _ * protocolProject.getName() >> 'name'

        expect:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.project instanceof TestProject
        model.project.name == 'name'
    }

    def doesNotAdaptNullReturnValue() {
        TestProtocolModel protocolModel = Mock()
        _ * protocolModel.getProject() >> null

        expect:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.project == null
    }

    def adaptsIterableToDomainObjectSet() {
        TestProtocolModel protocolModel = Mock()
        TestProtocolProject protocolProject = Mock()
        _ * protocolModel.getChildren() >> [protocolProject]
        _ * protocolProject.getName() >> 'name'

        expect:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.children.size() == 1
        model.children[0] instanceof TestProject
        model.children[0].name == 'name'
    }

    def cachesPropertyValues() {
        TestProtocolModel protocolModel = Mock()
        TestProtocolProject protocolProject = Mock()
        _ * protocolModel.getProject() >> protocolProject
        _ * protocolModel.getChildren() >> [protocolProject]
        _ * protocolProject.getName() >> 'name'

        expect:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.project.is(model.project)
        model.children.is(model.children)
    }

    def reportsMethodWhichDoesNotExistOnProtocolObject() {
        PartialTestProtocolModel protocolModel = Mock()

        when:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.project

        then:
        UnsupportedMethodException e = thrown()
        e.message.contains "TestModel.getProject()"
    }

    def propagatesExceptionThrownByProtocolObject() {
        TestProtocolModel protocolModel = Mock()
        RuntimeException failure = new RuntimeException()

        when:
        def model = adapter.adapt(TestModel.class, protocolModel, versionDetails)
        model.name

        then:
        protocolModel.name >> { throw failure }
        RuntimeException e = thrown()
        e == failure
    }

    def "adapts idea dependencies"() {
        def libraryDep = new GroovyClassLoader().loadClass(DefaultIdeaSingleEntryLibraryDependency.class.getCanonicalName()).newInstance()
        def moduleDep = new GroovyClassLoader().loadClass(DefaultIdeaModuleDependency.class.getCanonicalName()).newInstance()

        when:
        def library = adapter.adapt(IdeaDependency.class, libraryDep, versionDetails)
        def module  = adapter.adapt(IdeaDependency.class, moduleDep, versionDetails)

        then:
        library instanceof IdeaSingleEntryLibraryDependency
        module instanceof IdeaModuleDependency
    }
}
