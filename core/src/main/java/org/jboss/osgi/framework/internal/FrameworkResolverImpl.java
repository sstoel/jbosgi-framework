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

import static org.jboss.osgi.framework.internal.InternalConstants.NATIVE_LIBRARY_METADATA_KEY;
import static org.jboss.osgi.resolver.ResolverMessages.MESSAGES;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.FrameworkWiringLock;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.LockManager.LockContext;
import org.jboss.osgi.framework.spi.LockManager.Method;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.NativeCode;
import org.jboss.osgi.metadata.NativeLibraryMetaData;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XIdentityCapability;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XResourceCapability;
import org.jboss.osgi.resolver.XWiring;
import org.jboss.osgi.resolver.spi.AbstractBundleWire;
import org.jboss.osgi.resolver.spi.AbstractResolveContext;
import org.jboss.osgi.resolver.spi.ResolverHookProcessor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;

/**
 * The resolver plugin.
 *
 * @author thomas.diesler@jboss.com
 * @since 15-Feb-2012
 */
final class FrameworkResolverImpl implements XResolver {

    private final BundleManagerPlugin bundleManager;
    private final NativeCode nativeCode;
    private final ModuleManager moduleManager;
    private final FrameworkModuleLoader moduleLoader;
    private final LockManager lockManager;
    private final XResolver resolver;

    FrameworkResolverImpl(BundleManager bundleManager, NativeCode nativeCode, ModuleManager moduleManager, FrameworkModuleLoader moduleLoader, XResolver resolver, LockManager lockManager) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
        this.nativeCode = nativeCode;
        this.moduleManager = moduleManager;
        this.moduleLoader = moduleLoader;
        this.lockManager = lockManager;
        this.resolver = resolver;
    }

    @Override
    public XResolveContext createResolveContext(XEnvironment environment, Collection<? extends Resource> mandatory, Collection<? extends Resource> optional) {
        return resolver.createResolveContext(environment, mandatory, optional);
    }

    @Override
    public Map<Resource, List<Wire>> resolve(ResolveContext resolveContext) throws ResolutionException {
        return resolveInternal((XResolveContext) resolveContext, false);
    }

    @Override
    public Map<Resource, List<Wire>> resolveDynamic(ResolveContext resolveContext, Wiring hostWiring, Requirement dynamicRequirement) throws ResolutionException {
        return resolver.resolveDynamic(resolveContext, hostWiring, dynamicRequirement);
    }

    @Override
    public Map<Resource, List<Wire>> resolveAndApply(XResolveContext resolveContext) throws ResolutionException {
        return resolveInternal(resolveContext, true);
    }

    private Map<Resource, List<Wire>> resolveInternal(XResolveContext resolveContext, boolean applyResults) throws ResolutionException {

        // Resolver Hooks also must not be allowed to start another resolve operation, for example by starting a bundle or resolving bundles.
        // The framework must detect this and throw an Illegal State Exception.
        if (ResolverHookProcessor.getCurrentProcessor() != null)
            throw MESSAGES.illegalStateResolverHookCannotTriggerResolveOperation();

        // One resolve operation at a time. The framework wiring lock serializes them, which the lifecycle operations take
        // before they resolve: there is one lock order. The RESOLVED events are sent without it.
        FrameworkWiringLock resolveLock = lockManager.getItemForType(FrameworkWiringLock.class);
        LockContext resolveContextLock = lockManager.lockItems(Method.RESOLVE, resolveLock);
        Map<Resource, List<Wire>> wiremap;
        List<Map<Resource, List<Wire>>> resolved = new ArrayList<Map<Resource, List<Wire>>>();
        try {
            wiremap = resolveLocked(resolveContext, applyResults, resolved);
        } finally {
            lockManager.unlockItems(resolveContextLock);
        }

        // Send the {@link BundleEvent.RESOLVED} event outside the lock, for the resources of the resolver
        if (applyResults && resolved.isEmpty() == false) {
            sendBundleResolvedEvents(resolved.get(0));
        }
        return wiremap;
    }

    // The wire map of the resolver is added to the given list
    private Map<Resource, List<Wire>> resolveLocked(XResolveContext resolveContext, boolean applyResults, List<Map<Resource, List<Wire>>> resolved) throws ResolutionException {

        XEnvironment env = resolveContext.getEnvironment();
        BundleContext syscontext = bundleManager.getSystemContext();
        Collection<Resource> manres = new HashSet<Resource>(resolveContext.getMandatoryResources());
        Collection<Resource> optres = new HashSet<Resource>(resolveContext.getOptionalResources());

        // Framework extensions are wired to the system bundle, the resolver cannot attach them to a resolved host
        Map<Resource, List<Wire>> extwires = Collections.emptyMap();
        if (applyResults) {
            FrameworkExtensions extensions = bundleManager.getFrameworkExtensions();
            extwires = extensions.resolve();
            manres.removeAll(extwires.keySet());
            optres.removeAll(extwires.keySet());

            // Only the system bundle and the extensions are resolved while the framework is initialized
            if (extensions.isInitializing())
                return extwires;
        }

        ResolverHookProcessor hookregs = new ResolverHookProcessor(syscontext, bundleManager.getBundles(Bundle.INSTALLED));
        boolean hooksEnded = false;
        try {
            // Recreate the {@link ResolveContext} with filtered resources
            if (hookregs.hasResolverHooks()) {
                hookregs.begin(manres, optres);
                hookregs.filterResolvable();
                hookregs.filterSingletonCollisions(new ResolverHookProcessor.SingletonLocator() {
                    @Override
                    public Collection<BundleCapability> findCollisionCandidates(BundleCapability viewpoint) {
                        Collection<BundleCapability> result = new HashSet<BundleCapability>();
                        if (viewpoint instanceof XResourceCapability) {
                            String symbolicName = ((XResourceCapability) viewpoint).getName();
                            for (XBundle bundle : bundleManager.getBundles(symbolicName, null)) {
                                XBundleRevision xres = bundle.getBundleRevision();
                                List<BundleCapability> bcaps = xres.getDeclaredCapabilities(viewpoint.getNamespace());
                                if (bcaps.size() == 1) {
                                    BundleCapability bcap = bcaps.get(0);
                                    String spec = bcap.getDirectives().get(Constants.SINGLETON_DIRECTIVE);
                                    if (bcap != viewpoint && Boolean.parseBoolean(spec)) {
                                        result.add(bcap);
                                    }
                                }
                            }
                        }
                        return result;
                    }
                });
                // The context adds attachable fragments and unresolved bundles, a revision that the hooks removed stays removed
                XResolveContext expanded = resolver.createResolveContext(env, getFilteredResources(hookregs, manres), getFilteredResources(hookregs, optres));
                final Collection<Resource> filteredMandatory = getResolvableResources(hookregs, expanded.getMandatoryResources());
                final Collection<Resource> filteredOptional = withoutRefreshing(bundleManager, getResolvableResources(hookregs, expanded.getOptionalResources()));
                resolveContext = new AbstractResolveContext(env) {

                    @Override
                    public Collection<Resource> getMandatoryResources() {
                        return Collections.unmodifiableCollection(filteredMandatory);
                    }

                    @Override
                    public Collection<Resource> getOptionalResources() {
                        return Collections.unmodifiableCollection(filteredOptional);
                    }

                    @Override
                    public List<Capability> findProviders(Requirement req) {
                        return withoutRefreshing(bundleManager, req, super.findProviders(req));
                    }
                };
            } else {
                filterSingletons(manres, optres);
                // The resolve context enforces the singleton policy, also for the singletons pulled in as providers
                XResolveContext expanded = resolver.createResolveContext(env, manres, optres);
                resolveContext = new SingletonResolveContext(bundleManager, env, expanded.getMandatoryResources(), expanded.getOptionalResources());
            }

            Map<Resource, List<Wire>> wiremap;

            FrameworkWiringLock wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
            LockContext lockContext = lockManager.lockItems(Method.RESOLVE, wireLock);
            try {
                wiremap = resolver.resolve(resolveContext);

                // The hooks end before the results are applied: a hook that fails in end() fails the resolve, which
                // then has no effect
                hooksEnded = true;
                hookregs.end();

                if (applyResults) {
                    applyResolverResults(env, wiremap);
                }
            } finally {
                lockManager.unlockItems(lockContext);
            }

            resolved.add(wiremap);
            if (extwires.isEmpty() == false) {
                wiremap = new LinkedHashMap<Resource, List<Wire>>(wiremap);
                wiremap.putAll(extwires);
            }
            return wiremap;
        } finally {
            if (hooksEnded == false)
                hookregs.end();
        }
    }

    // The resources without the unresolved bundle revisions that the hooks did not leave to be resolved
    private Collection<Resource> getResolvableResources(ResolverHookProcessor hookregs, Collection<Resource> resources) {
        Collection<Resource> result = new ArrayList<Resource>();
        for (Resource res : resources) {
            boolean unresolvedRevision = res instanceof XBundleRevision && ((XBundleRevision) res).getWiringSupport().getWiring(false) == null;
            if (unresolvedRevision == false || hookregs.hasResource(res))
                result.add(res);
        }
        return result;
    }

    private Collection<? extends Resource> getFilteredResources(ResolverHookProcessor hookregs, Collection<? extends Resource> resources) {
        Collection<Resource> filtered = null;
        if (resources != null) {
            filtered = new ArrayList<Resource>(resources);
            Iterator<Resource> iterator = filtered.iterator();
            while (iterator.hasNext()) {
                Resource res = iterator.next();
                if (!hookregs.hasResource(res)) {
                    iterator.remove();
                }
            }
        }
        return filtered;
    }

    private HashSet<Resource> getCombinedResources(Collection<? extends Resource> manres, Collection<Resource> optres) {
        HashSet<Resource> combined = new HashSet<Resource>(manres);
        combined.addAll(optres);
        return combined;
    }

    /**
     * A resolve context that lets only one singleton of a symbolic name be resolved: a resolved singleton, else the
     * first one that the resolve operation uses
     */
    static final class SingletonResolveContext extends AbstractResolveContext {

        private final BundleManagerPlugin bundleManager;
        private final Collection<Resource> mandatory = new ArrayList<Resource>();
        private final Collection<Resource> optional = new ArrayList<Resource>();
        private final Map<String, Resource> chosen = new HashMap<String, Resource>();

        SingletonResolveContext(BundleManagerPlugin bundleManager, XEnvironment env, Collection<Resource> mandatory, Collection<Resource> optional) {
            super(env);
            this.bundleManager = bundleManager;
            Iterator<XResource> itres = env.getResources(Collections.singleton(XResource.TYPE_BUNDLE));
            while (itres.hasNext()) {
                XResource res = itres.next();
                if (isSingleton(res) && res.getWiringSupport().getWiring(false) != null)
                    chosen.put(res.getIdentityCapability().getName(), res);
            }
            this.mandatory.addAll(getAllowed(mandatory));
            this.optional.addAll(getAllowed(withoutRefreshing(bundleManager, optional)));
        }

        private Collection<Resource> getAllowed(Collection<Resource> resources) {
            List<Resource> sorted = new ArrayList<Resource>(resources);
            Collections.sort(sorted, new Comparator<Resource>() {
                @Override
                public int compare(Resource r1, Resource r2) {
                    return ((XResource) r2).getIdentityCapability().getVersion().compareTo(((XResource) r1).getIdentityCapability().getVersion());
                }
            });
            Collection<Resource> result = new ArrayList<Resource>();
            for (Resource res : sorted) {
                if (isAllowed((XResource) res))
                    result.add(res);
            }
            return result;
        }

        private boolean isAllowed(XResource res) {
            if (!isSingleton(res) || res.getWiringSupport().getWiring(false) != null)
                return true;
            String name = res.getIdentityCapability().getName();
            Resource singleton = chosen.get(name);
            if (singleton == null)
                chosen.put(name, singleton = res);
            return singleton == res;
        }

        private static boolean isSingleton(XResource res) {
            XIdentityCapability icap = res.getIdentityCapability();
            return icap != null && icap.isSingleton();
        }

        @Override
        public Collection<Resource> getMandatoryResources() {
            return Collections.unmodifiableCollection(mandatory);
        }

        @Override
        public Collection<Resource> getOptionalResources() {
            return Collections.unmodifiableCollection(optional);
        }

        @Override
        public List<Capability> findProviders(Requirement req) {
            List<Capability> providers = withoutRefreshing(bundleManager, req, super.findProviders(req));
            Iterator<Capability> itcap = providers.iterator();
            while (itcap.hasNext()) {
                Resource res = itcap.next().getResource();
                if (res instanceof XResource && !isAllowed((XResource) res))
                    itcap.remove();
            }
            return providers;
        }
    }

    // The revisions that a refresh refreshes without the framework wiring lock are not resolved with other bundles
    private static Collection<Resource> withoutRefreshing(BundleManagerPlugin bundleManager, Collection<Resource> optional) {
        Collection<Resource> result = new ArrayList<Resource>(optional);
        Iterator<Resource> itres = result.iterator();
        while (itres.hasNext()) {
            if (bundleManager.isHiddenByRefresh(itres.next(), null))
                itres.remove();
        }
        return result;
    }

    // Nor are they providers for the requirements of other bundles
    private static List<Capability> withoutRefreshing(BundleManagerPlugin bundleManager, Requirement req, List<Capability> providers) {
        Iterator<Capability> itcap = providers.iterator();
        while (itcap.hasNext()) {
            if (bundleManager.isHiddenByRefresh(itcap.next().getResource(), req.getResource()))
                itcap.remove();
        }
        return providers;
    }

    private void filterSingletons(Collection<? extends Resource> manres, Collection<Resource> optres) {
        Map<String, Resource> singletons = new HashMap<String, Resource>();
        for (Resource res : getCombinedResources(manres, optres)) {
            XResource xres = (XResource) res;
            XIdentityCapability icap = xres.getIdentityCapability();
            if (icap.isSingleton()) {
                if (singletons.get(icap.getName()) != null) {
                    manres.remove(res);
                    optres.remove(res);
                } else {
                    singletons.put(icap.getName(), xres);
                }
            }
        }
    }

    private Map<Resource, Wiring> applyResolverResults(XEnvironment environment, Map<Resource, List<Wire>> wiremap) throws ResolutionException {

        // [TODO] Revisit how we apply the resolution results
        // An exception in one of the steps may leave the framework partially modified

        // Transform the wiremap to {@link BundleRevision} and {@link BundleWire}
        Map<BundleRevision, List<BundleWire>> brevmap = new LinkedHashMap<BundleRevision, List<BundleWire>>();
        for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
            List<BundleWire> bwires = new ArrayList<BundleWire>();
            List<Wire> wires = new ArrayList<Wire>();
            for (Wire wire : entry.getValue()) {
                AbstractBundleWire bwire = new AbstractBundleWire(wire);
                bwires.add(bwire);
                wires.add(bwire);
            }
            Resource res = entry.getKey();
            brevmap.put((BundleRevision) res, bwires);
            wiremap.put(res, wires);
        }

        // Attach the fragments to host
        attachFragmentsToHost(brevmap);

        // Resolve native code libraries if there are any
        try {
            resolveNativeCodeLibraries(brevmap);
        } catch (BundleException ex) {
            throw new ResolutionException(ex);
        }

        // For every resolved host bundle create the {@link ModuleSpec}
        addModules(brevmap);

        // For every resolved host bundle create a {@link Module} service
        createModuleServices(brevmap);

        // Construct and apply the resource wiring map
        Map<Resource, Wiring> wirings = environment.updateWiring(wiremap);
        for (Entry<Resource, Wiring> entry : wirings.entrySet()) {
            XBundleRevision res = (XBundleRevision) entry.getKey();
            res.getWiringSupport().setWiring((XWiring) entry.getValue());
        }

        // Change the bundle state to RESOLVED
        setBundleStatesToResolved(brevmap);

        return wirings;
    }

    private void attachFragmentsToHost(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            if (brev.isFragment()) {
                FragmentBundleRevision fragRev = (FragmentBundleRevision) brev;
                for (BundleWire wire : entry.getValue()) {
                    BundleCapability cap = wire.getCapability();
                    if (HostNamespace.HOST_NAMESPACE.equals(cap.getNamespace())) {
                        if (cap.getResource() instanceof HostBundleRevision) {
                            HostBundleRevision hostRev = (HostBundleRevision) cap.getResource();
                            fragRev.attachToHost(hostRev);
                        }
                    }
                }
            }
        }
    }

    private void resolveNativeCodeLibraries(Map<BundleRevision, List<BundleWire>> wiremap) throws BundleException {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            if (brev instanceof UserBundleRevision) {
                UserBundleRevision userRev = (UserBundleRevision) brev;
                Deployment deployment = userRev.getDeployment();

                // Resolve the native code libraries, if there are any
                NativeLibraryMetaData libMetaData = deployment.getAttachment(NATIVE_LIBRARY_METADATA_KEY);
                if (libMetaData != null) {
                    nativeCode.resolveNativeCode(userRev);
                }
            }
        }
    }

    private void addModules(Map<BundleRevision, List<BundleWire>> wiremap) {
        // The module of a requirer may depend on the wires of a provider that is wired by the same resolve operation
        ModuleManagerImpl.setResolveWires(wiremap);
        try {
            for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
                XBundleRevision brev = (XBundleRevision) entry.getKey();
                if (brev.isFragment() == false) {
                    List<BundleWire> wires = wiremap.get(brev);
                    moduleManager.addModule(brev, wires);
                }
            }
        } finally {
            ModuleManagerImpl.setResolveWires(null);
        }
    }

    private void createModuleServices(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            XBundleRevision brev = (XBundleRevision) entry.getKey();
            List<BundleWire> wires = entry.getValue();
            XBundle bundle = brev.getBundle();
            if (bundle != null && bundle.getBundleId() != 0 && !brev.isFragment()) {
                moduleLoader.createModuleService(brev, wires);
            }
        }
    }

    private void setBundleStatesToResolved(Map<BundleRevision, List<BundleWire>> wiremap) {
        for (Map.Entry<BundleRevision, List<BundleWire>> entry : wiremap.entrySet()) {
            Bundle bundle = entry.getKey().getBundle();
            // The state of the system bundle follows the framework, it is STARTING while it gets wired
            // Only the current revision of an INSTALLED bundle makes it RESOLVED, not an older revision of an updated bundle
            if (bundle instanceof AbstractBundleState && bundle.getBundleId() != 0 && isCurrentRevision(entry.getKey())) {
                AbstractBundleState<?> bundleState = (AbstractBundleState<?>) bundle;
                if (bundleState.getState() == Bundle.INSTALLED)
                    bundleState.changeState(Bundle.RESOLVED, 0);
            }
        }
    }

    private static boolean isCurrentRevision(BundleRevision brev) {
        Bundle bundle = brev.getBundle();
        return bundle instanceof XBundle && ((XBundle) bundle).getBundleRevision() == brev;
    }

    private void sendBundleResolvedEvents(Map<Resource, List<Wire>> wiremap) {
        for (Entry<Resource, List<Wire>> entry : wiremap.entrySet()) {
            Resource res = entry.getKey();
            if (res instanceof XBundleRevision) {
                XBundleRevision brev = (XBundleRevision) res;
                Bundle bundle = brev.getBundle();
                if (bundle instanceof AbstractBundleState && isCurrentRevision(brev)) {
                    AbstractBundleState<?> bundleState = (AbstractBundleState<?>) bundle;
                    if (bundleManager.isFrameworkCreated()) {
                        bundleState.fireBundleEvent(BundleEvent.RESOLVED);
                    }
                }
            }
        }
    }
}
