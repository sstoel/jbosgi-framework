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

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.modules.LocalLoader;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.Resource;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.SystemPaths;
import org.jboss.osgi.framework.spi.URLResource;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XCapability;
import org.jboss.osgi.resolver.XIdentityRequirement;
import org.jboss.osgi.resolver.XPackageCapability;
import org.jboss.osgi.resolver.XPackageRequirement;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XWiring;
import org.jboss.osgi.resolver.spi.AbstractBundleWire;
import org.jboss.osgi.resolver.spi.RemoveOnlyCollection;
import org.jboss.osgi.resolver.spi.ResolverHookProcessor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

/**
 * A fallback loader that takes care of dynamic class/resource loads.
 *
 * @author thomas.diesler@jboss.com
 * @since 24-Feb-2012
 */
final class FallbackLoader implements LocalLoader {

    private static ThreadLocal<Map<String, AtomicInteger>> dynamicLoadAttempts;

    private final ReentrantLock fallbackLock = new ReentrantLock();
    private final AtomicBoolean fallbackEnabled = new AtomicBoolean(true);
    private final UserBundleState hostBundle;
    private final HostBundleRevision hostRev;
    private final Set<String> importedPaths;
    private final FrameworkState frameworkState;
    private final BundleManager bundleManager;

    private final List<XPackageRequirement> weavingImports = new CopyOnWriteArrayList<XPackageRequirement>();

    FallbackLoader(HostBundleRevision hostRev, Set<String> importedPaths) {
        assert hostRev != null : "Null hostRev";
        assert importedPaths != null : "Null importedPaths";
        this.importedPaths = importedPaths;
        this.hostRev = hostRev;
        this.hostBundle = hostRev.getBundleState();
        this.bundleManager = hostBundle.getBundleManager();
        this.frameworkState = hostBundle.getFrameworkState();
        hostRev.setFallbackLoader(this);
    }

    boolean setEnabled(boolean flag) {
        lockFallbackLoader();
        try {
            return fallbackEnabled.getAndSet(flag);
        } finally {
            unlockFallbackLoader();
        }
    }

    void lockFallbackLoader() {
        fallbackLock.lock();
    }

    void unlockFallbackLoader() {
        fallbackLock.unlock();
    }

    /**
     * The dynamic package requirements that weaving hooks have added to the bundle
     */
    List<XPackageRequirement> getDynamicWeavingImports() {
        return weavingImports;
    }

    void addDynamicWeavingImport(XPackageRequirement req) {
        weavingImports.add(req);
    }

    @Override
    public Class<?> loadClassLocal(String className, boolean resolve) {
        DynamicLoadContext context = new DynamicLoadContext(className.replace('.', '/') + ".class");
        try {
            lockFallbackLoader();

            List<XPackageRequirement> matchingPatterns = findMatchingPatterns(context.packageName);
            if (!fallbackEnabled.get() || matchingPatterns.isEmpty())
                return null;

            findRevisionDynamically(context, matchingPatterns);
            addDynamicWire(context);
        } finally {
            unlockFallbackLoader();
        }

        Class<?> result = null;
        XBundleRevision brev = context.targetRevision;
        if (brev != null) {
            try {
                ModuleClassLoader moduleClassLoader = brev.getModuleClassLoader();
                LOGGER.tracef("Fallback loader loaded class [%s] from module: %s", className, brev);
                result = moduleClassLoader.loadClass(className);
                HostBundleClassLoader.lazyClassRequested(result);
            }
            catch (ClassNotFoundException ex) {
                LOGGER.warnf("Fallback loader cannot load class [%s] from module: %s", className, brev);
                return null;
            }
        }
        else {
            LOGGER.debugf("Fallback loader cannot find class [%s] anywhere", className);
        }
        return result;
    }

    @Override
    public Package loadPackageLocal(String name) {
        // The packages of dynamic imports are those of the exporters
        return null;
    }

    @Override
    public List<Resource> loadResourceLocal(String resName) {
        if (resName.startsWith("/"))
            resName = resName.substring(1);
        DynamicLoadContext context = new DynamicLoadContext(resName);
        try {
            lockFallbackLoader();

            List<XPackageRequirement> matchingPatterns = findMatchingPatterns(context.packageName);
            if (!fallbackEnabled.get() || matchingPatterns.isEmpty())
                return Collections.emptyList();

            findRevisionDynamically(context, matchingPatterns);
            addDynamicWire(context);
            XBundleRevision brev = context.targetRevision;
            if (brev == null) {
                LOGGER.debugf("Fallback loader cannot find resource [%s] anywhere", resName);
                return Collections.emptyList();
            }

            // The resource of a package is loaded by the exporter, also from its fragments and its Bundle-ClassPath. A
            // resource directory that is not a package is an entry of the bundle that has it
            ModuleClassLoader exporterLoader = context.capability != null ? brev.getModuleClassLoader() : null;
            URL resURL = exporterLoader != null ? exporterLoader.getResource(resName) : brev.getEntry(resName);
            if (resURL == null) {
                LOGGER.warnf("Fallback loader cannot load resource [%s] from module: %s", resName, brev);
                return Collections.emptyList();
            }
            else {
                LOGGER.tracef("Fallback loader loaded resource [%s] from module: %s", resName, brev);
            }

            return Collections.singletonList((Resource) new URLResource(resURL));
        } finally {
            unlockFallbackLoader();
        }
    }

    /**
     * The exporter of a package that the host imported dynamically before
     */
    private boolean findWiredRevision(DynamicLoadContext context) {
        BundleWiring wiring = hostBundle.adapt(BundleWiring.class);
        if (wiring == null || context.packageName == null)
            return false;
        for (BundleWire wire : wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            String resolution = wire.getRequirement().getDirectives().get(PackageNamespace.REQUIREMENT_RESOLUTION_DIRECTIVE);
            if (PackageNamespace.RESOLUTION_DYNAMIC.equals(resolution)
                    && context.packageName.equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE))) {
                // The requirement stays null, the wire exists already
                context.targetRevision = (XBundleRevision) wire.getProvider();
                context.capability = ((XCapability) wire.getCapability()).adapt(XPackageCapability.class);
                return true;
            }
        }
        return false;
    }

    /**
     * The dynamic import is resolved when an exporter of the package was found, the wire exists whether the class or
     * resource is found or not (3.9.4)
     */
    private void addDynamicWire(DynamicLoadContext context) {
        XBundleRevision brev = context.targetRevision;
        if (brev == null || context.capability == null || context.requirement == null)
            return;
        // Another thread wired the package while this one resolved the exporter without the lock of this loader: the
        // dynamic import has one wire, the class is loaded from its exporter
        if (findWiredRevision(context))
            return;
        BundleCapability bcap = (BundleCapability) context.capability;
        BundleRequirement breq = (BundleRequirement) context.requirement;
        AbstractBundleWire wire = new AbstractBundleWire(bcap, breq, brev, hostRev);
        XWiring requirerWiring = (XWiring) hostBundle.adapt(BundleWiring.class);
        XWiring providerWiring = (XWiring) brev.getBundle().adapt(BundleWiring.class);
        if (requirerWiring != null) {
            requirerWiring.addRequiredWire(wire);
        } else {
            LOGGER.warnf("Fallback loader unable to add 'requirer' wire [%s] to host bundle [%s] - no bundle wiring available", wire, hostBundle);
        }
        if (providerWiring != null) {
            providerWiring.addProvidedWire(wire);
        } else {
            LOGGER.warnf("Fallback loader unable to add 'provider' wire [%s] to provider bundle [%s] - no bundle wiring available", wire, brev);
        }
    }

    private void findRevisionDynamically(DynamicLoadContext context, List<XPackageRequirement> matchingPatterns) {
        String pathName = context.resName;
        int idx = pathName.lastIndexOf('/');
        if (idx < 0)
            return;

        String path = pathName.substring(0, idx);
        if (importedPaths.contains(path))
            return;

        if (dynamicLoadAttempts == null)
            dynamicLoadAttempts = new ThreadLocal<Map<String, AtomicInteger>>();

        Map<String, AtomicInteger> mapping = dynamicLoadAttempts.get();
        boolean removeThreadLocalMapping = false;
        try {
            if (mapping == null) {
                mapping = new HashMap<String, AtomicInteger>();
                dynamicLoadAttempts.set(mapping);
                removeThreadLocalMapping = true;
            }

            AtomicInteger recursiveDepth = mapping.get(pathName);
            if (recursiveDepth == null)
                mapping.put(pathName, recursiveDepth = new AtomicInteger());

            if (recursiveDepth.incrementAndGet() == 1) {
                // A package that was dynamically imported before is loaded from the same exporter
                if (findWiredRevision(context))
                    return;
                // A resource in a directory that is not a package is found by its content, the capabilities of the
                // module bundles name every path of a module
                findResourceDirectory(context, matchingPatterns);
                if (context.targetRevision == null) {
                    findInResolvedRevisions(context, matchingPatterns);
                }
                if (context.targetRevision == null) {
                    findInUnresolvedRevisions(context, matchingPatterns);
                    if (context.targetRevision == null) {
                        findInSystemRevision(context, matchingPatterns);
                    }
                }
            }
        } finally {
            if (removeThreadLocalMapping == true) {
                dynamicLoadAttempts.remove();
            } else {
                AtomicInteger recursiveDepth = mapping.get(pathName);
                if (recursiveDepth.decrementAndGet() == 0)
                    mapping.remove(pathName);
            }
        }
    }

    /**
     * The dynamic package requirements that match the package of a class or resource. Dynamic imports are not used
     * for packages that the bundle exports, or obtains with Import-Package or Require-Bundle (3.9.4 step 6).
     */
    private List<XPackageRequirement> findMatchingPatterns(String packageName) {
        if (packageName == null)
            return Collections.emptyList();

        // Dynamic imports may not be used when the package is exported
        for (XPackageCapability packageCap : getPackageCapabilities(hostRev)) {
            if (packageName.equals(packageCap.getPackageName()))
                return Collections.emptyList();
        }

        // Nor when the package is visible through a required bundle
        if (getRequiredBundlePackages().contains(packageName))
            return Collections.emptyList();

        List<XPackageRequirement> foundMatch = new ArrayList<XPackageRequirement>();
        for (XPackageRequirement dynreq : getDynamicPackageRequirements(hostRev)) {
            if (matchesPattern(dynreq.getPackageName(), packageName)) {
                foundMatch.add(dynreq);
            }
        }

        if (foundMatch.isEmpty() == false)
            LOGGER.tracef("Found match for package [%s] with Dynamic-ImportPackage pattern: %s", packageName, foundMatch);
        else
            LOGGER.tracef("Package [%s] does not match Dynamic-ImportPackage patterns", packageName);

        return foundMatch;
    }

    // "*" matches every package, "a.b.*" the packages below a.b, "a.b" only the package a.b
    static boolean matchesPattern(String pattern, String packageName) {
        if (pattern.equals("*"))
            return true;
        if (pattern.endsWith(".*"))
            return packageName.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(packageName);
    }

    // The packages of the bundles that the host requires, also those that these re-export
    private Set<String> getRequiredBundlePackages() {
        Set<String> result = new HashSet<String>();
        BundleWiring wiring = hostRev.getWiring();
        if (wiring != null)
            addRequiredBundlePackages(wiring, result, new HashSet<BundleWiring>(), true);
        return result;
    }

    private void addRequiredBundlePackages(BundleWiring wiring, Set<String> result, Set<BundleWiring> visited, boolean requirer) {
        if (visited.add(wiring) == false)
            return;
        List<BundleWire> wires = wiring.getRequiredWires(BundleNamespace.BUNDLE_NAMESPACE);
        if (wires == null)
            return;
        for (BundleWire wire : wires) {
            XIdentityRequirement idreq = ((XRequirement) wire.getRequirement()).adapt(XIdentityRequirement.class);
            boolean reexport = idreq != null && Constants.VISIBILITY_REEXPORT.equals(idreq.getVisibility());
            if (requirer || reexport) {
                BundleWiring providerWiring = wire.getProviderWiring();
                if (providerWiring != null) {
                    for (XPackageCapability pcap : getPackageCapabilities(wire.getProvider()))
                        result.add(pcap.getPackageName());
                    addRequiredBundlePackages(providerWiring, result, visited, false);
                }
            }
        }
    }

    private void findInResolvedRevisions(DynamicLoadContext context, List<XPackageRequirement> matchingPatterns) {
        LOGGER.tracef("Attempt to find path dynamically in resolved modules ...");
        // STARTING and STOPPING are resolved too, a lazy bundle waits in STARTING for the class load that activates it
        Set<XBundle> resolved = bundleManager.getBundles(Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING);
        LOGGER.tracef("Resolved modules: %d", resolved.size());
        if (LOGGER.isTraceEnabled()) {
            for (Bundle bundle : resolved)
                LOGGER.tracef("   %s", bundle);
        }
        if (resolved.isEmpty())
            return;

        for (XPackageRequirement pkgreq : matchingPatterns) {
            Map<XBundleRevision, XPackageCapability> matches = new HashMap<XBundleRevision, XPackageCapability>();
            for (XBundle bundle : resolved) {
                if (bundle != hostBundle) {
                    XBundleRevision brev = bundle.getBundleRevision();
                    // A revision that a refresh refreshes without the framework wiring lock gets no new wires
                    boolean hidden = ((BundleManagerPlugin) bundleManager).isHiddenByRefresh(brev, hostRev);
                    if (bundle.getBundleId() > 0 && !brev.isFragment() && !hidden && matches.get(brev) == null) {
                        XPackageCapability bcap = getCapabilityCandidate(context, pkgreq, brev);
                        if (bcap != null && isUsesConsistent(bcap)) {
                            matches.put(brev, bcap);
                        }
                    }
                }
            }

            // The resolver hooks see all candidates of the dynamic import at once
            if (matches.size() > 0) {
                Collection<XPackageCapability> filtered = filterMatches(pkgreq, matches.values());
                matches.values().retainAll(filtered);
            }
            List<XBundleRevision> matchingRevisions = new ArrayList<XBundleRevision>(matches.keySet());
            if (matchingRevisions.size() > 0) {
                if (matchingRevisions.size() > 1) {
                    // The framework preference: the highest package version, then the lowest bundle id
                    final Map<XBundleRevision, XPackageCapability> capabilities = matches;
                    Collections.sort(matchingRevisions, new Comparator<XBundleRevision>() {
                        @Override
                        public int compare(XBundleRevision brevA, XBundleRevision brevB) {
                            int result = capabilities.get(brevB).getVersion().compareTo(capabilities.get(brevA).getVersion());
                            if (result == 0)
                                result = Long.compare(brevA.getBundle().getBundleId(), brevB.getBundle().getBundleId());
                            return result;
                        }
                    });
                }
                XBundleRevision brev = matchingRevisions.get(0);
                context.capability = matches.get(brev);
                context.targetRevision = brev;
                context.requirement = pkgreq;
                return;
            }
        }
    }

    /**
     * The candidates that the resolver hooks leave for the dynamic requirement, in one resolve operation
     */
    private Collection<XPackageCapability> filterMatches(XPackageRequirement req, Collection<XPackageCapability> caps) {

        // Cannot filter invalid types
        if (!(req instanceof BundleRequirement))
            return caps;

        boolean callHookLifecycle = false;
        ResolverHookProcessor hookregs = ResolverHookProcessor.getCurrentProcessor();
        if (hookregs == null) {
            BundleContext syscontext = bundleManager.getSystemBundle().getBundleContext();
            hookregs = new ResolverHookProcessor(syscontext, null);
            callHookLifecycle = true;
        }

        // Nothing to filter
        if (hookregs.hasResolverHooks() == false)
            return caps;

        Collection<BundleCapability> bcaps = new ArrayList<BundleCapability>();
        for (XPackageCapability cap : caps) {
            if (cap instanceof BundleCapability)
                bcaps.add((BundleCapability) cap);
        }
        bcaps = new RemoveOnlyCollection<BundleCapability>(bcaps);

        if (callHookLifecycle) {
            // A hook that fails, fails the dynamic import. The failure is published, the class or resource is not found
            try {
                try {
                    hookregs.begin(Collections.singleton(hostRev), null);
                    hookregs.filterMatches((BundleRequirement) req, bcaps);
                } finally {
                    hookregs.end();
                }
            } catch (RuntimeException ex) {
                LOGGER.debugf(ex, "Resolver hook failure for dynamic import: %s", req);
                frameworkState.getFrameworkEvents().fireFrameworkEvent(hostBundle, FrameworkEvent.ERROR, ex);
                return Collections.emptyList();
            }
        } else {
            hookregs.filterMatches((BundleRequirement) req, bcaps);
        }

        Collection<XPackageCapability> result = new ArrayList<XPackageCapability>();
        for (XPackageCapability cap : caps) {
            if (!(cap instanceof BundleCapability) || bcaps.contains(cap))
                result.add(cap);
        }
        return result;
    }

    /**
     * A candidate is consistent with the class space of the host when every package that it uses, and that the host
     * sees, comes from the same provider for both (uses constraint, 3.7.6)
     */
    private boolean isUsesConsistent(XPackageCapability cap) {
        String uses = cap.getDirective(Constants.USES_DIRECTIVE);
        if (uses == null)
            return true;
        XBundleRevision exporter = (XBundleRevision) cap.getResource();
        for (String usedPackage : uses.split(",")) {
            usedPackage = usedPackage.trim();
            BundleRevision hostSource = getPackageSource(hostRev, usedPackage);
            BundleRevision exporterSource = getPackageSource(exporter, usedPackage);
            if (hostSource != null && exporterSource != null && hostSource != exporterSource)
                return false;
        }
        return true;
    }

    // The revision that provides a package to the given revision: an import wire, else the revision itself if it exports it
    private BundleRevision getPackageSource(BundleRevision brev, String packageName) {
        BundleWiring wiring = brev.getWiring();
        if (wiring != null) {
            List<BundleWire> wires = wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
            for (BundleWire wire : wires != null ? wires : Collections.<BundleWire>emptyList()) {
                if (packageName.equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                    return wire.getProvider();
            }
        }
        for (XPackageCapability pcap : getPackageCapabilities(brev)) {
            if (packageName.equals(pcap.getPackageName()))
                return brev;
        }
        return null;
    }

    private void findInUnresolvedRevisions(DynamicLoadContext context, List<XPackageRequirement> matchingPatterns) {
        LOGGER.tracef("Attempt to find path dynamically in unresolved modules ...");
        Set<XBundle> unresolved = bundleManager.getBundles(Bundle.INSTALLED);
        LOGGER.tracef("Unresolved modules: %d", unresolved.size());
        if (LOGGER.isTraceEnabled()) {
            for (Bundle bundle : unresolved)
                LOGGER.tracef("   %s", bundle);
        }
        // The bundles are resolved without the lock of this loader: a resolve takes the framework wiring lock, which a
        // lifecycle operation holds while it loads classes through this loader
        int holdCount = fallbackLock.getHoldCount();
        for (int i = 0; i < holdCount; i++)
            fallbackLock.unlock();
        try {
            for (Bundle bundle : unresolved) {
                if (!(bundle instanceof AbstractBundleState)) {
                    LOGGER.tracef("Ignore invalid bundle type: %s", bundle);
                    continue;
                }
                LOGGER.tracef("Attempt to resolve: %s", bundle);
                AbstractBundleState.assertBundleState(bundle).ensureResolved(false);
            }
        } finally {
            for (int i = 0; i < holdCount; i++)
                fallbackLock.lock();
        }
        findInResolvedRevisions(context, matchingPatterns);
    }

    /**
     * A directory like META-INF/services is not a package and cannot be exported. A dynamic import pattern like
     * META-INF.* finds a resource in it in the content of a resolved bundle whose capabilities name the directory, as
     * those of a module bundle name every path of the module (3.9.4: only an exported package is visible). The bundle
     * must have the resource, not every module that names the directory has it.
     */
    private void findResourceDirectory(DynamicLoadContext context, List<XPackageRequirement> matchingPatterns) {
        if (context.resName.endsWith(".class") || isPackageName(context.packageName))
            return;
        List<XBundle> resolved = new ArrayList<XBundle>(bundleManager.getBundles(Bundle.RESOLVED | Bundle.ACTIVE | Bundle.STARTING));
        Collections.sort(resolved, new Comparator<XBundle>() {
            @Override
            public int compare(XBundle b1, XBundle b2) {
                return Long.compare(b1.getBundleId(), b2.getBundleId());
            }
        });
        for (XBundle bundle : resolved) {
            XBundleRevision brev = bundle.getBundleRevision();
            if (bundle != hostBundle && bundle.getBundleId() > 0 && !brev.isFragment() && brev.getEntry(context.resName) != null) {
                for (XPackageRequirement pkgreq : matchingPatterns) {
                    if (getCapabilityCandidate(context, pkgreq, brev) != null) {
                        context.targetRevision = brev;
                        return;
                    }
                }
            }
        }
    }

    private static boolean isPackageName(String packageName) {
        for (String segment : packageName.split("\\.")) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0)))
                return false;
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i)))
                    return false;
            }
        }
        return true;
    }

    private void findInSystemRevision(DynamicLoadContext context, List<XPackageRequirement> matchingPatterns) {
        LOGGER.tracef("Attempt to find path dynamically in framework module ...");
        String resName = context.resName;
        int lastIndex = resName.lastIndexOf('/');
        String pathName = lastIndex > 0 ? resName.substring(0, lastIndex) : resName;
        SystemPaths systemPaths = frameworkState.getSystemPaths();
        if (systemPaths.getSystemPaths().contains(pathName)) {
            context.targetRevision = frameworkState.getSystemBundle().getBundleRevision();
            return;
        }

        // The packages that framework extensions export are provided by the system bundle
        String packageName = pathName.replace('/', '.');
        for (XBundleRevision extRev : frameworkState.getBundleManager().getFrameworkExtensions().getAttachedRevisions()) {
            for (XPackageCapability pcap : getPackageCapabilities(extRev)) {
                if (packageName.equals(pcap.getPackageName()) == false)
                    continue;
                for (XPackageRequirement pkgreq : matchingPatterns) {
                    if (pkgreq.matches(pcap) && filterMatches(pkgreq, Collections.singleton(pcap)).contains(pcap)) {
                        context.targetRevision = frameworkState.getSystemBundle().getBundleRevision();
                        context.capability = pcap;
                        context.requirement = pkgreq;
                        return;
                    }
                }
            }
        }
    }

    /**
     * The package capability of the requested package that the revision exports, also through its fragments. The
     * exporter is not selected by its content: the class or resource is then loaded by the exporter.
     */
    private XPackageCapability getCapabilityCandidate(DynamicLoadContext context, XPackageRequirement pkgreq, XBundleRevision brev) {

        // Skip dynamic loads from this module
        if (brev == hostRev)
            return null;

        LOGGER.tracef("Attempt to find package dynamically [%s] in %s ...", context.packageName, brev);
        for (XPackageCapability pcap : getPackageCapabilities(brev)) {
            if (context.packageName.equals(pcap.getPackageName()) && pkgreq.matches(pcap)) {
                return pcap;
            }
        }
        return null;
    }

    // The exported packages of a revision, those of a resolved revision include the exports of its fragments
    private List<XPackageCapability> getPackageCapabilities(BundleRevision brev) {
        // The wiring of a fragment, e.g. of a framework extension, provides only its identity capability
        boolean fragment = (brev.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0;
        BundleWiring wiring = fragment ? null : brev.getWiring();
        List<? extends Capability> caps = wiring != null ? wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE) : null;
        if (caps == null)
            caps = brev.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
        List<XPackageCapability> result = new ArrayList<XPackageCapability>();
        for (Capability aux : caps) {
            XPackageCapability pcap = ((XCapability) aux).adapt(XPackageCapability.class);
            if (pcap != null)
                result.add(pcap);
        }
        return result;
    }

    private List<XPackageRequirement> getDynamicPackageRequirements(BundleRevision brev) {

        List<XPackageRequirement> result = new ArrayList<XPackageRequirement>();
        for (Requirement aux : brev.getRequirements(PackageNamespace.PACKAGE_NAMESPACE)) {
            XRequirement xreq = (XRequirement) aux;
            XPackageRequirement preq = xreq.adapt(XPackageRequirement.class);
            if (preq.isDynamic()) {
                result.add(preq);
            }
        }

        // Add the dynamic package reqs generated by the {@link WeavingHook}s.
        for (XPackageRequirement preq : weavingImports) {
            if (!result.contains(preq))
                result.add(preq);
        }

        return result;
    }

    class DynamicLoadContext {

        final String resName;
        // The package of the class or resource, null for a resource in the root
        final String packageName;
        XBundleRevision targetRevision;
        XPackageRequirement requirement;
        XPackageCapability capability;

        DynamicLoadContext(String resName) {
            this.resName = resName;
            int idx = resName.lastIndexOf('/');
            this.packageName = idx > 0 ? resName.substring(0, idx).replace('/', '.') : null;
        }
    }
}
