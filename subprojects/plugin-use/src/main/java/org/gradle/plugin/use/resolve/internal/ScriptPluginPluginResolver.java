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

import org.apache.commons.codec.binary.Hex;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.plugins.DefaultPotentialPluginWithId;
import org.gradle.api.internal.plugins.PluginImplementation;
import org.gradle.api.internal.plugins.PluginInspector;
import org.gradle.internal.UncheckedException;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.security.MessageDigest;

import static org.objectweb.asm.Opcodes.*;

/**
 * Plugin resolver for script plugins.
 */
public class ScriptPluginPluginResolver implements PluginResolver {

    private static final String LOADER_PACKAGE_NAME = "org.gradle.plugin.use.resolve.script";
    private static final String LOADER_PACKAGE_PATH = LOADER_PACKAGE_NAME.replace(".", "/");

    private final ScriptPluginLoaderClassLoader pluginsLoader;
    private final PluginInspector pluginInspector;

    public ScriptPluginPluginResolver(ClassLoaderScope coreAndPluginsScope, PluginInspector pluginInspector) {
        this.pluginsLoader = new ScriptPluginLoaderClassLoader();
        this.pluginInspector = pluginInspector;
        coreAndPluginsScope.createChild("script-plugins-loaders").export(pluginsLoader);
    }

    public static String getDescription() {
        return "Script Plugins";
    }

    @Override
    public void resolve(PluginRequestInternal pluginRequest, PluginResolutionResult result) throws InvalidPluginRequestException {
        if (pluginRequest.getFrom() == null) {
            result.notFound(getDescription(), "only script plugin requests are supported by this source");
            return;
        }
        if (pluginRequest.getModule() != null) {
            result.notFound(getDescription(), "explicit artifact coordinates are not supported by this source");
            return;
        }
        if (pluginRequest.getVersion() != null) {
            result.notFound(getDescription(), "explicit version is not supported by this source");
            return;
        }

        Class<?> pluginLoaderClass = pluginsLoader.defineScriptPluginLoaderClass(pluginRequest);

        PluginImplementation pluginImplementation = DefaultPotentialPluginWithId.of(pluginRequest.getId(), pluginInspector.inspect(pluginLoaderClass));

        result.found(getDescription(), new SimplePluginResolution(pluginImplementation));
    }

    private static class ScriptPluginLoaderClassLoader extends ClassLoader {

        private Class<?> defineScriptPluginLoaderClass(PluginRequestInternal pluginRequest) {

            String scriptPluginPath = pluginRequest.getFrom();
            String classSimpleName = loaderClassSimpleNameFor(pluginRequest);

            String asmName = LOADER_PACKAGE_PATH + "/" + classSimpleName;

            String fileOperations = "fileOperations";
            String loaderScopeRegistry = "classLoaderScopeRegistry";
            String scriptHandler = "scriptHandler";
            String scriptPluginFactory = "scriptPluginFactory";

            ClassWriter cw = new ClassWriter(0);
            cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, asmName, "Ljava/lang/Object;Lorg/gradle/api/Plugin<Ljava/lang/Object;>;", "java/lang/Object", new String[]{"org/gradle/api/Plugin"});
            cw.visitSource(classSimpleName + ".java", null);

            cw.visitField(ACC_PRIVATE + ACC_FINAL, fileOperations, "Lorg/gradle/api/internal/file/FileOperations;", null, null).visitEnd();
            cw.visitField(ACC_PRIVATE + ACC_FINAL, loaderScopeRegistry, "Lorg/gradle/initialization/ClassLoaderScopeRegistry;", null, null).visitEnd();
            cw.visitField(ACC_PRIVATE + ACC_FINAL, scriptHandler, "Lorg/gradle/api/initialization/dsl/ScriptHandler;", null, null).visitEnd();
            cw.visitField(ACC_PRIVATE + ACC_FINAL, scriptPluginFactory, "Lorg/gradle/configuration/ScriptPluginFactory;", null, null).visitEnd();

            MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lorg/gradle/api/internal/file/FileOperations;Lorg/gradle/initialization/ClassLoaderScopeRegistry;Lorg/gradle/api/initialization/dsl/ScriptHandler;Lorg/gradle/configuration/ScriptPluginFactory;)V", null, null);
            ctor.visitAnnotation("Ljavax/inject/Inject;", true).visitEnd();
            ctor.visitMaxs(2, 5);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            ctor.visitFieldInsn(PUTFIELD, asmName, fileOperations, "Lorg/gradle/api/internal/file/FileOperations;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 2);
            ctor.visitFieldInsn(PUTFIELD, asmName, loaderScopeRegistry, "Lorg/gradle/initialization/ClassLoaderScopeRegistry;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 3);
            ctor.visitFieldInsn(PUTFIELD, asmName, scriptHandler, "Lorg/gradle/api/initialization/dsl/ScriptHandler;");
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 4);
            ctor.visitFieldInsn(PUTFIELD, asmName, scriptPluginFactory, "Lorg/gradle/configuration/ScriptPluginFactory;");
            ctor.visitInsn(RETURN);
            ctor.visitEnd();

            String pluginLoaderAsmName = asmNameOf(ScriptPluginPluginLoader.class);
            MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply", "(Ljava/lang/Object;)V", null, null);
            apply.visitMaxs(6, 4);
            apply.visitTypeInsn(NEW, pluginLoaderAsmName);
            apply.visitInsn(DUP);
            apply.visitMethodInsn(INVOKESPECIAL, pluginLoaderAsmName, "<init>", "()V", false);
            apply.visitVarInsn(ASTORE, 2);
            apply.visitVarInsn(ALOAD, 2);
            apply.visitLdcInsn(scriptPluginPath);
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, asmName, fileOperations, "Lorg/gradle/api/internal/file/FileOperations;");
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, asmName, loaderScopeRegistry, "Lorg/gradle/initialization/ClassLoaderScopeRegistry;");
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, asmName, scriptHandler, "Lorg/gradle/api/initialization/dsl/ScriptHandler;");
            apply.visitVarInsn(ALOAD, 0);
            apply.visitFieldInsn(GETFIELD, asmName, scriptPluginFactory, "Lorg/gradle/configuration/ScriptPluginFactory;");
            apply.visitMethodInsn(INVOKEVIRTUAL, pluginLoaderAsmName, "load", "(Ljava/lang/String;Lorg/gradle/api/internal/file/FileOperations;Lorg/gradle/initialization/ClassLoaderScopeRegistry;Lorg/gradle/api/initialization/dsl/ScriptHandler;Lorg/gradle/configuration/ScriptPluginFactory;)Lorg/gradle/configuration/ScriptPlugin;", false);
            apply.visitVarInsn(ASTORE, 3);
            apply.visitVarInsn(ALOAD, 3);
            apply.visitVarInsn(ALOAD, 1);
            apply.visitMethodInsn(INVOKEINTERFACE, "org/gradle/configuration/ScriptPlugin", "apply", "(Ljava/lang/Object;)V", true);
            apply.visitInsn(RETURN);
            apply.visitEnd();

            MethodVisitor toString = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
            toString.visitMaxs(1, 1);
            toString.visitLdcInsn("id '" + pluginRequest.getId().getId() + "' from '" + scriptPluginPath + "'");
            toString.visitInsn(ARETURN);
            toString.visitEnd();

            cw.visitEnd();
            byte[] bytes = cw.toByteArray();
            return defineClass(LOADER_PACKAGE_NAME + "." + classSimpleName, bytes, 0, bytes.length);
        }

        private String loaderClassSimpleNameFor(PluginRequestInternal pluginRequest) {
            try {
                String id = pluginRequest.hashCode() + "|" + pluginRequest.getFrom();
                byte[] hash = MessageDigest.getInstance("MD5").digest(id.getBytes("UTF-8"));
                return "ScriptPluginPluginSyntheticLoader_" + Hex.encodeHexString(hash);
            } catch (Exception ex) {
                throw UncheckedException.throwAsUncheckedException(ex);
            }
        }

        private String asmNameOf(Class<?> clazz) {
            return org.objectweb.asm.Type.getInternalName(clazz);
        }
    }
}
