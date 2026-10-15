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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.jboss.osgi.framework.spi.FrameworkModuleProvider;
import org.jboss.osgi.framework.spi.FrameworkWiringLock;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.LockManager.LockContext;
import org.jboss.osgi.framework.spi.LockManager.Method;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.framework.spi.VirtualFileResourceLoader;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.spi.AbstractBundleWire;
import org.jboss.osgi.spi.AttachmentKey;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.resource.Capability;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;

/**
 * Support for framework extension bundles.
 *
 * A framework extension is a fragment of the system bundle. Its requirements can only be wired to the
 * system bundle or to another extension, and its content becomes part of the framework.
 *
 * <ul>
 * <li>The resolver cannot attach a fragment to a host that is already resolved. Extensions are therefore wired here.
 * <li>The host and execution environment requirements of an extension are wired from the extension. All other
 * requirements are hosted by the system bundle, package requirements are not wired at all.
 * <li>An extension that is updated or uninstalled stays attached until the framework is launched again. Refreshing
 * such an extension stops the framework with {@link FrameworkEvent#STOPPED_SYSTEM_REFRESHED}.
 * <li>The extension bundle activators are started when the framework is initialized, or when their
 * extension is resolved later. They are stopped when the framework stops.
 * </ul>
 *
 * @since 11-Oct-2026
 */
final class FrameworkExtensions {

    /** Marks the revision of an extension that is attached to the system bundle */
    static final AttachmentKey<Boolean> ATTACHED_KEY = AttachmentKey.create(Boolean.class);

    private final BundleManagerPlugin bundleManager;
    private final List<FragmentBundleRevision> attached = new ArrayList<FragmentBundleRevision>();
    private final Set<Long> attachedBundleIds = new HashSet<Long>();
    private final List<StartedActivator> startedActivators = new ArrayList<StartedActivator>();
    private volatile boolean initializing;

    FrameworkExtensions(BundleManagerPlugin bundleManager) {
        this.bundleManager = bundleManager;
    }

    /**
     * True if the given revision is a fragment of the system bundle.
     */
    static boolean isExtension(Resource res) {
        if (res instanceof FragmentBundleRevision == false)
            return false;
        String fragmentHost = ((FragmentBundleRevision) res).getOSGiMetaData().getFragmentHost().getAttribute();
        return isSystemBundleName(fragmentHost);
    }

    /**
     * True if the name is the symbolic name of the system bundle: its implementation name or the alias system.bundle
     */
    static boolean isSystemBundleName(String symbolicName) {
        return Constants.SYSTEM_BUNDLE_SYMBOLICNAME.equals(symbolicName) || org.jboss.osgi.framework.Constants.JBOSGI_FRAMEWORK_SYMBOLIC_NAME.equals(symbolicName);
    }

    /**
     * True if the content of the given revision is part of the framework.
     */
    static boolean isAttachedExtension(Resource res) {
        return res instanceof XBundleRevision && Boolean.TRUE.equals(((XBundleRevision) res).getAttachment(ATTACHED_KEY));
    }

    /**
     * True if the framework module can take the content of extensions.
     */
    boolean isSupported() {
        return getModuleProvider().supportsExtensions();
    }

    /**
     * True while the framework is initialized. Only the system bundle and the extensions can be resolved then.
     */
    boolean isInitializing() {
        return initializing;
    }

    /**
     * True if a revision of the given bundle is attached to the system bundle.
     */
    boolean isAttached(Bundle bundle) {
        synchronized (attached) {
            return attachedBundleIds.contains(bundle.getBundleId());
        }
    }

    /**
     * The revisions of the extensions that are attached to the system bundle.
     */
    List<XBundleRevision> getAttachedRevisions() {
        synchronized (attached) {
            return new ArrayList<XBundleRevision>(attached);
        }
    }

    boolean hasAttachedExtensions() {
        synchronized (attached) {
            return !attached.isEmpty();
        }
    }

    private FrameworkModuleProvider getModuleProvider() {
        return bundleManager.getFrameworkState().getFrameworkModuleProvider();
    }

    /**
     * Resolve the installed extensions and start their activators. This is the last step of the framework initialization.
     */
    void initialize() {
        initializing = true;
        try {
            resolve();
        } finally {
            initializing = false;
        }
    }

    /**
     * Resolve the installed extensions that can be resolved, and start their activators.
     *
     * @return The extensions that got resolved with their wires. Wires that are hosted by the system bundle are not included.
     */
    Map<Resource, List<Wire>> resolve() {
        if (isSupported() == false)
            return Collections.emptyMap();

        Map<Resource, List<Wire>> result = new LinkedHashMap<Resource, List<Wire>>();
        List<FragmentBundleRevision> resolved = new ArrayList<FragmentBundleRevision>();

        LockManager lockManager = bundleManager.getFrameworkState().getLockManager();
        LockContext lockContext = lockManager.lockItems(Method.RESOLVE, lockManager.getItemForType(FrameworkWiringLock.class));
        try {
            XBundleRevision sysrev = bundleManager.getSystemBundle().getBundleRevision();

            // An extension may depend on another extension that is attached in the same operation. First the
            // extensions whose requirements can be satisfied are determined, then they are attached in the order of
            // their bundle ids, which is the order of the framework class path (3.15.2)
            List<FragmentBundleRevision> candidates = getCandidates();
            Map<FragmentBundleRevision, List<Wire>[]> selected = new LinkedHashMap<FragmentBundleRevision, List<Wire>[]>();
            boolean progress = true;
            while (progress && candidates.isEmpty() == false) {
                progress = false;
                for (FragmentBundleRevision fragRev : new ArrayList<FragmentBundleRevision>(candidates)) {
                    List<XBundleRevision> providers = new ArrayList<XBundleRevision>();
                    providers.add(sysrev);
                    synchronized (attached) {
                        providers.addAll(attached);
                    }
                    providers.addAll(selected.keySet());
                    // The capabilities of the extension are those of the system bundle once it is attached,
                    // an extension can import a package that it exports (3.8.1)
                    providers.add(fragRev);

                    List<Wire> extwires = new ArrayList<Wire>();
                    List<Wire> hostedwires = new ArrayList<Wire>();
                    if (getWires(fragRev, sysrev, providers, extwires, hostedwires) == false)
                        continue;

                    @SuppressWarnings("unchecked")
                    List<Wire>[] wires = new List[] { extwires, hostedwires };
                    selected.put(fragRev, wires);
                    candidates.remove(fragRev);
                    progress = true;
                }
            }
            for (FragmentBundleRevision fragRev : candidates)
                LOGGER.debugf("Cannot resolve framework extension: %s", fragRev);

            List<FragmentBundleRevision> ordered = new ArrayList<FragmentBundleRevision>(selected.keySet());
            Collections.sort(ordered, new Comparator<FragmentBundleRevision>() {
                @Override
                public int compare(FragmentBundleRevision rev1, FragmentBundleRevision rev2) {
                    return Long.compare(rev1.getBundle().getBundleId(), rev2.getBundle().getBundleId());
                }
            });
            for (FragmentBundleRevision fragRev : ordered) {
                List<Wire> extwires = selected.get(fragRev)[0];
                List<Wire> hostedwires = selected.get(fragRev)[1];

                attach(fragRev);

                Map<Resource, List<Wire>> wiremap = new LinkedHashMap<Resource, List<Wire>>();
                wiremap.put(fragRev, extwires);
                if (hostedwires.isEmpty() == false)
                    wiremap.put(sysrev, hostedwires);
                bundleManager.getFrameworkState().getEnvironment().updateWiring(wiremap);

                fragRev.getBundleState().changeState(Bundle.RESOLVED, 0);
                result.put(fragRev, extwires);
                resolved.add(fragRev);
            }
        } finally {
            lockManager.unlockItems(lockContext);
        }

        // The activator is started before the RESOLVED event is fired
        for (FragmentBundleRevision fragRev : resolved) {
            startActivator(fragRev);
            if (bundleManager.isFrameworkCreated())
                fragRev.getBundleState().fireBundleEvent(BundleEvent.RESOLVED);
        }
        return result;
    }

    /**
     * Get the installed extensions that may be attached, in the order of their bundle ids.
     *
     * Only one version of an extension can be attached, that is the highest version.
     * A bundle that has a revision attached cannot be attached again until the framework is launched again.
     */
    private List<FragmentBundleRevision> getCandidates() {
        Map<String, FragmentBundleRevision> candidates = new LinkedHashMap<String, FragmentBundleRevision>();
        Set<String> attachedNames = new HashSet<String>();
        synchronized (attached) {
            for (FragmentBundleRevision fragRev : attached)
                attachedNames.add(fragRev.getSymbolicName());
        }
        for (XBundle bundle : bundleManager.getBundles(Bundle.INSTALLED)) {
            XBundleRevision brev = bundle.getBundleRevision();
            if (isExtension(brev) == false || brev.getWiringSupport().getWiring(false) != null || isAttached(bundle))
                continue;

            String symbolicName = brev.getSymbolicName();
            if (attachedNames.contains(symbolicName))
                continue;

            FragmentBundleRevision other = candidates.get(symbolicName);
            if (other == null || other.getVersion().compareTo(brev.getVersion()) < 0)
                candidates.put(symbolicName, (FragmentBundleRevision) brev);
        }
        List<FragmentBundleRevision> result = new ArrayList<FragmentBundleRevision>(candidates.values());
        Collections.sort(result, new Comparator<FragmentBundleRevision>() {
            @Override
            public int compare(FragmentBundleRevision rev1, FragmentBundleRevision rev2) {
                return Long.compare(rev1.getBundle().getBundleId(), rev2.getBundle().getBundleId());
            }
        });
        return result;
    }

    /**
     * Get the wires for the requirements of the given extension.
     *
     * @return false if a mandatory requirement cannot be satisfied by the system bundle or an attached extension
     */
    private boolean getWires(FragmentBundleRevision fragRev, XBundleRevision sysrev, List<XBundleRevision> providers, List<Wire> extwires, List<Wire> hostedwires) {
        for (Requirement req : fragRev.getRequirements(null)) {
            String effective = req.getDirectives().get(Namespace.REQUIREMENT_EFFECTIVE_DIRECTIVE);
            if (effective != null && !Namespace.EFFECTIVE_RESOLVE.equals(effective))
                continue;

            Capability match = null;
            for (XBundleRevision provider : providers) {
                for (Capability cap : provider.getCapabilities(req.getNamespace())) {
                    if (((XRequirement) req).matches(cap)) {
                        match = cap;
                        break;
                    }
                }
                if (match != null)
                    break;
            }
            if (match == null) {
                if (Namespace.RESOLUTION_OPTIONAL.equals(req.getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE)))
                    continue;
                LOGGER.debugf("Unsatisfied requirement of framework extension: %s", req);
                return false;
            }

            String namespace = req.getNamespace();
            BundleCapability bcap = (BundleCapability) match;
            BundleRequirement breq = (BundleRequirement) req;
            if (HostNamespace.HOST_NAMESPACE.equals(namespace) || ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE.equals(namespace)) {
                // These requirements stay with the extension
                extwires.add(new AbstractBundleWire(bcap, breq, sysrev, fragRev));
            } else if (PackageNamespace.PACKAGE_NAMESPACE.equals(namespace)) {
                // The system bundle only exports packages. The package is available from the framework
                continue;
            } else {
                // All other requirements are hosted by the system bundle. So are the capabilities of an extension, except its identity
                boolean identity = IdentityNamespace.IDENTITY_NAMESPACE.equals(match.getNamespace());
                XBundleRevision provider = identity ? (XBundleRevision) match.getResource() : sysrev;
                hostedwires.add(new AbstractBundleWire(bcap, breq, provider, sysrev));
            }
        }
        return true;
    }

    /**
     * Make the content of an extension part of the framework.
     */
    private void attach(FragmentBundleRevision fragRev) {
        FrameworkModuleProvider moduleProvider = getModuleProvider();
        for (RevisionContent revContent : fragRev.getClassPathContent()) {
            // The connector of a connect framework provides the classes of a connect extension itself, the framework
            // does not load anything from its content (60.3.6)
            if (ConnectVirtualFile.isConnectFile(revContent.getVirtualFile())) {
                LOGGER.debugf("Connect extension content is not added to the framework: %s", revContent);
                continue;
            }
            VirtualFileResourceLoader loader = new VirtualFileResourceLoader(revContent.getVirtualFile(), revContent.getVersionedRoots(), revContent.isMultiRelease());
            // Only the bundle jar itself can be appended to a class path
            URL contentURL = revContent.getContentId() == 0 ? getContentURL(fragRev) : null;
            boolean classPath = moduleProvider.addExtensionContent(contentURL, loader);
            LOGGER.debugf("Attached framework extension %s [classpath=%s]", revContent, classPath);
        }
        fragRev.putAttachment(ATTACHED_KEY, Boolean.TRUE);
        synchronized (attached) {
            attached.add(fragRev);
            attachedBundleIds.add(fragRev.getBundle().getBundleId());
        }
    }

    private URL getContentURL(FragmentBundleRevision fragRev) {
        StorageState storageState = fragRev.getStorageState();
        String fileName = storageState.getProperties().getProperty(StorageState.PROPERTY_BUNDLE_FILE);
        if (fileName == null)
            return null;
        File file = new File(storageState.getStorageDir(), fileName);
        if (file.isFile() == false)
            return null;
        // A class loader ignores a URL that is on its class path already and keeps a jar that it opened. A framework
        // that is launched again in the same JVM, e.g. after the storage was cleaned, appends its content under a name
        // that is unique for the framework instance
        File copy = new File(storageState.getStorageDir(), "extension-" + bundleManager.getProperty(Constants.FRAMEWORK_UUID) + ".jar");
        try {
            File[] previous = storageState.getStorageDir().listFiles();
            for (File aux : previous != null ? previous : new File[0]) {
                if (aux.getName().startsWith("extension-") && aux.getName().endsWith(".jar") && aux.equals(copy) == false)
                    aux.delete();
            }
            Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return copy.toURI().toURL();
        } catch (IOException ex) {
            LOGGER.debugf(ex, "Cannot copy the content of extension: %s", fragRev);
            return null;
        }
    }

    private void startActivator(FragmentBundleRevision fragRev) {
        String className = fragRev.getOSGiMetaData().getHeader(Constants.EXTENSION_BUNDLE_ACTIVATOR);
        if (className == null || className.trim().isEmpty())
            return;

        XBundle bundle = fragRev.getBundle();
        try {
            ClassLoader classLoader = bundleManager.getFrameworkState().getModuleManager().getFrameworkModule().getClassLoader();
            Object instance = classLoader.loadClass(className.trim()).getConstructor().newInstance();
            BundleActivator activator = (BundleActivator) instance;
            activator.start(getSystemContext());
            synchronized (startedActivators) {
                startedActivators.add(new StartedActivator(bundle, activator));
            }
        } catch (Throwable th) {
            fireActivatorError(bundle, "Cannot start extension bundle activator: " + className, th);
        }
    }

    /**
     * Stop the activators that were started successfully, in reverse order.
     * This is called while the framework is stopping and still has a valid bundle context.
     */
    void stopActivators() {
        List<StartedActivator> activators;
        synchronized (startedActivators) {
            activators = new ArrayList<StartedActivator>(startedActivators);
            startedActivators.clear();
        }
        for (ListIterator<StartedActivator> it = activators.listIterator(activators.size()); it.hasPrevious();) {
            StartedActivator started = it.previous();
            try {
                started.activator.stop(getSystemContext());
            } catch (Throwable th) {
                fireActivatorError(started.bundle, "Cannot stop extension bundle activator: " + started.activator.getClass().getName(), th);
            }
        }
    }

    private BundleContext getSystemContext() {
        return bundleManager.getSystemBundle().getBundleContext();
    }

    private void fireActivatorError(XBundle bundle, String message, Throwable th) {
        BundleException ex = new BundleException(message, BundleException.ACTIVATOR_ERROR, th);
        FrameworkEventsImpl events = (FrameworkEventsImpl) bundleManager.getFrameworkState().getFrameworkEvents();
        events.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, ex);
        // The error is reported before the framework carries on
        events.awaitFrameworkEvents();
    }

    private static final class StartedActivator {

        final XBundle bundle;
        final BundleActivator activator;

        StartedActivator(XBundle bundle, BundleActivator activator) {
            this.bundle = bundle;
            this.activator = activator;
        }
    }
}
