/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.modules;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jboss.modules.filter.MultiplePathFilterBuilder;
import org.jboss.modules.filter.PathFilters;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class JarModuleLoader extends ModuleLoader {

    private static final String[] NO_STRINGS = new String[0];
    private final ModuleLoader delegate;
    private final JarFile jarFile;
    private final String simpleJarName;
    private final ModuleIdentifier myIdentifier;

    JarModuleLoader(final ModuleLoader delegate, final JarFile jarFile) {
        this.delegate = delegate;
        this.jarFile = jarFile;
        String jarName = jarFile.getName();
        simpleJarName = jarName.substring(jarName.lastIndexOf(File.separatorChar) + 1);
        myIdentifier = ModuleIdentifier.create(simpleJarName);
    }

    protected Module preloadModule(final ModuleIdentifier identifier) throws ModuleLoadException {
        if (identifier.equals(ModuleIdentifier.SYSTEM)) {
            return preloadModule(ModuleIdentifier.SYSTEM, SystemClassPathModuleLoader.getInstance());
        } else if (identifier.equals(myIdentifier)) {
            return loadModuleLocal(identifier);
        } else {
            Module module = loadModuleLocal(identifier);
            if (module == null) {
                return preloadModule(identifier, delegate);
            } else {
                return module;
            }
        }
    }

    ModuleIdentifier getMyIdentifier() {
        return myIdentifier;
    }

    public String toString() {
        return "JAR module loader";
    }

    protected ModuleSpec findModule(final ModuleIdentifier identifier) throws ModuleLoadException {
        if (identifier.equals(myIdentifier)) {
            // special root JAR module
            Manifest manifest = null;
            try {
                manifest = jarFile.getManifest();
            } catch (IOException e) {
                throw new ModuleLoadException("Failed to load MANIFEST from JAR", e);
            }
            ModuleSpec.Builder builder = ModuleSpec.build(identifier);
            Attributes mainAttributes = manifest.getMainAttributes();
            String mainClass = mainAttributes.getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null) {
                builder.setMainClass(mainClass);
            }
            String classPath = mainAttributes.getValue(Attributes.Name.CLASS_PATH);
            String dependencies = mainAttributes.getValue("Dependencies");
            MultiplePathFilterBuilder pathFilterBuilder = PathFilters.multiplePathFilterBuilder(true);
            pathFilterBuilder.addFilter(PathFilters.is("modules"), false);
            pathFilterBuilder.addFilter(PathFilters.isChildOf("modules"), false);
            builder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(new JarFileResourceLoader("", jarFile), pathFilterBuilder.create()));
            String[] classPathEntries = classPath == null ? NO_STRINGS : classPath.split("\\w+");
            for (String entry : classPathEntries) {
                if (! entry.isEmpty()) {
                    if (entry.startsWith("../") || entry.startsWith("./") || entry.startsWith("/") || entry.contains("/../")) {
                        // invalid
                        continue;
                    }
                    if (entry.endsWith("/")) {
                        // directory reference
                        File root = new File(jarFile.getName(), entry);
                        FileResourceLoader resourceLoader = new FileResourceLoader(entry, root);
                        builder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(resourceLoader));
                    } else {
                        // assume a JAR
                        File root = new File(jarFile.getName(), entry);
                        JarFile childJarFile;
                        try {
                            childJarFile = new JarFile(root, true);
                        } catch (IOException e) {
                            // ignore and continue
                            continue;
                        }
                        builder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(new JarFileResourceLoader(entry, childJarFile)));
                    }
                }
            }
            String[] dependencyEntries = dependencies == null ? NO_STRINGS : dependencies.split("\\s*,\\s*");
            for (String dependencyEntry : dependencyEntries) {
                boolean optional = false;
                boolean export = false;
                dependencyEntry = dependencyEntry.trim();
                if (! dependencyEntry.isEmpty()) {
                    String[] fields = dependencyEntry.split("\\s+");
                    if (fields.length < 1) {
                        continue;
                    }
                    String moduleName = fields[0];
                    for (int i = 1; i < fields.length; i++) {
                        String field = fields[i];
                        if (field.equals("optional")) {
                            optional = true;
                        } else if (field.equals("export")) {
                            export = true;
                        }
                        // else ignored
                    }
                    builder.addDependency(DependencySpec.createModuleDependencySpec(ModuleIdentifier.fromString(moduleName), export, optional));
                }
            }
            builder.addDependency(DependencySpec.createModuleDependencySpec(ModuleIdentifier.SYSTEM));
            builder.addDependency(DependencySpec.createLocalDependencySpec());
            return builder.create();
        } else {
            String namePath = identifier.getName().replace('.', '/');
            String basePath = "modules/" + namePath + "/" + identifier.getSlot();
            JarEntry moduleXmlEntry = jarFile.getJarEntry(basePath + "/module.xml");
            if (moduleXmlEntry == null) {
                return null;
            }
            ModuleSpec moduleSpec;
            try {
                InputStream inputStream = jarFile.getInputStream(moduleXmlEntry);
                try {
                    moduleSpec = ModuleXmlParser.parseModuleXml(new ModuleXmlParser.ResourceRootFactory() {
                        public ResourceLoader createResourceLoader(final String rootPath, final String loaderPath, final String loaderName) throws IOException {
                            return new JarFileResourceLoader(loaderName, jarFile, loaderPath);
                        }
                    }, basePath, inputStream, moduleXmlEntry.getName(), identifier);
                } finally {
                    safeClose(inputStream);
                }
            } catch (IOException e) {
                throw new ModuleLoadException("Failed to read module.xml file", e);
            }
            return moduleSpec;
        }
    }

    private static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
