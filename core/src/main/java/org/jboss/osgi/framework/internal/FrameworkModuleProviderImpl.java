/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.jboss.osgi.framework.internal;

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.LocalLoader;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.Resource;
import org.jboss.modules.ResourceLoader;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.BundleReferenceClassLoader;
import org.jboss.osgi.framework.spi.FrameworkModuleProvider;
import org.jboss.osgi.framework.spi.SystemPaths;
import org.jboss.osgi.framework.spi.URLResource;
import org.jboss.osgi.resolver.XBundle;

/**
 * The system module provider plugin.
 *
 * @author thomas.diesler@jboss.com
 * @since 04-Feb-2011
 */
public class FrameworkModuleProviderImpl implements FrameworkModuleProvider {

    private final BundleManagerPlugin bundleManager;
    private final SystemPaths systemPaths;
    private final List<ResourceLoaderSpec> extensionLoaders = new ArrayList<ResourceLoaderSpec>();
    private final Set<String> classPathExtensionPaths = new LinkedHashSet<String>();
    private FrameworkModuleLoader moduleLoader;
    private volatile boolean hasExtensions;
    private Module frameworkModule;

    public FrameworkModuleProviderImpl(BundleManager bundleManager, SystemPaths systemPaths) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
        this.systemPaths = systemPaths;
    }

    @Override
    public Module getFrameworkModule() {
        synchronized (this) {
            if (frameworkModule == null) {
                frameworkModule = createFrameworkModule();
            }
            return frameworkModule;
        }
    }

    private Module createFrameworkModule() {
        ModuleSpec.Builder specBuilder = ModuleSpec.build(FrameworkModuleProvider.FRAMEWORK_MODULE_IDENTIFIER);
        for (DependencySpec dependency : createDependencies()) {
            specBuilder.addDependency(dependency);
        }

        // Classes of a framework extension can use all classes of the framework implementation.
        // Without extensions only the system packages are visible through the framework module
        specBuilder.setFallbackLoader(new LocalLoader() {

            private final LocalLoader delegate = new FrameworkClassLoaderLoader(false);

            @Override
            public Class<?> loadClassLocal(String name, boolean resolve) {
                return hasExtensions ? delegate.loadClassLocal(name, resolve) : null;
            }

            @Override
            public Package loadPackageLocal(String name) {
                return null;
            }

            @Override
            public List<Resource> loadResourceLocal(String name) {
                return Collections.emptyList();
            }
        });

        SystemBundleState systemBundle = bundleManager.getSystemBundle();
        specBuilder.setModuleClassLoaderFactory(new BundleReferenceClassLoader.Factory<XBundle>(systemBundle));

        try {
            moduleLoader = new FrameworkModuleLoader(specBuilder.create());
            return moduleLoader.loadModule(specBuilder.getIdentifier());
        } catch (ModuleLoadException ex) {
            throw MESSAGES.illegalStateCannotCreateFrameworkModule(ex);
        }
    }

    private List<DependencySpec> createDependencies() {
        List<DependencySpec> result = new ArrayList<DependencySpec>();
        Set<String> bootPaths = systemPaths.getBootDelegationPaths();
        PathFilter bootFilter = systemPaths.getBootDelegationFilter();
        PathFilter acceptAll = PathFilters.acceptAll();
        result.add(DependencySpec.createSystemDependencySpec(bootFilter, acceptAll, bootPaths));

        // The system packages
        Set<String> paths = systemPaths.getSystemPaths();
        PathFilter filter = systemPaths.getSystemFilter();
        result.add(DependencySpec.createLocalDependencySpec(filter, acceptAll, new FrameworkClassLoaderLoader(false), paths));

        // The classes and resources of the extensions that are on the class path of the framework
        if (classPathExtensionPaths.isEmpty() == false) {
            Set<String> extpaths = new LinkedHashSet<String>(classPathExtensionPaths);
            result.add(DependencySpec.createLocalDependencySpec(PathFilters.in(extpaths), acceptAll, new FrameworkClassLoaderLoader(true), extpaths));
        }

        // The content of the extensions that are loaded by the framework module
        result.add(DependencySpec.createLocalDependencySpec());
        return result;
    }

    @Override
    public boolean supportsExtensions() {
        return true;
    }

    /**
     * Add the content of a framework extension to the framework.
     *
     * The content is appended to the class path of the class loader that loaded the framework when that is possible.
     * Otherwise the classes of the extension are defined by the class loader of the framework module.
     */
    @Override
    public synchronized boolean addExtensionContent(URL contentURL, ResourceLoader resourceLoader) {
        Module module = getFrameworkModule();
        hasExtensions = true;
        try {
            if (contentURL != null && appendToFrameworkClassPath(contentURL)) {
                classPathExtensionPaths.addAll(resourceLoader.getPaths());
                moduleLoader.relinkDependencies(module, createDependencies());
                return true;
            }
            extensionLoaders.add(ResourceLoaderSpec.createResourceLoaderSpec(resourceLoader));
            moduleLoader.refreshResourceLoaders(module, extensionLoaders);
            return false;
        } catch (ModuleLoadException ex) {
            throw MESSAGES.illegalStateCannotCreateFrameworkModule(ex);
        }
    }

    private boolean appendToFrameworkClassPath(URL contentURL) {
        ClassLoader classLoader = BundleManagerPlugin.class.getClassLoader();
        if (classLoader instanceof URLClassLoader == false)
            return false;
        try {
            // Only possible when java.net is open to the framework, i.e. --add-opens java.base/java.net=ALL-UNNAMED
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, contentURL);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static final class FrameworkClassLoaderLoader implements LocalLoader {

        private final ClassLoader classLoader = BundleManagerPlugin.class.getClassLoader();
        private final boolean withResources;

        FrameworkClassLoaderLoader(boolean withResources) {
            this.withResources = withResources;
        }

        @Override
        public Class<?> loadClassLocal(String name, boolean resolve) {
            try {
                return classLoader.loadClass(name);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }

        @Override
        public Package loadPackageLocal(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Resource> loadResourceLocal(String name) {
            if (withResources == false)
                return Collections.emptyList();

            List<Resource> result = new ArrayList<Resource>();
            try {
                Enumeration<URL> urls = classLoader.getResources(name);
                while (urls.hasMoreElements())
                    result.add(new URLResource(urls.nextElement()));
            } catch (IOException ex) {
                // ignore
            }
            return result;
        }
    }

    private static final class FrameworkModuleLoader extends ModuleLoader {

        private final ModuleSpec moduleSpec;

        FrameworkModuleLoader(ModuleSpec moduleSpec) {
            this.moduleSpec = moduleSpec;
        }

        @Override
        protected ModuleSpec findModule(ModuleIdentifier identifier) throws ModuleLoadException {
            return (moduleSpec.getModuleIdentifier().equals(identifier) ? moduleSpec : null);
        }

        void relinkDependencies(Module module, List<DependencySpec> dependencies) throws ModuleLoadException {
            setAndRelinkDependencies(module, dependencies);
        }

        void refreshResourceLoaders(Module module, List<ResourceLoaderSpec> loaders) throws ModuleLoadException {
            setAndRefreshResourceLoaders(module, loaders);
            relink(module);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName();
        }
    }
}