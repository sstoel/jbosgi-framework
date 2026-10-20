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

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.jboss.logging.Logger.Level;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.deployment.interceptor.LifecycleInterceptorException;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.spi.BundleLifecycle;
import org.jboss.osgi.framework.spi.BundleLifecycle.BundleRefreshPolicy;
import org.jboss.osgi.framework.spi.DeploymentProvider;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.ServiceManager;
import org.jboss.osgi.framework.spi.ServiceState;
import org.jboss.osgi.framework.spi.StartLevelManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.metadata.ActivationPolicyMetaData;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XRequirement;
import org.jboss.osgi.resolver.XWiringSupport;
import org.jboss.osgi.resolver.spi.AbstractBundleWiring;
import org.jboss.osgi.resolver.spi.ResolverHookException;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleRevisions;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Wire;

/**
 * This is the internal implementation of a Bundle based on a user {@link Deployment}.
 *
 * @author thomas.diesler@jboss.com
 * @since 12-Aug-2010
 */
class UserBundleState extends AbstractBundleState<UserBundleRevision> {

    private final AtomicBoolean alreadyStarting = new AtomicBoolean();
    private final AtomicBoolean awaitLazyActivation = new AtomicBoolean();
    // A class was loaded from the bundle while it waited for its start level to be activated lazily
    private final AtomicBoolean classLoadedBeforeLazyStart = new AtomicBoolean();
    private final AtomicInteger revisionIndex = new AtomicInteger();
    private final List<UserBundleRevision> revisions = new ArrayList<UserBundleRevision>();

    private BundleActivator bundleActivator;
    private volatile Dictionary<String, String> headersBeforeUninstall;

    UserBundleState(FrameworkState frameworkState, UserBundleRevision brev) {
        super(frameworkState, brev, brev.getStorageState().getBundleId());
    }

    /**
     * Assert that the given bundle is an instance of {@link UserBundleState}
     */
    static UserBundleState assertBundleState(Bundle bundle) {
        bundle = AbstractBundleState.assertBundleState(bundle);
        assert bundle instanceof UserBundleState : "Not an UserBundleState: " + bundle;
        return (UserBundleState) bundle;
    }

    @Override
    public String getLocation() {
        return getBundleRevision().getLocation();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T adapt(Class<T> type) {
        T result = super.adapt(type);
        if (result == null) {
            if (type.isAssignableFrom(Deployment.class)) {
                result = (T) getDeployment();
            }
        }
        return result;
    }

    Deployment getDeployment() {
        return getBundleRevision().getDeployment();
    }

    @Override
    boolean isSingleton() {
        return getOSGiMetaData().isSingleton();
    }

    /**
     * A bundle awaits its lazy activation only once it was started with its declared activation policy (STARTING),
     * not because its manifest declares a lazy policy
     */
    void initLazyActivation() {
        awaitLazyActivation.set(false);
    }

    boolean isActivationLazy() {
        ActivationPolicyMetaData activationPolicy = getActivationPolicy();
        String policyType = (activationPolicy != null ? activationPolicy.getType() : null);
        return Constants.ACTIVATION_LAZY.equals(policyType);
    }

    ActivationPolicyMetaData getActivationPolicy() {
        return getOSGiMetaData().getBundleActivationPolicy();
    }

    boolean awaitLazyActivation() {
        return awaitLazyActivation.get();
    }

    /**
     * A class that would trigger the lazy activation is loaded while the bundle is persistently started with its declared
     * activation policy but waits for its start level: the bundle is activated right away when the start level is met.
     */
    void lazyClassLoaded() {
        if (awaitLazyActivation.get() == false && getState() == RESOLVED && isActivationLazy() && isPersistentlyStarted() && isActivationPolicyUsed())
            classLoadedBeforeLazyStart.set(true);
    }

    void activateLazily() throws BundleException {
        // No lazy activation while the framework is shutting down: the bundle stays STARTING
        if (getBundleManager().getManagerState() == Bundle.STOPPING)
            return;
        if (getState() == STARTING && awaitLazyActivation.getAndSet(false)) {
            if (startLevelValidForStart() == true) {
                // The declared activation policy was applied when the bundle was started, the trigger activates it
                int options = START_TRANSIENT;
                LOGGER.debugf("Lazy activation of: %s", this);
                getBundleManager().startBundleLifecycle(this, options);
            }
        }
    }

    void setBundleActivationPolicyUsed(boolean usePolicy) {
        StorageState storageState = getStorageState();
        storageState.setBundleActivationPolicyUsed(usePolicy);
    }

    boolean isAlreadyStarting() {
        return alreadyStarting.get();
    }

    @Override
    public Dictionary<String, String> getHeaders(String locale) {
        // This method must continue to return Manifest header information while this bundle is in the UNINSTALLED state,
        // however the header values must only be available in the raw and default locale values
        Dictionary<String, String> headersOnUninstall = getBundleRevision().getHeadersOnUninstall();
        if (headersOnUninstall != null)
            return headersOnUninstall;

        return super.getHeaders(locale);
    }

    @Override
    BundleRevisions getBundleRevisions() {
        synchronized (revisions) {
            final Bundle bundle = this;
            return new BundleRevisions() {

                @Override
                public Bundle getBundle() {
                    return bundle;
                }

                @Override
                public List<BundleRevision> getRevisions() {
                    List<BundleRevision> bundleRevisions = new ArrayList<BundleRevision>(revisions);
                    return Collections.unmodifiableList(bundleRevisions);
                }

                @Override
                public String toString() {
                    return bundle + ": " + revisions;
                }
            };
        }
    }

    @Override
    void addBundleRevision(UserBundleRevision brev) {
        synchronized (revisions) {
            super.addBundleRevision(brev);
            if (!isFragment()) {
                ModuleManager moduleManager = getFrameworkState().getModuleManager();
                ModuleIdentifier moduleIdentifier = moduleManager.getModuleIdentifier(brev);
                brev.putAttachment(IntegrationConstants.MODULE_IDENTIFIER_KEY, moduleIdentifier);
            }
            revisionIndex.incrementAndGet();
            revisions.add(0, brev);
        }
    }

    int getRevisionIndex() {
        return revisionIndex.get();
    }

    List<XBundleRevision> getAllBundleRevisions() {
        synchronized (revisions) {
            List<XBundleRevision> result = new ArrayList<XBundleRevision>(revisions);
            Collections.reverse(result);
            return Collections.unmodifiableList(result);
        }
    }

    void removeRevision(UserBundleRevision rev) {
        synchronized (revisions) {
            revisions.remove(rev);
        }
    }

    @Override
    UserBundleRevision getBundleRevisionById(int revisionId) {
        synchronized (revisions) {
            for (UserBundleRevision rev : revisions) {
                if (rev.getRevisionId() == revisionId) {
                    return rev;
                }
            }
            return null;
        }
    }

    @Override
    int updateInternal(InputStream input) throws BundleException {

        LOGGER.debugf("Updating bundle: %s", this);

        boolean restart = false;
        // A bundle that is started with its declared activation policy is started that way again: a lazily started
        // bundle waits for its activation again
        int restartOptions = Bundle.START_TRANSIENT;
        if (adapt(BundleStartLevel.class).isActivationPolicyUsed())
            restartOptions |= Bundle.START_ACTIVATION_POLICY;

        if (isFragment() == false) {
            int state = getState();
            if (state == Bundle.ACTIVE || state == Bundle.STARTING || state == Bundle.STOPPING) {
                // If this bundle's state is ACTIVE, STARTING or STOPPING, this bundle is stopped
                // If Bundle.stop throws an exception, the exception is rethrown terminating the update.
                getBundleManager().stopBundle(this, Bundle.STOP_TRANSIENT);
                if (state != Bundle.STOPPING)
                    restart = true;
            }
        }

        // If the Framework is unable to install the updated version of this bundle, the original
        // version of this bundle must be restored and a BundleException must be thrown after
        // completion of the remaining steps. The state changes only when the update revision exists.
        UserBundleRevision currentRev = getBundleRevision();

        // Without an input stream the module connector of a connect framework decides whether the update is a connect
        // bundle. An exception of the connector propagates as it is (60.4.1)
        VirtualFile connectRoot;
        try {
            connectRoot = input == null ? getBundleManager().connect(getLocation()) : null;
        } catch (BundleException | RuntimeException ex) {
            if (restart)
                getBundleManager().startBundle(this, restartOptions);
            throw ex;
        }

        try {
            // Create the update revision
            createUpdateRevision(input, connectRoot);

            // The bundle is INSTALLED with its new revision, a resolved bundle is unresolved
            boolean wasResolved = getState() != Bundle.INSTALLED;
            changeState(Bundle.INSTALLED, wasResolved ? BundleEvent.UNRESOLVED : 0);

            // Make the {@link BundleWiring} for the old {@link BundleRevision} uneffective
            currentRev.getWiringSupport().makeUneffective();

            // The connect content of a revision that is not in use is closed right away
            if (currentRev.getWiringSupport().getWiring(false) == null)
                currentRev.closeConnectContent();
        } catch (Exception ex) {
            boolean isbe = (ex instanceof BundleException);
            BundleException be = isbe ? (BundleException) ex : MESSAGES.cannotUpdateBundle(ex, this);
            if (restart)
                getBundleManager().startBundle(this, restartOptions);
            throw be;
        }

        FrameworkEvents eventsPlugin = getFrameworkState().getFrameworkEvents();
        eventsPlugin.fireBundleEvent(this, BundleEvent.UPDATED);

        // The replaced revision is removed now when nothing uses it, its content is closed; a revision in use is removal
        // pending until the next refresh
        BundleWiring oldWiring = currentRev.getWiringSupport().getWiring(false);
        boolean inUse = oldWiring != null && (oldWiring.isInUse() || getFrameworkState().getFrameworkModuleLoader().isModuleInUse(currentRev));
        if (inUse == false && currentRev != getBundleRevision()) {
            // Its module and class loader are removed with it, as a refresh does
            if (currentRev.isFragment() == false) {
                ModuleManager moduleManager = getFrameworkState().getModuleManager();
                moduleManager.removeModule(currentRev, moduleManager.getModuleIdentifier(currentRev));
            }
            getBundleManager().removeRevisionLifecycle(currentRev, 0);
        }

        updateLastModified();

        LOGGER.infoBundleUpdated(this);

        // If this bundle's state was originally ACTIVE or STARTING, the updated bundle is started like with Bundle.start,
        // through the bundle lifecycle: an integration may have to activate the deployment of the new revision
        return restart ? restartOptions : -1;
    }

    /**
     * Creates a new Bundle Revision when the bundle is updated. Multiple Bundle Revisions can co-exist at the same time.
     *
     * @param input The stream to create the bundle revision from or <tt>null</tt> if the new revision needs to be created from
     *              the same location as where the bundle was initially installed.
     * @throws Exception If the bundle cannot be read, or if the update attempt to change the BSN.
     */
    private UserBundleRevision createUpdateRevision(InputStream input, VirtualFile connectRoot) throws IOException, BundleException {

        String updateLocation = getOSGiMetaData().getHeader(Constants.BUNDLE_UPDATELOCATION);

        // If the specified InputStream is null, the Framework must create the InputStream from
        // which to read the updated bundle by interpreting, in an implementation dependent manner,
        // this bundle's Bundle-UpdateLocation Manifest header, if present, or this bundle's
        // original location.
        VirtualFile rootFile = connectRoot;

        if (rootFile == null && input == null) {
            if (updateLocation != null) {
                URL updateURL = new URL(updateLocation);
                rootFile = AbstractVFS.toVirtualFile(updateURL);
            } else {
                // The content at the original location, like at install. A location that is not a URL that can be
                // read gives the current content
                rootFile = getContentAtLocation();
                if (rootFile == null)
                    rootFile = getCopyOfContent(getDeployment().getRoot());
            }
        }

        if (rootFile == null && input != null)
            rootFile = AbstractVFS.toVirtualFile(input);

        StorageState storageState = createUpdateStorageState(getLocation(), rootFile);
        DeploymentProvider deploymentManager = getFrameworkState().getDeploymentProvider();
        Deployment dep = deploymentManager.createDeployment(storageState);
        OSGiMetaData metadata = deploymentManager.createOSGiMetaData(dep);
        dep.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);
        dep.putAttachment(IntegrationConstants.BUNDLE_KEY, this);
        dep.setBundleUpdate(true);
        dep.setAutoStart(false);

        // Check for symbolic name, version uniqueness
        String symbolicName = metadata.getBundleSymbolicName();
        Version bundleVersion = metadata.getBundleVersion();
        BundleManagerPlugin bundleManager = getBundleManager();
        bundleManager.checkUniqunessPolicy(this, symbolicName, bundleVersion, CollisionHook.UPDATING);

        BundleContext syscontext = getFrameworkState().getSystemBundle().getBundleContext();
        BundleLifecycle bundleLifecycle = getFrameworkState().getCoreServices().getBundleLifecycle();
        XBundleRevision brev = bundleLifecycle.createBundleRevision(syscontext, dep);
        return UserBundleRevision.assertBundleRevision(brev);
    }

    // The revisions do not share their content, the content of the replaced revision is closed when it is removed
    private static VirtualFile getCopyOfContent(VirtualFile rootFile) throws IOException {
        InputStream input = rootFile.openStream();
        try {
            return AbstractVFS.toVirtualFile(input);
        } finally {
            input.close();
        }
    }

    // The mandatory osgi.native requirement of the bundle is not satisfied by the system bundle
    private boolean isNativeCodeUnsatisfied() {
        List<Requirement> reqs = getBundleRevision().getRequirements(NativeNamespace.NATIVE_NAMESPACE);
        if (reqs.isEmpty())
            return false;
        XRequirement req = (XRequirement) reqs.get(0);
        if (req.isOptional())
            return false;
        XBundleRevision sysrev = getFrameworkState().getSystemBundle().getBundleRevision();
        for (Capability cap : sysrev.getCapabilities(NativeNamespace.NATIVE_NAMESPACE)) {
            if (req.matches(cap))
                return false;
        }
        return true;
    }

    private VirtualFile getContentAtLocation() {
        URL locationURL;
        try {
            locationURL = new URL(getLocation());
        } catch (MalformedURLException ex) {
            return null;
        }
        try {
            InputStream input = locationURL.openStream();
            try {
                return AbstractVFS.toVirtualFile(input);
            } finally {
                input.close();
            }
        } catch (IOException ex) {
            LOGGER.debugf(ex, "Cannot read the update from the location of: %s", this);
            return null;
        }
    }

    private StorageState createUpdateStorageState(String location, VirtualFile rootFile) throws BundleException {
        StorageState storageState;
        try {
            int startLevel = adapt(BundleStartLevel.class).getStartLevel();
            StorageManager storageManager = getFrameworkState().getStorageManager();
            storageState = storageManager.createStorageState(getBundleId(), location, startLevel, rootFile);
        } catch (IOException ex) {
            throw MESSAGES.cannotSetupStorage(ex, rootFile);
        }
        return storageState;
    }

    /**
     * This method gets called by when the bundle needs to be refreshed,
     * this means that all the old revisions are thrown out.
     */
    void refresh() throws BundleException {
        assertNotUninstalled();

        LOGGER.debugf("Refreshing bundle: %s", this);

        // Get the {@link BundleRefreshPolicy}
        CoreServices coreServices = getFrameworkState().getCoreServices();
        BundleLifecycle bundleLifecycle = coreServices.getBundleLifecycle();
        BundleRefreshPolicy refreshPolicy = bundleLifecycle.getBundleRefreshPolicy();

        // Only a resolved bundle becomes unresolved
        boolean wasResolved = getState() != Bundle.INSTALLED;

        // Remove the {@link Module} and the associated ClassLoader
        BundleManagerPlugin bundleManager = getBundleManager();
        bundleManager.unresolveBundle(this);

        // Initialize bundle refresh
        UserBundleRevision currentRev = getBundleRevision();
        refreshPolicy.startBundleRefresh(this);
        try {
            // Remove the revisions from the environment
            for (XBundleRevision brev : getAllBundleRevisions()) {

                if (currentRev != brev) {
                    bundleManager.removeRevisionLifecycle(brev, 0);
                }

                // Cleanup stale fragment revisions
                if (brev instanceof HostBundleRevision) {
                    HostBundleRevision hostRev = (HostBundleRevision) brev;
                    for (FragmentBundleRevision fragRev : hostRev.getAttachedFragments()) {
                        if (fragRev != fragRev.getBundle().getBundleRevision()) {
                            bundleManager.removeRevisionLifecycle(fragRev, 0);
                        }
                    }
                }
            }

            awaitLazyActivation.set(false);
            revisionIndex.set(1);

            FrameworkEvents eventsPlugin = getFrameworkState().getFrameworkEvents();
            if (wasResolved)
                eventsPlugin.fireBundleEvent(this, BundleEvent.UNRESOLVED);

            refreshPolicy.refreshCurrentRevision(currentRev);

            // The integration may have resolved the new revision already, when other modules need its module
            XBundleRevision newRev = getBundleRevision();
            // The refreshed bundle is not installed again: no INSTALLED event
            changeState(newRev != currentRev && newRev.getWiring() != null ? Bundle.RESOLVED : Bundle.INSTALLED, 0);
        } finally {
            refreshPolicy.endBundleRefresh(this);
        }

        LOGGER.infoBundleRefreshed(this);
    }

    void setHeadersBeforeUninstall(Dictionary<String, String> headers) {
        headersBeforeUninstall = headers;
    }

    @Override
    void uninstallInternal(int options) throws BundleException {

        int state = getState();
        if (state == Bundle.UNINSTALLED) {
            // Bundle.uninstall of a bundle that another thread uninstalled while this one waited for the lock
            if ((options & InternalConstants.UNINSTALL_INTERNAL) != 0)
                throw MESSAGES.illegalStateBundleAlreadyUninstalled(this);
            return;
        }

        LOGGER.debugf("Uninstalling bundle: %s", this);

        BundleManagerPlugin bundleManager = getBundleManager();
        Dictionary<String, String> headers = headersBeforeUninstall;
        getBundleRevision().setHeadersOnUninstall(headers != null ? headers : getHeaders(null));

        // #2 If the bundle's state is ACTIVE, STARTING or STOPPING, the bundle is stopped
        if (isFragment() == false) {
            if (state == Bundle.ACTIVE || state == Bundle.STARTING || state == Bundle.STOPPING) {
                try {
                    stopInternal(options);
                } catch (Exception ex) {
                    // If Bundle.stop throws an exception, a Framework event of type FrameworkEvent.ERROR is fired
                    bundleManager.fireFrameworkError(this, "stopping bundle: " + this, ex);
                }
            }
        }

        // Check if the bundle has still active wires
        boolean activeWires = hasActiveWiresWhileUninstalling();
        if (activeWires == false)
            bundleManager.unresolveBundle(this);
        else
            LOGGER.debugf("Has active wires: %s", this);

        // Make the current {@link BundleWiring} uneffective and fire the UNRESOLVED event of a resolved bundle
        boolean wasResolved = getState() != Bundle.INSTALLED;
        getBundleRevision().getWiringSupport().makeUneffective();
        FrameworkEvents eventsPlugin = getFrameworkState().getFrameworkEvents();
        if (wasResolved)
            eventsPlugin.fireBundleEvent(this, BundleEvent.UNRESOLVED);

        // #3 This bundle's state is set to UNINSTALLED
        changeState(Bundle.UNINSTALLED, 0);

        // #5 This bundle and any persistent storage area provided for this bundle by the Framework are removed
        // The content of a revision that is still in use stays open until the revision is removed (removal pending)
        if ((options & Bundle.STOP_TRANSIENT) == 0) {
            StorageManager storagePlugin = getFrameworkState().getStorageManager();
            if (activeWires && storagePlugin instanceof StorageManagerImpl) {
                ((StorageManagerImpl) storagePlugin).deleteStorageState(getStorageState(), false);
            } else {
                storagePlugin.deleteStorageState(getStorageState());
            }
        }

        // Remove the bundle from the {@link Environment}
        if (activeWires == false) {
            bundleManager.removeBundle(this, options);
        }

        // #4 A bundle event of type BundleEvent.UNINSTALLED is fired
        fireBundleEvent(BundleEvent.UNINSTALLED);

        LOGGER.infoBundleUninstalled(this);
    }

    /**
     * Remove other uninstalled bundles that now also are not in use any more.
     *
     * This is done when the uninstall released its locks: the bundle lifecycle may remove a bundle with an operation
     * that needs them in another thread, like the undeploy of its deployment.
     */
    void removeUnusedUninstalledBundles(int options) {
        BundleManagerPlugin bundleManager = getBundleManager();
        Set<XBundle> uninstalled = bundleManager.getBundles(Bundle.UNINSTALLED);
        for (XBundle auxState : uninstalled) {
            if (auxState != this) {
                boolean bundleInUse = false;
                XBundleRevision brev = auxState.getBundleRevision();
                XWiringSupport wiringSupport = brev.getWiringSupport();
                if (!wiringSupport.isEffective()) {
                    BundleWiring bwiring = (BundleWiring) wiringSupport.getWiring(false);
                    // Only this bundle stays, another uninstalled bundle may not be in use any more
                    if (bwiring != null && bwiring.isInUse()) {
                        bundleInUse = true;
                    }
                }
                if (bundleInUse == false) {
                    UserBundleState auxUser = UserBundleState.assertBundleState(auxState);
                    bundleManager.unresolveBundle(auxUser);
                    // The revisions are removed by the bundle lifecycle, like those of a bundle that is not in use
                    bundleManager.removeBundle(auxUser, options | InternalConstants.UNINSTALL_INTERNAL);
                }
            }
        }
    }

    @Override
    public boolean isFragment() {
        return getBundleRevision().isFragment();
    }

    @Override
    UserBundleContext createContextInternal() {
        return new UserBundleContext(this);
    }

    // The event of a start of this thread that is fired when the locks of the start are released: a synchronous
    // listener of the event can stop the bundle in another thread (Core 10.1.31)
    private static final ThreadLocal<Object[]> deferredStartEvent = new ThreadLocal<Object[]>();

    private void deferStartEvent(int type) {
        deferredStartEvent.set(new Object[] { this, type });
    }

    /**
     * The event type of the start of the bundle by this thread that is still to be fired, or null
     */
    static Integer takeDeferredStartEvent(XBundle bundle) {
        Object[] event = deferredStartEvent.get();
        if (event == null || event[0] != bundle)
            return null;
        deferredStartEvent.remove();
        return (Integer) event[1];
    }

    @Override
    void startInternal(int options) throws BundleException {

        // #1 If this bundle is in the process of being activated or deactivated
        // then this method must wait for activation or deactivation to complete before continuing.
        // If this does not occur in a reasonable time, a BundleException is thrown

        // We got the permit, now start
        try {
            alreadyStarting.set(true);
            startInternalNow(options);
        } finally {
            alreadyStarting.set(false);
        }
    }

    private void startInternalNow(int options) throws BundleException {

        // #2 Set this bundle's autostart setting, also for a bundle that is ACTIVE already
        persistAutoStartSettings(options);

        // #3 If this bundle's state is ACTIVE then this method returns immediately.
        if (getState() == ACTIVE)
            return;

        LOGGER.debugf("Starting bundle: %s", this);

        // If the Framework's current start level is less than this bundle's start level
        if (startLevelValidForStart() == false) {
            StartLevelManager plugin = getFrameworkState().getStartLevelManager();
            int frameworkState = getBundleManager().getSystemBundle().getState();
            Level level = (plugin.isFrameworkStartLevelChanging() || frameworkState != Bundle.ACTIVE) ? Level.DEBUG : Level.INFO;
            LOGGER.log(level, MESSAGES.bundleStartLevelNotValid(getBundleStartLevel(), plugin.getFrameworkStartLevel(), this));
            return;
        }

        // #4 If this bundle's state is not RESOLVED, an attempt is made to resolve this bundle.
        // If the Framework cannot resolve this bundle, a BundleException is thrown.
        if (ensureResolved(true) == false) {
            Exception resex = getLastResolverException();
            int type = (resex instanceof ResolverHookException ? BundleException.REJECTED_BY_HOOK : BundleException.RESOLVE_ERROR);
            // No Bundle-NativeCode clause matches the environment (3.10.2)
            if (type == BundleException.RESOLVE_ERROR && isNativeCodeUnsatisfied())
                type = BundleException.NATIVECODE_ERROR;
            throw new BundleException(MESSAGES.cannotResolveBundle(this), type, resex);
        }

        // The BundleContext object is valid during STARTING, STOPPING, and ACTIVE
        if (getBundleContextInternal() == null)
            createBundleContext();

        // #5 If the START_ACTIVATION_POLICY option is set and this bundle's declared activation policy is lazy
        boolean useActivationPolicy = (options & START_ACTIVATION_POLICY) != 0;
        // A bundle from which a class was loaded while it waited for its start level is activated right away
        if (isActivationLazy() && useActivationPolicy && classLoadedBeforeLazyStart.getAndSet(false) == false) {

            // #5.1 If this bundle's state is STARTING then this method returns immediately.
            if (getState() == STARTING)
                return;

            // #5.2 This bundle's state is set to STARTING.
            // #5.3 A bundle event of type BundleEvent.LAZY_ACTIVATION is fired
            // The bundle now awaits its activation by the first class load (4.4.6.2)
            awaitLazyActivation.set(true);
            changeState(STARTING, 0);
            deferStartEvent(BundleEvent.LAZY_ACTIVATION);
            return;
        }
        awaitLazyActivation.set(false);

        // #6 This bundle's state is set to STARTING.
        // #7 A bundle event of type BundleEvent.STARTING is fired.
        try {
            changeState(Bundle.STARTING);
        } catch (LifecycleInterceptorException ex) {
            throw MESSAGES.cannotTransitionToStarting(ex, this);
        }

        // #8 The BundleActivator.start(BundleContext) method of this bundle is called
        String className = getOSGiMetaData().getBundleActivator();
        if (className != null) {
            try {
                // Every start gets a new activator object (4.4.6), from the factory of an integration if there is one
                Supplier<BundleActivator> activatorFactory = getDeployment().getAttachment(IntegrationConstants.BUNDLE_ACTIVATOR_FACTORY_KEY);
                bundleActivator = activatorFactory != null ? activatorFactory.get() : getDeployment().getAttachment(IntegrationConstants.BUNDLE_ACTIVATOR_KEY);
                if (bundleActivator == null) {
                    Object result = loadClass(className).newInstance();
                    if (result instanceof BundleActivator) {
                        bundleActivator = (BundleActivator) result;
                    } else {
                        throw MESSAGES.invalidBundleActivator(className);
                    }
                }
                if (bundleActivator != null) {
                    bundleActivator.start(getBundleContext());
                }
            }

            // If the BundleActivator is invalid or throws an exception then
            catch (Throwable th) {
                // #8.1 This bundle's state is set to STOPPING
                // #8.2 A bundle event of type BundleEvent.STOPPING is fired
                changeState(Bundle.STOPPING);

                // #8.3 Any services registered by this bundle must be unregistered.
                // #8.4 Any services used by this bundle must be released.
                // #8.5 Any listeners registered by this bundle must be removed.
                removeServicesAndListeners();

                // The BundleContext object is valid during STARTING, STOPPING, and ACTIVE
                destroyBundleContext();

                // #8.6 This bundle's state is set to RESOLVED
                // #8.7 A bundle event of type BundleEvent.STOPPED is fired
                changeState(Bundle.RESOLVED, BundleEvent.STOPPED);

                // #8.8 A BundleException is then thrown
                if (th instanceof BundleException)
                    throw (BundleException) th;

                throw new BundleException(MESSAGES.cannotStartBundle(this), BundleException.ACTIVATOR_ERROR, th);
            }
        }

        // #9 If this bundle's state is UNINSTALLED, because this bundle was uninstalled while
        // the BundleActivator.start method was running, a BundleException is thrown
        if (getState() == Bundle.UNINSTALLED)
            throw MESSAGES.uninstalledDuringActivatorStart(this);

        // #10 This bundle's state is set to ACTIVE.
        // #11 A bundle event of type BundleEvent.STARTED is fired, after the state change lock is released
        changeState(Bundle.ACTIVE, 0);
        deferStartEvent(BundleEvent.STARTED);

        LOGGER.infoBundleStarted(this);
    }

    @Override
    void stopInternal(int options) throws BundleException {

        // #2 If this bundle is in the process of being activated or deactivated
        // then this method must wait for activation or deactivation to complete before continuing.
        // If this does not occur in a reasonable time, a BundleException is thrown to indicate this bundle was unable to be
        // stopped

        // We got the permit, now stop
        stopInternalNow(options);
    }

    private void stopInternalNow(int options) throws BundleException {

        LOGGER.debugf("Stopping bundle: %s", this);

        int priorState = getState();

        // A stopped bundle does not await its lazy activation any more
        awaitLazyActivation.set(false);

        // #3 If the STOP_TRANSIENT option is not set then then set this bundle's persistent autostart setting to Stopped.
        // When the Framework is restarted and this bundle's autostart setting is Stopped, this bundle must not be
        // automatically started.
        if ((options & Bundle.STOP_TRANSIENT) == 0) {
            setPersistentlyStarted(false);
            setBundleActivationPolicyUsed(false);
            classLoadedBeforeLazyStart.set(false);
        }

        // #4 If this bundle's state is not STARTING or ACTIVE then this method returns immediately
        if (priorState != Bundle.STARTING && priorState != Bundle.ACTIVE)
            return;

        // #5 This bundle's state is set to STOPPING
        // #6 A bundle event of type BundleEvent.STOPPING is fired
        changeState(Bundle.STOPPING);

        // #7 If this bundle's state was ACTIVE prior to setting the state to STOPPING,
        // the BundleActivator.stop(org.osgi.framework.BundleContext) method of this bundle's BundleActivator,
        // if one is specified, is called. If that method throws an exception, this method must continue to stop
        // this bundle and a BundleException must be thrown after completion of the remaining steps.
        Throwable rethrow = null;
        if (priorState == Bundle.ACTIVE) {
            if (bundleActivator != null) {
                try {
                    bundleActivator.stop(getBundleContext());
                } catch (Throwable t) {
                    rethrow = t;
                }
            }
        }

        // #8 Any services registered by this bundle must be unregistered.
        // #9 Any services used by this bundle must be released.
        // #10 Any listeners registered by this bundle must be removed.
        removeServicesAndListeners();

        // #11 If this bundle's state is UNINSTALLED, because this bundle was uninstalled while the
        // BundleActivator.stop method was running, a BundleException must be thrown
        if (getState() == Bundle.UNINSTALLED)
            throw MESSAGES.uninstalledDuringActivatorStop(this);

        // The BundleContext object is valid during STARTING, STOPPING, and ACTIVE
        destroyBundleContext();

        // #12 This bundle's state is set to RESOLVED
        // #13 A bundle event of type BundleEvent.STOPPED is fired
        changeState(Bundle.RESOLVED, BundleEvent.STOPPED);

        if (rethrow != null) {
            throw MESSAGES.cannotStopBundle(rethrow, this);
        }

        LOGGER.infoBundleStopped(this);
    }

    Set<UserBundleState> getDependentBundles() {
        Set<UserBundleState> result = new HashSet<UserBundleState>();
        if (isResolved() == true) {
            BundleWiring wiring = getBundleRevision().getWiring();
            List<Wire> wires = wiring.getRequiredResourceWires(null);
            for (Wire wire : wires) {
                BundleRevision brev = (BundleRevision) wire.getProvider();
                Bundle bundle = brev.getBundle();
                if (bundle instanceof UserBundleState)
                    result.add((UserBundleState) bundle);
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("deprecation")
    void assertStartConditions(int options) throws BundleException {
        super.assertStartConditions(options);

        // The service platform may run this bundle if any of the execution environments named in the
        // Bundle-RequiredExecutionEnvironment header matches one of the execution environments it implements.
        List<String> requiredEnvs = getOSGiMetaData().getRequiredExecutionEnvironment();
        if (requiredEnvs.isEmpty() == false) {
            boolean foundSupportedEnv = false;
            String frameworkEnvProp = (String) getBundleManager().getProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT);
            List<String> availableEnvs = Arrays.asList(frameworkEnvProp.split("[,\\s]+"));
            for (String aux : requiredEnvs) {
                if (availableEnvs.contains(aux) || isSystemExecutionEnvironment(aux)) {
                    foundSupportedEnv = true;
                    break;
                }
            }
            if (foundSupportedEnv == false)
                throw MESSAGES.unsupportedExecutionEnvironment(requiredEnvs, availableEnvs);
        }

        // If the Framework's current start level is less than this bundle's start level
        if (startLevelValidForStart() == false) {
            // If the START_TRANSIENT option is set, then a BundleException is thrown
            // indicating this bundle cannot be started due to the Framework's current start level
            if ((options & START_TRANSIENT) != 0)
                throw new BundleException(MESSAGES.cannotStartBundleDueToStartLevel(), BundleException.START_TRANSIENT_ERROR);

            // Set this bundle's autostart setting
            persistAutoStartSettings(options);
        }
    }

    private void persistAutoStartSettings(int options) {
        // The Framework must set this bundle's persistent autostart setting to
        // Started with declared activation if the START_ACTIVATION_POLICY option is set or
        // Started with eager activation if not set.
        if ((options & START_TRANSIENT) == 0) {
            setPersistentlyStarted(true);
            boolean activationPolicyUsed = (options & START_ACTIVATION_POLICY) != 0;
            setBundleActivationPolicyUsed(activationPolicyUsed);
        }
    }

    private int getBundleStartLevel() {
        StartLevelManager startLevelPlugin = getFrameworkState().getStartLevelManager();
        return startLevelPlugin.getBundleStartLevel(this);
    }

    void setPersistentlyStarted(boolean started) {
        StartLevelManager startLevelPlugin = getFrameworkState().getStartLevelManager();
        startLevelPlugin.setBundlePersistentlyStarted(this, started);
    }

    private boolean startLevelValidForStart() {
        // A stopping framework is on its way to start level 0: the autostart setting is recorded, the bundle is not
        // activated
        if (getBundleManager().isFrameworkStopping())
            return false;
        StartLevelManager startLevelPlugin = getFrameworkState().getStartLevelManager();
        return startLevelPlugin.getBundleStartLevel(this) <= startLevelPlugin.getFrameworkStartLevel();
    }

    /**
     * True if the system bundle provides the given execution environment as an osgi.ee capability
     */
    private boolean isSystemExecutionEnvironment(String execEnv) {
        StringBuffer namepart = new StringBuffer();
        StringBuffer versionpart = new StringBuffer();
        OSGiMetaDataBuilder.convertExecutionEnvironmentHeader(execEnv, namepart, versionpart);
        XBundleRevision sysrev = getBundleManager().getSystemBundle().getBundleRevision();
        for (Capability cap : sysrev.getCapabilities(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) {
            Map<String, Object> atts = cap.getAttributes();
            if (namepart.toString().equals(atts.get(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) == false)
                continue;
            if (versionpart.length() == 0)
                return true;
            Version version = Version.parseVersion(versionpart.toString());
            Object capVersion = atts.get(ExecutionEnvironmentNamespace.CAPABILITY_VERSION_ATTRIBUTE);
            if (version.equals(capVersion) || (capVersion instanceof Collection && ((Collection<?>) capVersion).contains(version)))
                return true;
        }
        return false;
    }


    private void removeServicesAndListeners() {
        // Any services registered by this bundle must be unregistered.
        // Any services used by this bundle must be released.
        // Any listeners registered by this bundle must be removed
        ServiceManager serviceManager = getFrameworkState().getServiceManagerPlugin();
        FrameworkEvents eventsPlugin = getFrameworkState().getFrameworkEvents();
        getBundleServices().release(this, serviceManager, eventsPlugin);
    }

    // Every revision of the bundle counts, after an update the old revision may be in use while the current one is not
    private boolean hasActiveWiresWhileUninstalling() {
        for (XBundleRevision brev : getAllBundleRevisions()) {
            BundleWiring wiring = brev.getWiringSupport().getWiring(false);
            if (wiring instanceof AbstractBundleWiring && ((AbstractBundleWiring) wiring).isInUseForUninstall())
                return true;
        }
        return false;
    }
}
