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
package org.gradle.plugin.use.resolve.internal;

import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.configuration.ScriptPlugin;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.groovy.scripts.UriScriptSource;
import org.gradle.initialization.ClassLoaderScopeRegistry;

import java.net.URI;


/**
 * Script plugin loader used by {@link ScriptPluginPluginResolver}.
 */
public class ScriptPluginPluginLoader {

    public ScriptPlugin load(String scriptPluginPath,
                             FileOperations fileOperations,
                             ClassLoaderScopeRegistry classLoaderScopeRegistry,
                             ScriptHandler scriptHandler,
                             ScriptPluginFactory scriptPluginFactory) {

        URI uri = fileOperations.uri(scriptPluginPath);
        ScriptSource scriptSource = new UriScriptSource("script", uri);

        ClassLoaderScope coreAndPluginsScope = classLoaderScopeRegistry.getCoreAndPluginsScope();
        ClassLoaderScope loaderScope = coreAndPluginsScope.createChild("script-plugin-" + uri.toString());

        return scriptPluginFactory.create(scriptSource, scriptHandler, loaderScope, coreAndPluginsScope, false);
    }

}
