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

package org.gradle.api.internal.artifacts.repositories.resolver;

import org.gradle.api.artifacts.DirectDependencyMetadata;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;

import java.util.List;

public class DirectDependencyMetadataAdapter extends AbstractDependencyMetadataAdapter<DirectDependencyMetadata> implements DirectDependencyMetadata {

    public DirectDependencyMetadataAdapter(ImmutableAttributesFactory attributesFactory, List<ModuleDependencyMetadata> container, int originalIndex) {
        super(attributesFactory, container, originalIndex);
    }

    @Override
    public void inheritStrictVersions() {
        updateMetadata(getOriginalMetadata().withInheritStrictVersions(true));
    }

    @Override
    public void doNotInheritStrictVersions() {
        updateMetadata(getOriginalMetadata().withInheritStrictVersions(false));
    }

    @Override
    public boolean isInheriting() {
        return getOriginalMetadata().isInheriting();
    }
}
