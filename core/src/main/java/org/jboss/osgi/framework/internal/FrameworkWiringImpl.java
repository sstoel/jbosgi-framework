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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import java.util.regex.Pattern;
import org.jboss.osgi.framework.spi.BundleLifecycle.BundleRefreshPolicy;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.FrameworkWiringLock;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.LockManager.LockContext;
import org.jboss.osgi.framework.spi.LockManager.LockableItem;
import org.jboss.osgi.framework.spi.LockManager.Method;
import org.jboss.osgi.framework.spi.LockUtils;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.XWiringSupport;
import org.jboss.osgi.resolver.spi.AbstractBundleWire;
import org.jboss.osgi.resolver.spi.ResolverHookException;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.jboss.osgi.resolver.XRequirement;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.resource.Namespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.ResolutionException;

/**
 * An implementation of the {@link FrameworkWiringSupport} service.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 08-Nov-2012
 */
public final class FrameworkWiringImpl implements FrameworkWiring {

    private final BundleManagerPlugin bundleManager;
    private final FrameworkEvents events;
    private final XResolver resolver;
    private final LockManager lockManager;
    private final XEnvironment environment;
    private final ExecutorService executorService;

    public FrameworkWiringImpl(BundleManager bundleManager, FrameworkEvents events, XEnvironment environment, XResolver resolver, LockManager lockManager, ExecutorService executorService) {
        this.bundleManager = (BundleManagerPlugin) bundleManager;
        this.events = events;
        this.environment = environment;
        this.resolver = resolver;
        this.lockManager = lockManager;
        this.executorService = executorService;
    }

    @Override
    public Bundle getBundle() {
        return bundleManager.getSystemBundle();
    }

    @Override
    public void refreshBundles(final Collection<Bundle> bundles, final FrameworkListener... listeners) {

        // Refreshing a framework extension requires the framework to be launched again
        boolean relaunch = refreshesFrameworkExtension(bundles);
        if (relaunch && bundleManager.isLaunchedByFramework()) {
            LOGGER.debugf("Refresh of framework extension, stop the framework: %s", bundles);
            bundleManager.stopFramework(FrameworkEvent.STOPPED_SYSTEM_REFRESHED, null);
            return;
        }

        final List<Bundle> bundlesToRefresh = new ArrayList<Bundle>();
        final List<XBundle> dependencyClosure = new ArrayList<XBundle>();

        LockContext lockContext = lockEnvironment(LockManager.Method.REFRESH);
        try {
            if (bundles == null) {
                bundlesToRefresh.addAll(getRemovalPendingBundles());
            } else {
                bundlesToRefresh.addAll(bundles);
            }

            // A framework that is not launched through its Framework object cannot be launched again by a refresh.
            // The content of an extension stays attached until the framework is launched again, the other bundles are refreshed
            if (relaunch) {
                FrameworkExtensions extensions = bundleManager.getFrameworkExtensions();
                for (Iterator<Bundle> it = bundlesToRefresh.iterator(); it.hasNext();) {
                    Bundle bundle = it.next();
                    if (bundle.getBundleId() == 0 || extensions.isAttached(bundle)) {
                        LOGGER.warnf("Refresh of %s requires the framework to be launched again, it stays attached until then", bundle);
                        it.remove();
                    }
                }
            }

            // Compute all depending bundles that need to be stopped and unresolved.
            for (Bundle auxBundle : getDependencyClosure(bundlesToRefresh)) {
                XBundle bundle = (XBundle) auxBundle;
                dependencyClosure.add(bundle);
            }
        } finally {
            unlockEnvironment(lockContext);
        }

        LOGGER.debugf("Refresh bundles %s", bundlesToRefresh);
        LOGGER.debugf("Dependency closure %s", dependencyClosure);

        Runnable runner = new Runnable() {
            public void run() {
                refreshBundlesInternal(dependencyClosure, listeners);
            }
        };

        if (!executorService.isShutdown()) {
            executorService.execute(runner);
            //runner.run();
        }
    }

    private boolean refreshesFrameworkExtension(Collection<Bundle> bundles) {
        FrameworkExtensions extensions = bundleManager.getFrameworkExtensions();
        if (extensions.hasAttachedExtensions() == false)
            return false;

        // The content of an extension stays attached to the system bundle
        Collection<Bundle> candidates = bundles != null ? bundles : getRemovalPendingBundles();
        for (Bundle bundle : candidates) {
            if (bundle.getBundleId() == 0 || extensions.isAttached(bundle))
                return true;
        }
        return false;
    }

    /**
     * The listeners get the PACKAGES_REFRESHED event also when the refresh failed, the failure is an ERROR event
     */
    private void refreshBundlesInternal(List<XBundle> dependencyClosure, FrameworkListener... listeners) {
        XBundle systemBundle = bundleManager.getSystemBundle();
        try {
            refreshBundlesLocked(dependencyClosure);
        } catch (Throwable th) {
            events.fireFrameworkEvent(systemBundle, FrameworkEvent.ERROR, th);
        } finally {
            events.fireFrameworkEvent(systemBundle, FrameworkEvent.PACKAGES_REFRESHED, null, listeners);
        }
    }

    private void refreshBundlesLocked(List<XBundle> dependencyClosure) {

        List<XBundle> stopList = new ArrayList<XBundle>();
        List<XBundle> uninstallBundles = new ArrayList<XBundle>();

        // The bundles of the dependency closure are locked for the whole refresh, together with the framework wiring
        // lock: never one after the other, a lifecycle operation that holds the wiring lock may wait for a bundle of the
        // closure. A refresh policy that refreshes the revisions with operations of a server gets the bundles without
        // the wiring lock: another operation of the server may hold the server's lock and wait for the wiring lock. The
        // revisions that it refreshes are no providers for other bundles meanwhile
        BundleRefreshPolicy refreshPolicy = bundleManager.getFrameworkState().getCoreServices().getBundleLifecycle().getBundleRefreshPolicy();
        LockableItem wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
        XBundle[] bundles = dependencyClosure.toArray(new XBundle[dependencyClosure.size()]);
        LockContext wiringContext = lockManager.lockItems(Method.REFRESH, LockUtils.getLockableItems(bundles, new LockableItem[] { wireLock }));
        LockContext bundlesContext = null;
        try {
            // The states are taken under the locks: a bundle that was started while the refresh waited is stopped too
            for (Bundle auxBundle : dependencyClosure) {
                XBundle bundle = (XBundle) auxBundle;
                int state = bundle.getState();
                if (state == Bundle.UNINSTALLED) {
                    uninstallBundles.add(bundle);
                } else if (state == Bundle.ACTIVE || state == Bundle.STARTING) {
                    stopList.add(bundle);
                }
            }

            BundleStartLevelComparator startLevelComparator = new BundleStartLevelComparator();
            Collections.sort(stopList, startLevelComparator);

            for (ListIterator<XBundle> it = stopList.listIterator(stopList.size()); it.hasPrevious();) {
                XBundle bundle = it.previous();
                try {
                    bundleManager.stopBundleLifecycle(bundle, Bundle.STOP_TRANSIENT);
                } catch (Exception th) {
                    events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, th);
                }
            }

            List<XBundle> refreshBundles = new ArrayList<XBundle>(dependencyClosure);
            bundleManager.beginRefresh(refreshBundles);
            try {
                if (refreshPolicy.isWiringLockAllowed() == false) {
                    // The bundles stay locked, the lock items are reentrant
                    bundlesContext = lockManager.lockItems(Method.REFRESH, LockUtils.getLockableItems(bundles, null));
                    lockManager.unlockItems(wiringContext);
                    wiringContext = null;
                }
                refreshBundlesInClosure(dependencyClosure, uninstallBundles);
            } finally {
                bundleManager.endRefresh(refreshBundles);
            }

            if (wiringContext == null) {
                lockManager.unlockItems(bundlesContext);
                bundlesContext = null;
                wiringContext = lockManager.lockItems(Method.REFRESH, LockUtils.getLockableItems(bundles, new LockableItem[] { wireLock }));
            }
            for (XBundle bundle : stopList) {
                try {
                    bundleManager.startBundleLifecycle(bundle, Bundle.START_TRANSIENT);
                } catch (Exception th) {
                    events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, th);
                }
            }
        } finally {
            if (bundlesContext != null)
                lockManager.unlockItems(bundlesContext);
            if (wiringContext != null)
                lockManager.unlockItems(wiringContext);
        }
    }

    // Refresh the bundles of the dependency closure, the stopped ones are started again by the caller
    private void refreshBundlesInClosure(List<XBundle> dependencyClosure, List<XBundle> uninstallBundles) {
        List<UserBundleState> removeBundles = new ArrayList<UserBundleState>();
        for (XBundle bundle : uninstallBundles) {
            if (bundle instanceof UserBundleState) {
                // Removed when the bundles that depend on it are refreshed
                removeBundles.add((UserBundleState) bundle);
            } else {
                XBundleRevision current = bundle.getBundleRevision();
                BundleRevisions brevs = bundle.adapt(BundleRevisions.class);
                for (BundleRevision aux : brevs.getRevisions()) {
                    XBundleRevision brev = (XBundleRevision) aux;
                    if (brev != current) {
                        bundleManager.removeRevisionLifecycle(brev, 0);
                    }
                }
                // The removal pending revision of a module bundle whose module went away
                if (current.getWiringSupport().isEffective() == false) {
                    bundleManager.removeRevision(current, 0);
                }
            }
            dependencyClosure.remove(bundle);
        }

        for (XBundle bundle : dependencyClosure) {
            if (bundle instanceof UserBundleState) {
                try {
                    UserBundleState userBundle = (UserBundleState) bundle;
                    userBundle.refresh();
                } catch (Exception th) {
                    events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, th);
                }
            } else {
                // nothing to do for adapted modules
            }
        }

        // No bundle uses the uninstalled bundles any more, their revisions are removed (7.5.1)
        for (UserBundleState userBundle : removeBundles) {
            try {
                bundleManager.unresolveBundle(userBundle);
                bundleManager.removeBundle(userBundle, InternalConstants.UNINSTALL_INTERNAL);
            } catch (Exception th) {
                events.fireFrameworkEvent(userBundle, FrameworkEvent.ERROR, th);
            }
        }
    }

    @Override
    public boolean resolveBundles(Collection<Bundle> bundles) {

        // Only bundles that are in state INSTALLED qualify as resolvable
        if (bundles == null) {
            Set<XBundle> bundleset = bundleManager.getBundles(Bundle.INSTALLED);
            bundles = new ArrayList<Bundle>(bundleset);
        }

        LOGGER.debugf("Resolve bundles %s", bundles);

        // Get the set of resolvable bundle revisions
        Set<BundleRevision> resolvableRevisions = new LinkedHashSet<BundleRevision>();
        for (Bundle aux : bundles) {
            XBundle bundleState = (XBundle) aux;
            resolvableRevisions.add(bundleState.getBundleRevision());
        }

        boolean result = true;
        try {
            XResolveContext context = resolver.createResolveContext(environment, null, resolvableRevisions);
            resolver.resolveAndApply(context);
            for (BundleRevision aux : resolvableRevisions) {
                if (aux.getWiring() == null) {
                    LOGGER.debugf("Cannot resolve: %s", aux);
                    result = false;
                }
            }
        } catch (ResolutionException ex) {
            LOGGER.debugf(ex, "Cannot resolve: %s", resolvableRevisions);
            result = false;
        } catch (ResolverHookException ex) {
            // The specified bundles that were resolved before stay resolved
            LOGGER.debugf(ex, "Cannot resolve: %s", resolvableRevisions);
            for (BundleRevision aux : resolvableRevisions) {
                if (aux.getWiring() == null)
                    result = false;
            }
        }
        return result;
    }

    /*
     * Returns the bundles that have non-current, in use bundle wirings. This is typically the bundles which have been updated
     * or uninstalled since the last call to refreshBundles
     */
    @Override
    public Collection<Bundle> getRemovalPendingBundles() {
        Collection<Bundle> result = new HashSet<Bundle>();
        for (XBundle bundle : bundleManager.getBundles(null)) {
            if (bundle.getBundleId() != 0) {
                BundleRevisions revisions = bundle.adapt(BundleRevisions.class);
                for (BundleRevision rev : revisions.getRevisions()) {
                    XBundleRevision brev = (XBundleRevision) rev;
                    XWiringSupport wiringSupport = brev.getWiringSupport();
                    if (!wiringSupport.isEffective()) {
                        BundleWiring bwiring = (BundleWiring) wiringSupport.getWiring(false);
                        if (bwiring != null && (bwiring.isInUse() || isModuleInUse(brev))) {
                            result.add(bundle);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    // The module of the revision may be used by modules that are not bundles
    private boolean isModuleInUse(XBundleRevision brev) {
        return bundleManager.getFrameworkState().getFrameworkModuleLoader().isModuleInUse(brev);
    }

    @Override
    public Collection<Bundle> getDependencyClosure(Collection<Bundle> bundles) {
        if (bundles == null)
            throw MESSAGES.illegalArgumentNull("bundles");

        Set<Bundle> closure = new HashSet<Bundle>();
        for (Bundle bundle : bundles) {
            transitiveDependencyClosure((XBundle) bundle, closure);
        }
        return Collections.unmodifiableCollection(closure);
    }

    @Override
    public Collection<BundleCapability> findProviders(final Requirement requirement) {
        Collection<BundleCapability> result = new HashSet<BundleCapability>();

        for (XBundle bundle : bundleManager.getBundles(null)) {

            // The current revision and the revisions that are still in use. Of an uninstalled bundle only the revisions
            // that are in use are left until the refresh (removal pending)
            Set<BundleRevision> brevs = new LinkedHashSet<BundleRevision>();
            if (bundle.getState() != Bundle.UNINSTALLED) {
                brevs.add(bundle.getBundleRevision());
            } else {
                BundleWiring wiring = bundle.getBundleRevision().getWiring();
                if (wiring != null && wiring.isInUse())
                    brevs.add(bundle.getBundleRevision());
            }
            BundleRevisions revisions = bundle.adapt(BundleRevisions.class);
            if (revisions != null) {
                for (BundleRevision brev : revisions.getRevisions()) {
                    BundleWiring wiring = brev.getWiring();
                    if (wiring != null && wiring.isInUse())
                        brevs.add(brev);
                }
            }
            for (BundleRevision brev : brevs) {
                for (BundleCapability bcap : brev.getDeclaredCapabilities(requirement.getNamespace())) {
                    if (matches(requirement, bcap))
                        result.add(bcap);
                }
            }
        }

        return result;
    }

    private boolean matches(Requirement requirement, BundleCapability cap) {
        if (requirement instanceof XRequirement)
            return ((XRequirement) requirement).matches(cap);

        String filterSpec = requirement.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
        if (filterSpec != null) {
            try {
                if (!FrameworkUtil.createFilter(filterSpec).matches(cap.getAttributes()))
                    return false;
            } catch (InvalidSyntaxException ex) {
                return false;
            }
        }

        // The mandatory attributes of the capability must be used by the filter
        String mandatory = cap.getDirectives().get(Constants.MANDATORY_DIRECTIVE);
        if (mandatory != null) {
            for (String attname : mandatory.split("[,\\s]+")) {
                if (attname.isEmpty())
                    continue;
                Pattern pattern = Pattern.compile("\\(\\s*" + Pattern.quote(attname) + "\\s*(=|~=|<=|>=)");
                if (filterSpec == null || !pattern.matcher(filterSpec).find())
                    return false;
            }
        }
        return true;
    }

    private void transitiveDependencyClosure(XBundle bundle, Set<Bundle> closure) {
        if (bundle.getBundleId() != 0 && closure.contains(bundle) == false) {
            closure.add(bundle);

            BundleRevisions brevs = bundle.adapt(BundleRevisions.class);
            for (BundleRevision aux : brevs.getRevisions()) {
                XBundleRevision brev = (XBundleRevision) aux;
                Wiring wiring = brev.getWiringSupport().getWiring(false);
                if (wiring != null) {
                    transitiveDependencyClosure(brev, wiring, closure);
                }
            }
        }
    }

    private void transitiveDependencyClosure(XBundleRevision brev, Wiring wiring, Set<Bundle> closure) {
        if (brev instanceof FragmentBundleRevision) {
            for (Wire wire : wiring.getRequiredResourceWires(HostNamespace.HOST_NAMESPACE)) {
                AbstractBundleWire bwire = (AbstractBundleWire) wire;
                Bundle provider = bwire.getProviderWiring(false).getBundle();
                transitiveDependencyClosure((XBundle) provider, closure);
            }
        } else {
            for (Wire wire : wiring.getProvidedResourceWires(null)) {
                XBundleRevision requirer = (XBundleRevision) wire.getRequirer();
                transitiveDependencyClosure(requirer.getBundle(), closure);
            }
        }
    }

    private LockContext lockEnvironment(Method method, XResource... resources) {
        FrameworkWiringLock wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
        return lockManager.lockItems(method, getLockableItems(wireLock, resources));
    }

    private LockableItem[] getLockableItems(LockableItem item, XResource... resources) {
        List<LockableItem> items = new ArrayList<LockableItem>();
        items.add(item);
        if (resources != null) {
            for (XResource res : resources) {
                XBundleRevision brev = (XBundleRevision) res;
                if (brev.getBundle() instanceof LockableItem) {
                    items.add((LockableItem) brev.getBundle());
                }
            }
        }
        return items.toArray(new LockableItem[items.size()]);
    }

    private void unlockEnvironment(LockContext context) {
        lockManager.unlockItems(context);
    }

    private static class BundleStartLevelComparator implements Comparator<Bundle> {
        @Override
        public int compare(Bundle o1, Bundle o2) {
            int sl1 = o1.adapt(BundleStartLevel.class).getStartLevel();
            int sl2 = o2.adapt(BundleStartLevel.class).getStartLevel();
            return sl1 - sl2 != 0 ? sl1 - sl2 : (int) (o1.getBundleId() - o2.getBundleId());
        }
    }
}
