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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;
import static org.jboss.osgi.framework.internal.InternalConstants.NATIVE_LIBRARY_METADATA_KEY;
import static org.osgi.framework.Constants.SYSTEM_BUNDLE_SYMBOLICNAME;
import static org.osgi.framework.Constants.VISIBILITY_REEXPORT;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.LocalDependencySpecBuilder;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ModuleSpec.Builder;
import org.jboss.modules.ResourceLoader;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.modules.filter.ClassFilter;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader.ModuleSpecBuilderContext;
import org.jboss.osgi.framework.spi.FrameworkModuleProvider;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.NativeLibraryProvider;
import org.jboss.osgi.framework.spi.SystemPaths;
import org.jboss.osgi.metadata.ActivationPolicyMetaData;
import org.jboss.osgi.metadata.NativeLibrary;
import org.jboss.osgi.metadata.NativeLibraryMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XIdentityRequirement;
import org.jboss.osgi.resolver.XPackageCapability;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.BundleReference;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Capability;
import org.osgi.resource.Resource;

/**
 * The module manager plugin.
 *
 * @author thomas.diesler@jboss.com
 * @since 06-Jul-2009
 */
public final class ModuleManagerImpl implements ModuleManager {

    private final XEnvironment environment;
    private final SystemPaths systemPaths;
    private final FrameworkEvents frameworkEvents;
    private final FrameworkModuleLoader moduleLoader;
    private final FrameworkModuleProvider moduleProvider;

    public ModuleManagerImpl(XEnvironment env, SystemPaths syspaths, FrameworkEvents frameworkEvents, FrameworkModuleProvider moduleProvider, FrameworkModuleLoader moduleLoader) {
        this.environment = env;
        this.systemPaths = syspaths;
        this.frameworkEvents = frameworkEvents;
        this.moduleProvider = moduleProvider;
        this.moduleLoader = moduleLoader;
    }

    @Override
    public Module getFrameworkModule() {
        return moduleProvider.getFrameworkModule();
    }

    @Override
    public ModuleIdentifier getModuleIdentifier(final XBundleRevision brev) {
        assert brev != null : "Null resource";
        // The content of a framework extension is part of the framework module
        if (FrameworkExtensions.isAttachedExtension(brev))
            return getFrameworkModule().getIdentifier();

        assert !brev.isFragment() : "A fragment is not a module";

        ModuleIdentifier identifier = brev.getAttachment(IntegrationConstants.MODULE_IDENTIFIER_KEY);
        if (identifier != null)
            return identifier;

        XIdentityCapability icap = brev.getIdentityCapability();
        Module module = brev.getAttachment(InternalConstants.MODULE_KEY);
        if (module != null) {
            identifier = module.getIdentifier();
        } else if (SYSTEM_BUNDLE_SYMBOLICNAME.equals(icap.getName())) {
            identifier = getFrameworkModule().getIdentifier();
        } else {
            identifier = moduleLoader.getModuleIdentifier(brev);
        }

        return identifier;
    }

    /**
     * Get the module with the given identifier
     *
     * @return The module or null
     */
    @Override
    public Module getModule(ModuleIdentifier identifier) {
        Module frameworkModule = getFrameworkModule();
        if (frameworkModule.getIdentifier().equals(identifier)) {
            return frameworkModule;
        }
        try {
            return moduleLoader.getModuleLoader().loadModule(identifier);
        } catch (ModuleLoadException ex) {
            return null;
        }
    }

    /**
     * Get the bundle for the given class
     *
     * @return The bundle or null
     */
    @Override
    public XBundle getBundleState(Class<?> clazz) {
        assert clazz != null : "Null clazz";
        XBundle result = null;
        ClassLoader loader = clazz.getClassLoader();
        if (loader instanceof BundleReference) {
            BundleReference bundleRef = (BundleReference) loader;
            result = (XBundle) bundleRef.getBundle();
        } else if (loader instanceof ModuleClassLoader) {
            Module module = ((ModuleClassLoader) loader).getModule();
            XBundleRevision brev = getBundleRevision(module);
            result = brev != null ? brev.getBundle() : null;
        }
        if (result == null)
            LOGGER.debugf("Cannot obtain bundle for: %s", clazz.getName());
        return result;
    }

    private XBundleRevision getBundleRevision(Module module) {
        XBundleRevision result = null;
        Iterator<XResource> itres = environment.getResources(null);
        while (itres.hasNext()) {
            XResource res = itres.next();
            Module resmod = res.getAttachment(InternalConstants.MODULE_KEY);
            if (module == resmod) {
                result = (XBundleRevision) res;
                break;
            }
        }
        return result;
    }

    @Override
    public ModuleIdentifier addModule(final XBundleRevision brev, final List<BundleWire> wires) {
        assert brev != null : "Null res";
        assert wires != null : "Null wires";
        assert !brev.isFragment() : "Fragments cannot be added: " + brev;

        Module module = brev.getAttachment(InternalConstants.MODULE_KEY);
        if (module != null) {
            ModuleIdentifier identifier = module.getIdentifier();
            FrameworkModuleLoader moduleLoaderPlugin = moduleLoader;
            moduleLoaderPlugin.addModule(brev, module);
            return identifier;
        }

        ModuleIdentifier identifier;
        XIdentityCapability icap = brev.getIdentityCapability();
        if (SYSTEM_BUNDLE_SYMBOLICNAME.equals(icap.getName())) {
            identifier = getFrameworkModule().getIdentifier();
        } else {
            HostBundleRevision hostRev = HostBundleRevision.assertHostRevision(brev);
            identifier = createHostModule(hostRev, wires);
        }
        return identifier;
    }

    /**
     * Create a {@link ModuleSpec} from the given resolver module definition
     */
    private ModuleIdentifier createHostModule(final HostBundleRevision hostRev, final List<BundleWire> wires) {

        LOGGER.tracef("createHostModule for: %s", hostRev);

        UserBundleState hostBundle = hostRev.getBundleState();
        List<RevisionContent> contentRoots = hostRev.getClassPathContent();

        final ModuleIdentifier identifier = getModuleIdentifier(hostRev);
        final ModuleSpec.Builder specBuilder = ModuleSpec.build(identifier);
        final Map<ModuleIdentifier, DependencySpec> moduleDependencies = new LinkedHashMap<ModuleIdentifier, DependencySpec>();

        // Add a system dependency
        Set<String> bootPaths = systemPaths.getBootDelegationPaths();
        PathFilter bootFilter = systemPaths.getBootDelegationFilter();
        PathFilter acceptAll = PathFilters.acceptAll();
        specBuilder.addDependency(DependencySpec.createSystemDependencySpec(bootFilter, acceptAll, bootPaths));

        // Map the dependency for (the likely) case that the same exporter is choosen for multiple wires
        Map<BundleRevision, ModuleDependencyHolder> specHolderMap = new LinkedHashMap<BundleRevision, ModuleDependencyHolder>();

        // For every {@link XWire} add a dependency on the exporter
        processModuleWireList(wires, specHolderMap);

        // Add the holder values to dependencies
        for (ModuleDependencyHolder holder : specHolderMap.values()) {
            moduleDependencies.put(holder.getIdentifier(), holder.create(hostRev));
        }

        // Add the module dependencies to the builder
        for (DependencySpec dep : moduleDependencies.values())
            specBuilder.addDependency(dep);

        // The content of a connect bundle may come with the class loader that loads from it
        ClassLoader connectLoader = null;
        Set<String> connectPaths = null;
        try {
            VirtualFile rootFile = hostRev.getDeployment().getRoot();
            if (ConnectVirtualFile.isConnectFile(rootFile)) {
                connectLoader = ((ConnectVirtualFile) rootFile).getClassLoader();
                connectPaths = ((ConnectVirtualFile) rootFile).getFilePaths();
            }
        } catch (IOException ex) {
            throw MESSAGES.illegalStateCannotAccessConnectContent(ex, hostRev);
        }

        // Add resource roots the local bundle content. With a connect class loader the content is only listed
        for (RevisionContent revContent : contentRoots) {
            ResourceLoader resLoader = new RevisionContentResourceLoader(hostRev, revContent, connectLoader != null);
            specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(resLoader));
        }

        // Process fragment local content and more resource roots
        Set<FragmentBundleRevision> fragRevs = hostRev.getAttachedFragments();
        for (FragmentBundleRevision fragRev : fragRevs) {
            for (RevisionContent revContent : fragRev.getClassPathContent()) {
                ResourceLoader resLoader = new RevisionContentResourceLoader(hostRev, revContent);
                specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(resLoader));
            }
        }

        // Get the set of imported paths
        Set<String> importedPaths = new HashSet<String>();
        for (ModuleDependencyHolder holder : specHolderMap.values()) {
            Set<String> paths = holder.getImportPaths();
            if (paths != null) {
                importedPaths.addAll(paths);
            }
        }

        // Setup the local loader dependency
        PathFilter importFilter = acceptAll;
        PathFilter exportFilter = acceptAll;
        if (importedPaths.isEmpty() == false) {
            importFilter = PathFilters.not(PathFilters.in(importedPaths));
        }
        PathFilter resImportFilter = PathFilters.acceptAll();
        PathFilter resExportFilter = PathFilters.acceptAll();
        ClassFilter classImportFilter = new ClassFilter() {
            @Override
            public boolean accept(String className) {
                return true;
            }
        };
        final PathFilter cefPath = getExportClassFilter(hostRev);
        ClassFilter classExportFilter = new ClassFilter() {
            @Override
            public boolean accept(String className) {
                return cefPath.accept(className);
            }
        };
        LOGGER.tracef("createLocalDependencySpec for %s: [if=%s,ef=%s,rif=%s,ref=%s,cf=%s]", hostRev, importFilter, exportFilter, resImportFilter, resExportFilter, cefPath);
        DependencySpec localDep = DependencySpec.createLocalDependencySpec(importFilter, exportFilter, resImportFilter, resExportFilter, classImportFilter, classExportFilter);
        specBuilder.addDependency(localDep);

        // The connect class loader provides the classes and resources of the paths that the bundle does not import
        if (connectLoader != null) {
            LocalDependencySpecBuilder connectDep = new LocalDependencySpecBuilder();
            connectDep.setImportFilter(importFilter).setExportFilter(exportFilter);
            connectDep.setResourceImportFilter(resImportFilter).setResourceExportFilter(resExportFilter);
            connectDep.setClassImportFilter(classImportFilter).setClassExportFilter(classExportFilter);
            connectDep.setLocalLoader(new ConnectLocalLoader(connectLoader)).setLoaderPaths(connectPaths);
            specBuilder.addDependency(connectDep.build());
        }

        // Native - Hack
        addNativeResourceLoader(hostRev, specBuilder);

        PathFilter lazyActivationFilter = getLazyPackagesFilter(hostBundle);
        specBuilder.setModuleClassLoaderFactory(new HostBundleClassLoader.Factory(hostBundle, lazyActivationFilter));
        specBuilder.setClassFileTransformer(new WeavingHookProcessor(hostRev, frameworkEvents));
        FallbackLoader fallbackLoader = new FallbackLoader(hostRev, importedPaths);
        specBuilder.setFallbackLoader(connectLoader != null ? new ConnectLocalLoader(connectLoader).after(fallbackLoader) : fallbackLoader);

        ModuleSpecBuilderContext context = new ModuleSpecBuilderContext() {

            @Override
            public XBundleRevision getBundleRevision() {
                return hostRev;
            }

            @Override
            public Builder getModuleSpecBuilder() {
                return specBuilder;
            }

            @Override
            public Map<ModuleIdentifier, DependencySpec> getModuleDependencies() {
                return Collections.unmodifiableMap(moduleDependencies);
            }
        };

        // Add integration dependencies, build the spec and add it to the module loader
        FrameworkModuleLoader moduleLoaderPlugin = moduleLoader;
        moduleLoaderPlugin.addIntegrationDependencies(context);
        moduleLoaderPlugin.addModuleSpec(hostRev, specBuilder.create());

        return identifier;
    }

    private void processModuleWireList(List<BundleWire> wires, Map<BundleRevision, ModuleDependencyHolder> depBuilderMap) {

        // A bundle may both import packages (via Import-Package) and require one
        // or more bundles (via Require-Bundle), but if a package is imported via
        // Import-Package, it is not also visible via Require-Bundle: Import-Package
        // takes priority over Require-Bundle, and packages which are exported by a
        // required bundle and imported via Import-Package must not be treated as
        // split packages.

        // Collect bundle and package wires
        List<BundleWire> bundleWires = new ArrayList<BundleWire>();
        List<BundleWire> packageWires = new ArrayList<BundleWire>();
        for (BundleWire wire : wires) {
            XRequirement req = (XRequirement) wire.getRequirement();
            XBundleRevision importer = (XBundleRevision) wire.getRequirer();
            XBundleRevision exporter = (XBundleRevision) wire.getProvider();

            // Skip dependencies on the module itself
            if (exporter == importer)
                continue;

            // Dependency for Import-Package
            if (req.adapt(XPackageRequirement.class) != null) {
                packageWires.add(wire);
                continue;
            }

            // Dependency for Require-Bundle
            if (req.adapt(XIdentityRequirement.class) != null) {
                bundleWires.add(wire);
                continue;
            }
        }

        Set<String> importedPaths = new HashSet<String>();
        Set<Resource> packageExporters = new HashSet<Resource>();
        for (BundleWire wire : packageWires) {
            XBundleRevision exporter = (XBundleRevision) wire.getProvider();
            packageExporters.add(exporter);
            XRequirement xreq = (XRequirement) wire.getRequirement();
            XPackageRequirement packreq = xreq.adapt(XPackageRequirement.class);
            ModuleDependencyHolder holder = getDependencyHolder(depBuilderMap, exporter);
            String path = VFSUtils.getPathFromPackageName(packreq.getPackageName());
            holder.setOptional(packreq.isOptional());
            holder.addImportPath(path);
            importedPaths.add(path);
        }

        for (BundleWire wire : bundleWires) {
            XBundleRevision exporter = (XBundleRevision) wire.getProvider();
            XRequirement xreq = (XRequirement) wire.getRequirement();
            XIdentityRequirement resreq = xreq.adapt(XIdentityRequirement.class);

            // Only the exported packages of a required bundle are visible, also those that it re-exports, unless
            // they are imported with Import-Package. A provider that is also an Import-Package exporter has one
            // dependency for both
            Set<String> requiredPaths = getRequiredBundlePaths(exporter, new HashSet<XBundleRevision>());
            requiredPaths.removeAll(importedPaths);
            ModuleDependencyHolder holder = getDependencyHolder(depBuilderMap, exporter);
            holder.addRequiredPaths(requiredPaths);
            if (packageExporters.contains(exporter) == false)
                holder.setOptional(resreq.isOptional());

            boolean reexport = VISIBILITY_REEXPORT.equals(resreq.getVisibility());
            if (reexport == true) {
                holder.addReexportPaths(requiredPaths);
            }
        }
    }

    // The wires of the revisions that the current resolve operation wires, their wirings are set after their modules
    private static final ThreadLocal<Map<BundleRevision, List<BundleWire>>> resolveWires = new ThreadLocal<Map<BundleRevision, List<BundleWire>>>();

    static void setResolveWires(Map<BundleRevision, List<BundleWire>> wires) {
        if (wires != null)
            resolveWires.set(wires);
        else
            resolveWires.remove();
    }

    // The paths of the packages that a bundle provides to the bundles that require it
    private Set<String> getRequiredBundlePaths(XBundleRevision provider, Set<XBundleRevision> visited) {
        Set<String> paths = new HashSet<String>();
        if (visited.add(provider) == false)
            return paths;

        // The wiring also has the packages of the attached fragments. A provider that is wired by the same resolve
        // operation has no wiring yet, it has the wires of that operation
        BundleWiring wiring = provider.getWiring();
        Map<BundleRevision, List<BundleWire>> pendingWires = resolveWires.get();
        List<BundleWire> pending = wiring == null && pendingWires != null ? pendingWires.get(provider) : null;
        List<? extends Capability> caps = wiring != null ? wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE) : null;
        if (caps == null)
            caps = provider.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
        for (Capability auxcap : caps) {
            XPackageCapability packcap = ((XCapability) auxcap).adapt(XPackageCapability.class);
            if (packcap != null)
                paths.add(packcap.getPackageName().replace('.', '/'));
        }
        List<BundleWire> wires = wiring != null ? wiring.getRequiredWires(BundleNamespace.BUNDLE_NAMESPACE) : pending;
        if (wires != null) {
            for (BundleWire auxwire : wires) {
                if (BundleNamespace.BUNDLE_NAMESPACE.equals(auxwire.getCapability().getNamespace()) == false)
                    continue;
                XIdentityRequirement auxreq = ((XRequirement) auxwire.getRequirement()).adapt(XIdentityRequirement.class);
                if (auxreq != null && VISIBILITY_REEXPORT.equals(auxreq.getVisibility()))
                    paths.addAll(getRequiredBundlePaths((XBundleRevision) auxwire.getProvider(), visited));
            }
        }
        return paths;
    }

    /**
     * The filter of the classes that the bundle exports: the include and exclude directives of an exported package
     * apply to the classes of that package, given by their simple names
     */
    private PathFilter getExportClassFilter(XBundleRevision hostRev) {
        final Map<String, List<Pattern>[]> packageFilters = new HashMap<String, List<Pattern>[]>();
        for (Capability auxcap : hostRev.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            XCapability xcap = (XCapability) auxcap;
            XPackageCapability packcap = xcap.adapt(XPackageCapability.class);
            String includeDirective = packcap.getDirective(Constants.INCLUDE_DIRECTIVE);
            String excludeDirective = packcap.getDirective(Constants.EXCLUDE_DIRECTIVE);
            if (includeDirective != null || excludeDirective != null) {
                @SuppressWarnings("unchecked")
                List<Pattern>[] filters = new List[] { getClassNamePatterns(includeDirective), getClassNamePatterns(excludeDirective) };
                packageFilters.put(packcap.getPackageName(), filters);
            }
        }

        // Accept all classes for export if there is no filter specified
        if (packageFilters.isEmpty())
            return PathFilters.acceptAll();

        return new PathFilter() {
            @Override
            public boolean accept(String className) {
                int index = className.lastIndexOf('.');
                String packageName = index > 0 ? className.substring(0, index) : "";
                List<Pattern>[] filters = packageFilters.get(packageName);
                if (filters == null)
                    return true;
                String simpleName = className.substring(index + 1);
                boolean included = filters[0] == null || matchesAny(filters[0], simpleName);
                boolean excluded = filters[1] != null && matchesAny(filters[1], simpleName);
                return included && !excluded;
            }

            @Override
            public String toString() {
                return "ExportClassFilter" + packageFilters.keySet();
            }
        };
    }

    // The patterns of a comma separated list of class names with wildcards, null without a list
    private static List<Pattern> getClassNamePatterns(String directive) {
        if (directive == null)
            return null;
        List<Pattern> result = new ArrayList<Pattern>();
        for (String name : directive.split(",")) {
            List<String> parts = new ArrayList<String>();
            for (String part : name.trim().split("\\*", -1))
                parts.add(Pattern.quote(part));
            result.add(Pattern.compile(String.join(".*", parts)));
        }
        return result;
    }

    private static boolean matchesAny(List<Pattern> patterns, String simpleName) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(simpleName).matches())
                return true;
        }
        return false;
    }

    /**
     * Get a path filter for packages that trigger bundle activation for a host bundle with lazy ActivationPolicy
     */
    private PathFilter getLazyPackagesFilter(UserBundleState hostBundle) {

        // By default all packages are loaded lazily
        PathFilter result = PathFilters.acceptAll();

        ActivationPolicyMetaData activationPolicy = hostBundle.getActivationPolicy();
        if (activationPolicy != null) {
            List<String> includes = activationPolicy.getIncludes();
            if (includes != null) {
                Set<String> paths = new HashSet<String>();
                for (String packageName : includes)
                    paths.add(packageName.replace('.', '/'));

                result = PathFilters.in(paths);
            }

            List<String> excludes = activationPolicy.getExcludes();
            if (excludes != null) {
                // The set of packages on the exclude list determines the packages that can be loaded eagerly
                Set<String> paths = new HashSet<String>();
                for (String packageName : excludes)
                    paths.add(packageName.replace('.', '/'));

                if (includes != null)
                    result = PathFilters.all(result, PathFilters.not(PathFilters.in(paths)));
                else
                    result = PathFilters.not(PathFilters.in(paths));
            }
        }
        return result;
    }

    private void addNativeResourceLoader(HostBundleRevision hostrev, ModuleSpec.Builder specBuilder) {
        Deployment deployment = hostrev.getDeployment();
        addNativeResourceLoader(specBuilder, hostrev, deployment);
        if (hostrev instanceof HostBundleRevision) {
            for (FragmentBundleRevision fragRev : hostrev.getAttachedFragments()) {
                addNativeResourceLoader(specBuilder, hostrev, fragRev.getDeployment());
            }
        }
    }

    private void addNativeResourceLoader(ModuleSpec.Builder specBuilder, HostBundleRevision hostrev, Deployment deployment) {
        NativeLibraryMetaData libMetaData = deployment.getAttachment(NATIVE_LIBRARY_METADATA_KEY);
        if (libMetaData != null) {
            NativeResourceLoader nativeLoader = new NativeResourceLoader();
            for (NativeLibrary library : libMetaData.getNativeLibraries()) {
                String libpath = library.getLibraryPath();
                String libfile = new File(libpath).getName();
                String libname = libfile.substring(0, libfile.lastIndexOf('.'));

                // Add the library provider to the policy
                NativeLibraryProvider libProvider = new NativeCodeImpl.NativeLibraryProviderImpl(hostrev, libname, libpath);
                nativeLoader.addNativeLibrary(libProvider);

                // [TODO] why does the TCK use 'Native' to mean 'libNative' ?
                if (libname.startsWith("lib")) {
                    libname = libname.substring(3);
                    libProvider = new NativeCodeImpl.NativeLibraryProviderImpl(hostrev, libname, libpath);
                    nativeLoader.addNativeLibrary(libProvider);
                }
            }

            specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(nativeLoader));
        }
    }

    // Get or create the dependency builder for the exporter
    private ModuleDependencyHolder getDependencyHolder(Map<BundleRevision, ModuleDependencyHolder> depBuilderMap, XBundleRevision exporter) {
        ModuleIdentifier exporterId = getModuleIdentifier(exporter);
        ModuleDependencyHolder holder = depBuilderMap.get(exporter);
        if (holder == null) {
            holder = new ModuleDependencyHolder(exporterId);
            depBuilderMap.put(exporter, holder);
        }
        return holder;
    }

    /**
     * Load the module for the given identifier
     *
     * @throws ModuleLoadException If the module cannot be loaded
     */
    @Override
    public Module loadModule(ModuleIdentifier identifier) throws ModuleLoadException {
        if (getFrameworkModule().getIdentifier().equals(identifier))
            return getFrameworkModule();
        else
            return moduleLoader.getModuleLoader().loadModule(identifier);
    }

    /**
     * Remove the module with the given identifier
     */
    @Override
    public void removeModule(XBundleRevision brev, ModuleIdentifier identifier) {
        moduleLoader.removeModule(brev);
    }

    private class ModuleDependencyHolder {

        private final ModuleIdentifier identifier;
        private DependencySpec dependencySpec;
        private Set<String> importPaths;
        private Set<String> requiredPaths;
        private Set<String> reexportPaths;
        private PathFilter importFilter;
        private PathFilter exportFilter;
        private boolean optional;

        ModuleDependencyHolder(ModuleIdentifier identifier) {
            this.identifier = identifier;
        }

        ModuleIdentifier getIdentifier() {
            return identifier;
        }

        void addImportPath(String path) {
            assertNotCreated();
            if (importPaths == null)
                importPaths = new HashSet<String>();

            importPaths.add(path);
        }

        Set<String> getImportPaths() {
            return importPaths;
        }

        // The packages that are visible through Require-Bundle
        void addRequiredPaths(Set<String> paths) {
            assertNotCreated();
            if (requiredPaths == null)
                requiredPaths = new HashSet<String>();
            requiredPaths.addAll(paths);
        }

        // The packages that a Require-Bundle with visibility:=reexport exports again
        void addReexportPaths(Set<String> paths) {
            assertNotCreated();
            if (reexportPaths == null)
                reexportPaths = new HashSet<String>();
            reexportPaths.addAll(paths);
        }

        void setImportFilter(PathFilter importFilter) {
            assertNotCreated();
            this.importFilter = importFilter;
        }

        void setExportFilter(PathFilter exportFilter) {
            assertNotCreated();
            this.exportFilter = exportFilter;
        }

        void setOptional(boolean optional) {
            assertNotCreated();
            this.optional = optional;
        }

        DependencySpec create(XBundleRevision hostRev) {
            if (exportFilter == null) {
                Set<String> exportedPaths = new HashSet<String>();
                for (Capability auxcap : hostRev.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
                    XCapability xcap = (XCapability) auxcap;
                    XPackageCapability packcap = xcap.adapt(XPackageCapability.class);
                    String path = packcap.getPackageName().replace('.', '/');
                    exportedPaths.add(path);
                }
                // An Import-Package dependency only re-exports the packages that the host also exports (substitution).
                // Re-exporting more makes the linking of the module walk every path of the dependency graph
                if (importPaths != null)
                    exportedPaths.retainAll(importPaths);
                if (reexportPaths != null)
                    exportedPaths.addAll(reexportPaths);
                exportFilter = exportedPaths.isEmpty() ? PathFilters.rejectAll() : PathFilters.in(exportedPaths);
            }
            if (importFilter == null) {
                if (requiredPaths != null) {
                    Set<String> visiblePaths = new HashSet<String>(requiredPaths);
                    if (importPaths != null)
                        visiblePaths.addAll(importPaths);
                    // Not a plain set filter: with one, jboss-modules links a cycle of required bundles differently and a
                    // bundle would see the content of a required bundle that is not first in its Require-Bundle order
                    importFilter = visiblePaths.isEmpty() ? PathFilters.rejectAll() : PathFilters.all(PathFilters.acceptAll(), PathFilters.in(visiblePaths));
                } else {
                    importFilter = (importPaths != null ? PathFilters.in(importPaths) : PathFilters.acceptAll());
                }
            }
            Module frameworkModule = getFrameworkModule();
            boolean isFrameworkModule = frameworkModule.getIdentifier().equals(identifier);
            ModuleLoader depLoader = (isFrameworkModule ? frameworkModule.getModuleLoader() : moduleLoader.getModuleLoader());
            LOGGER.tracef("createModuleDependencySpec: [id=%s,if=%s,ef=%s,loader=%s,optional=%s]", identifier, importFilter, exportFilter, depLoader, optional);
            return DependencySpec.createModuleDependencySpec(importFilter, exportFilter, depLoader, identifier, optional);
        }

        private void assertNotCreated() {
            assert dependencySpec == null : "DependencySpec already created";
        }
    }
}
