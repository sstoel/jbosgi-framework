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
import static org.jboss.osgi.framework.internal.InternalConstants.REVISION_IDENTIFIER_KEY;
import static org.jboss.osgi.framework.spi.IntegrationConstants.STORAGE_STATE_KEY;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.deployment.deployer.Deployment;
import org.jboss.osgi.framework.Constants;
import org.jboss.osgi.framework.Services;
import org.jboss.osgi.framework.spi.AbstractIntegrationService;
import org.jboss.osgi.framework.spi.BundleLifecycle;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.DeploymentProvider;
import org.jboss.osgi.framework.spi.FrameworkBuilder;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.FrameworkStartLevelSupport;
import org.jboss.osgi.framework.spi.FrameworkWiringLock;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.LockException;
import org.jboss.osgi.framework.spi.LockManager;
import org.jboss.osgi.framework.spi.LockManager.LockContext;
import org.jboss.osgi.framework.spi.LockManager.LockableItem;
import org.jboss.osgi.framework.spi.LockManager.Method;
import org.jboss.osgi.framework.spi.ModuleManager;
import org.jboss.osgi.framework.spi.StorageManager;
import org.jboss.osgi.framework.spi.StorageState;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.ParameterizedAttribute;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.resolver.XResolveContext;
import org.jboss.osgi.resolver.XResolver;
import org.jboss.osgi.resolver.XResource;
import org.jboss.osgi.resolver.spi.RemoveOnlyCollection;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.hooks.bundle.CollisionHook;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.resolver.ResolutionException;

/**
 * The BundleManager is the central managing entity for OSGi bundles.
 *
 * @author thomas.diesler@jboss.com
 * @author David Bosschaert
 * @since 29-Jun-2010
 */
final class BundleManagerPlugin extends AbstractIntegrationService<BundleManager> implements BundleManager {

    // The framework execution environment
    private static String OSGi_FRAMEWORK_EXECUTIONENVIRONMENT;
    // The framework language
    private static String OSGi_FRAMEWORK_LANGUAGE = Locale.getDefault().getLanguage();
    // The os name
    private static String OSGi_FRAMEWORK_OS_NAME;
    // The os version
    private static String OSGi_FRAMEWORK_OS_VERSION;
    // The os version
    private static String OSGi_FRAMEWORK_PROCESSOR;
    // The framework vendor
    private static String OSGi_FRAMEWORK_VENDOR = "jboss.org";
    // The framework version. This is the version of the org.osgi.framework package in R8
    private static String OSGi_FRAMEWORK_VERSION = "1.10";

    enum UniquenessPolicy {
        // Specifies the framework will allow multiple bundles to be installed having the same symbolic name and version.
        multiple,
        // Specifies the framework will only allow a single bundle to be installed for a given symbolic name and version.
        // It will be an error to install a bundle or update a bundle to have the same symbolic name and version as another installed bundle.
        single,
        // Specifies the framework must consult the bundle collision hook services to determine if it will be an error to install
        // a bundle or update a bundle to have the same symbolic name and version as another installed bundle.
        // If no bundle collision hook services are registered, then it will be an error to install a bundle or update a bundle
        // to have the same symbolic name and version as another installed bundle.
        managed
    }

    private static String implementationVersion;
    static {
        implementationVersion = BundleManagerPlugin.class.getPackage().getImplementationVersion();
    }

    private final InjectedValue<XEnvironment> injectedEnvironment = new InjectedValue<XEnvironment>();
    private final InjectedValue<LockManager> injectedLockManager = new InjectedValue<LockManager>();

    final InjectedValue<FrameworkState> injectedFramework = new InjectedValue<FrameworkState>();
    final InjectedValue<SystemBundleState> injectedSystemBundle = new InjectedValue<SystemBundleState>();
    final InjectedValue<Boolean> injectedFrameworkActive = new InjectedValue<Boolean>();

    private final FrameworkBuilder frameworkBuilder;
    private final ShutdownContainer shutdownContainer;
    private final Set<ExecutorService> executorServices = new HashSet<ExecutorService>();
    private final Map<String, InstallLock> installLocks = new HashMap<String, InstallLock>();
    private Thread stopThread;
    private final Map<String, Object> properties = new HashMap<String, Object>();
    private final AtomicInteger managerState = new AtomicInteger(Bundle.INSTALLED);
    private final AtomicBoolean managerStopped = new AtomicBoolean();
    private final ServiceContainer serviceContainer;
    private final UniquenessPolicy uniquenessPolicy;
    private Framework framework;
    private SystemBundleState cachedSystemBundle;
    private ServiceTarget serviceTarget;
    private int stoppedEvent;
    private final FrameworkExtensions frameworkExtensions = new FrameworkExtensions(this);
    private BundleActivator connectActivator;
    private final ConnectVirtualFile.OpenContents connectContents = new ConnectVirtualFile.OpenContents();
    // The id of this framework instance, unique in the JVM also when the framework is loaded by several class loaders,
    // part of the host of its bundle URLs
    private final String instanceId = Long.toHexString(new SecureRandom().nextLong() & Long.MAX_VALUE);

    @SuppressWarnings("deprecation")
    BundleManagerPlugin(ServiceContainer serviceContainer, FrameworkBuilder frameworkBuilder) {
        super(Services.BUNDLE_MANAGER);
        this.serviceContainer = serviceContainer;
        this.frameworkBuilder = frameworkBuilder;
        this.stoppedEvent = FrameworkEvent.STOPPED;

        // The properties on the BundleManager are mutable as long the framework is not created
        // Plugins may modify these properties in their respective constructor
        properties.putAll(frameworkBuilder.getProperties());

        // Init default framework properties
        if (getProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT) == null)
            setProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT, OSGi_FRAMEWORK_EXECUTIONENVIRONMENT);
        if (getProperty(Constants.FRAMEWORK_LANGUAGE) == null)
            setProperty(Constants.FRAMEWORK_LANGUAGE, OSGi_FRAMEWORK_LANGUAGE);
        if (getProperty(Constants.FRAMEWORK_OS_NAME) == null)
            setProperty(Constants.FRAMEWORK_OS_NAME, OSGi_FRAMEWORK_OS_NAME);
        if (getProperty(Constants.FRAMEWORK_OS_VERSION) == null)
            setProperty(Constants.FRAMEWORK_OS_VERSION, OSGi_FRAMEWORK_OS_VERSION);
        if (getProperty(Constants.FRAMEWORK_PROCESSOR) == null)
            setProperty(Constants.FRAMEWORK_PROCESSOR, OSGi_FRAMEWORK_PROCESSOR);
        if (getProperty(Constants.FRAMEWORK_BSNVERSION) == null)
            setProperty(Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MANAGED);
        if (getProperty(Constants.FRAMEWORK_STORAGE) == null)
            setProperty(Constants.FRAMEWORK_STORAGE, StorageManagerImpl.getDefaultStorageArea());
        if (getProperty(Constants.FRAMEWORK_SYSTEMPACKAGES) == null)
            setProperty(Constants.FRAMEWORK_SYSTEMPACKAGES, String.join(",", SystemPathsImpl.getDefaultSystemPackages()));

        // The fixed properties are set by the framework, whatever the configuration contains. A new framework
        // instance gets a new UUID (4.2.2)
        setProperty(Constants.FRAMEWORK_VENDOR, OSGi_FRAMEWORK_VENDOR);
        setProperty(Constants.FRAMEWORK_VERSION, OSGi_FRAMEWORK_VERSION);
        setProperty(Constants.FRAMEWORK_UUID, UUID.randomUUID().toString());
        setProperty(Constants.SUPPORTS_FRAMEWORK_FRAGMENT, "true");
        setProperty(Constants.SUPPORTS_FRAMEWORK_REQUIREBUNDLE, "true");

        // Get and cache the BSNVERSION
        uniquenessPolicy = UniquenessPolicy.valueOf((String) getProperty(Constants.FRAMEWORK_BSNVERSION));

        boolean allowContainerShutdown = frameworkBuilder.getServiceContainer() == null;
        shutdownContainer = new ShutdownContainer(serviceContainer, allowContainerShutdown);
    }

    @Override
    protected void addServiceDependencies(ServiceBuilder<BundleManager> builder) {
        builder.addDependency(Services.ENVIRONMENT, XEnvironment.class, injectedEnvironment);
        builder.addDependency(IntegrationServices.LOCK_MANAGER_PLUGIN, LockManager.class, injectedLockManager);
        builder.setInitialMode(Mode.ON_DEMAND);
    }

    @Override
    public void start(StartContext context) throws StartException {
        super.start(context);
        LOGGER.infoFrameworkImplementation(implementationVersion);
        serviceTarget = context.getChildTarget();
        LOGGER.debugf("Framework properties");
        for (Entry<String, Object> entry : properties.entrySet()) {
            LOGGER.debugf(" %s = %s", entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void stop(StopContext context) {
        XEnvironment env = injectedEnvironment.getValue();
        Iterator<XResource> itres = env.getResources(null);
        while (itres.hasNext()) {
            XResource res = itres.next();
            if (res instanceof UserBundleRevision) {
                UserBundleRevision userRev = (UserBundleRevision) res;
                userRev.close();
            }
        }
    }

    @Override
    protected BundleManager createServiceValue(StartContext startContext) {
        return this;
    }

    FrameworkBuilder getFrameworkBuilder() {
        return frameworkBuilder;
    }

    @Override
    public ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    @Override
    public ServiceTarget getServiceTarget() {
        return serviceTarget;
    }

    @Override
    public SystemBundleState getSystemBundle() {
        return injectedSystemBundle.getOptionalValue();
    }

    BundleContext getSystemContext() {
        SystemBundleState sysbundle = injectedSystemBundle.getOptionalValue();
        return sysbundle != null ? sysbundle.getBundleContext() : null;
    }

    FrameworkStartLevel getFrameworkStartLevel() {
        return getFrameworkState().getFrameworkStartLevel();
    }

    Framework getFramework() {
        return framework;
    }

    void setFramework(Framework framework) {
        this.framework = framework;
    }

    UniquenessPolicy getUniquenessPolicy() {
        return uniquenessPolicy;
    }

    /**
     * Required by spec:
     *
     * {@link Framework} The Framework object from the launching API.
     * {@link FrameworkStartLevel} The Framework Start Level.
     * {@link FrameworkWiring} The Framework Wiring.
     *
     * Proprietary extensions:
     *
     * {@link ServiceContainer} The Bundle metadata.
     * {@link XEnvironment} The Bundle's storage state.
     */
    @SuppressWarnings("unchecked")
    <T> T adapt(Class<T> type) {
        if (!hasStopped()) {
            if (type.isAssignableFrom(FrameworkStartLevel.class)) {
                return (T) getFrameworkStartLevel();
            } else if (type.isAssignableFrom(Framework.class)) {
                return (T) getFramework();
            } else if (type.isAssignableFrom(FrameworkWiring.class)) {
                return (T) getFrameworkState().getFrameworkWiring();
            } else if (type.isAssignableFrom(ServiceContainer.class)) {
                return (T) getServiceContainer();
            } else if (type.isAssignableFrom(XEnvironment.class)) {
                return (T) getFrameworkState().getEnvironment();
            }
        }
        return null;
    }

    /**
     * True if the framework has reached the {@link Services#FRAMEWORK_CREATE} state
     */
    boolean isFrameworkCreated() {
        return getFrameworkState() != null;
    }

    void assertFrameworkCreated() {
        if (isFrameworkCreated() == false)
            throw MESSAGES.illegalStateFrameworkNotActive();
    }

    @Override
    public boolean isFrameworkActive() {
        return Boolean.TRUE.equals(injectedFrameworkActive.getOptionalValue());
    }

    /**
     * The id of this framework instance in the JVM. The bundle URLs of the framework carry it in their host, the
     * 'bundle' URL handler of the JVM routes them to their framework
     */
    String getInstanceId() {
        return instanceId;
    }

    FrameworkState getFrameworkState() {
        return injectedFramework.getOptionalValue();
    }

    static Version getFrameworkVersion() {
        Version version;
        String versionSpec = BundleManagerPlugin.class.getPackage().getImplementationVersion();
        // Without the Implementation-Version, e.g. in a jar that combines several jars, the Maven properties of the jar
        if (versionSpec == null)
            versionSpec = getMavenVersion();
        if (versionSpec == null)
            return Version.emptyVersion;
        try {
            version = Version.parseVersion(versionSpec);
        } catch (IllegalArgumentException ex) {
            if (versionSpec.endsWith("-SNAPSHOT")) {
                versionSpec = versionSpec.substring(0, versionSpec.length() - 9);
            }
            version = Version.parseVersion(versionSpec);
        }
        return version;
    }

    private static String getMavenVersion() {
        InputStream input = BundleManagerPlugin.class.getResourceAsStream("/META-INF/maven/org.jboss.osgi.framework/jbosgi-framework-core/pom.properties");
        if (input == null)
            return null;
        try {
            try {
                Properties props = new Properties();
                props.load(input);
                return props.getProperty("version");
            } finally {
                input.close();
            }
        } catch (IOException ex) {
            return null;
        }
    }

    int getManagerState() {
        return managerState.get();
    }

    void setManagerState(int state) {
        managerState.set(state);
    }

    /**
     * Whether the framework was launched through its {@link Framework} object, which can be stopped for the launcher to
     * launch it again. An integration that creates the framework services itself (like WildFly) does not use it.
     */
    boolean isLaunchedByFramework() {
        int state = getManagerState();
        return state == Bundle.STARTING || state == Bundle.ACTIVE;
    }

    /**
     * The major version of the Java platform that the framework runs on.
     * This is taken from the java.specification.version framework property.
     */
    int getJavaVersion() {
        return MultiReleaseSupport.parseJavaVersion((String) getProperty(MultiReleaseSupport.JAVA_SPECIFICATION_VERSION));
    }

    @Override
    public Object getProperty(String key) {
        Object value = properties.get(key);
        if (value == null)
            value = SecurityActions.getSystemProperty(key, null);
        return value;
    }

    /**
     * Get a property of the framework configuration, without the fallback to the system properties.
     */
    Object getConfiguredProperty(String key) {
        return properties.get(key);
    }

    /**
     * The launching properties: the configuration properties plus the defaults and the fixed properties of the
     * framework (4.2.2), without the system properties that only BundleContext.getProperty falls back to
     */
    Map<String, Object> getLaunchingProperties() {
        Map<String, Object> result = new HashMap<String, Object>(properties);
        // A default that was not set because the system property defines it
        for (String key : new String[] { Constants.FRAMEWORK_EXECUTIONENVIRONMENT, Constants.FRAMEWORK_LANGUAGE, Constants.FRAMEWORK_OS_NAME,
                Constants.FRAMEWORK_OS_VERSION, Constants.FRAMEWORK_PROCESSOR }) {
            if (result.containsKey(key) == false) {
                Object value = getProperty(key);
                if (value != null)
                    result.put(key, value);
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> getProperties() {
        Map<String, Object> m = new HashMap<String, Object>();
        Properties sysProperties = System.getProperties();
        synchronized (sysProperties) {
            for (Map.Entry<Object, Object> entry : sysProperties.entrySet()) {
                m.put(entry.getKey().toString(), entry.getValue());
            }
        }
        m.putAll(properties);
        return m;
    }

    void setProperty(String key, Object value) {
        if (isFrameworkCreated())
            throw MESSAGES.illegalStateCannotAddProperty();

        properties.put(key, value);
    }

    @Override
    public Set<XBundle> getBundles() {
        Set<XBundle> result = new HashSet<XBundle>();
        XEnvironment env = injectedEnvironment.getValue();
        Iterator<XResource> itres = env.getResources(null);
        while (itres.hasNext()) {
            XResource res = itres.next();
            if (res instanceof XBundleRevision) {
                XBundle bundle = ((XBundleRevision) res).getBundle();
                if (bundle.getState() != Bundle.UNINSTALLED)
                    result.add(bundle);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public Set<XBundle> getBundles(Integer states) {
        Set<XBundle> result = new HashSet<XBundle>();
        XEnvironment env = injectedEnvironment.getValue();
        Iterator<XResource> itres = env.getResources(null);
        while (itres.hasNext()) {
            XResource res = itres.next();
            if (res instanceof XBundleRevision) {
                XBundle bundle = ((XBundleRevision) res).getBundle();
                if (states == null || (bundle.getState() & states.intValue()) != 0)
                    result.add(bundle);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public XBundle getBundleById(long bundleId) {
        XEnvironment env = injectedEnvironment.getValue();
        XResource res = env.getResourceById(bundleId);
        if (res instanceof XBundleRevision) {
            return ((XBundleRevision) res).getBundle();
        }
        return null;
    }

    @Override
    public XBundle getBundleByLocation(String location) {
        assert location != null : "Null location";
        // The location of the system bundle, also by the location that it had before (JBOSGI_FRAMEWORK_LOCATION)
        if (Constants.SYSTEM_BUNDLE_LOCATION.equals(location) || Constants.JBOSGI_FRAMEWORK_LOCATION.equals(location)) {
            return getSystemBundle();
        }
        for (XBundle aux : getBundles()) {
            String auxLocation = aux.getLocation();
            if (location.equals(auxLocation)) {
                return aux;
            }
        }
        return null;
    }

    @Override
    public Set<XBundle> getBundles(String symbolicName, VersionRange versionRange) {
        Set<XBundle> resultSet = new HashSet<XBundle>();
        if (Constants.SYSTEM_BUNDLE_SYMBOLICNAME.equals(symbolicName) && versionRange == null) {
            resultSet.add(getSystemBundle());
        } else {
            for (XBundle aux : getBundles(null)) {
                if (symbolicName == null || symbolicName.equals(aux.getSymbolicName())) {
                    if (versionRange == null || versionRange.includes(aux.getVersion())) {
                        resultSet.add(aux);
                    }
                }
            }
        }
        return Collections.unmodifiableSet(resultSet);
    }

    XBundleRevision createBundleRevisionLifecycle(BundleContext context, Deployment dep) throws BundleException {
        XBundleRevision brev = getBundleLifecycle().createBundleRevision(context, dep);
        if (brev == null)
            throw MESSAGES.illegalStateCannotObtainBundleRevision(dep);
        return brev;
    }

    /**
     * The lock that serializes the installs of a location, released with {@link #releaseInstallLock(String, Object)}
     */
    Object acquireInstallLock(String location) {
        synchronized (installLocks) {
            InstallLock lock = installLocks.get(location);
            if (lock == null) {
                lock = new InstallLock();
                installLocks.put(location, lock);
            }
            lock.users++;
            return lock;
        }
    }

    void releaseInstallLock(String location, Object lock) {
        synchronized (installLocks) {
            if (--((InstallLock) lock).users == 0)
                installLocks.remove(location);
        }
    }

    private static final class InstallLock {
        int users;
    }

    @Override
    public XBundleRevision installBundleRevision(BundleContext context, Deployment deployment, ServiceTarget serviceTarget) throws BundleException {
        if (context == null)
            throw MESSAGES.illegalArgumentNull("context");
        if (deployment == null)
            throw MESSAGES.illegalArgumentNull("deployment");

        if (serviceTarget == null)
            serviceTarget = getServiceTarget();

        // The installs of a symbolic name are serialized: the uniqueness check and the creation of the bundle are one
        // step, two concurrent installs of the same name and version cannot both pass the check
        String symbolicName = deployment.getSymbolicName();
        String nameKey = "bsn:" + symbolicName;
        Object nameLock = symbolicName != null && isUpdateOrRefresh(deployment) == false ? acquireInstallLock(nameKey) : null;
        try {
            if (nameLock == null)
                return installBundleRevisionInternal(context, deployment, serviceTarget);
            synchronized (nameLock) {
                return installBundleRevisionInternal(context, deployment, serviceTarget);
            }
        } finally {
            if (nameLock != null)
                releaseInstallLock(nameKey, nameLock);
        }
    }

    private XBundleRevision installBundleRevisionInternal(BundleContext context, Deployment deployment, ServiceTarget serviceTarget) throws BundleException {
        String symbolicName = deployment.getSymbolicName();
        Version version = Version.parseVersion(deployment.getVersion());

        // If a bundle containing the same location identifier is already installed,
        // the Bundle object for that bundle is returned.
        if (isUpdateOrRefresh(deployment) == false) {
            XBundle bundle = getBundleByLocation(deployment.getLocation());
            if (bundle != null) {
                LOGGER.debugf("Installing an already existing bundle: %s", deployment);
                XBundleRevision brev = bundle.getBundleRevision();
                VFSUtils.safeClose(deployment.getRoot());
                return brev;
            }

            // Check for symbolic name, version uniqueness
            checkUniqunessPolicy(context.getBundle(), symbolicName, version, CollisionHook.INSTALLING);
        }

        UserBundleRevision brev;
        try {

            // Create the revision identifier
            RevisionIdentifier revIdentifier = deployment.getAttachment(REVISION_IDENTIFIER_KEY);
            if (revIdentifier == null) {
                revIdentifier = createRevisionIdentifier(symbolicName, deployment);
                deployment.putAttachment(REVISION_IDENTIFIER_KEY, revIdentifier);
            }

            // Check that we have valid metadata
            OSGiMetaData metadata = deployment.getAttachment(IntegrationConstants.OSGI_METADATA_KEY);
            if (metadata == null) {
                DeploymentProvider plugin = getFrameworkState().getDeploymentProvider();
                metadata = plugin.createOSGiMetaData(deployment);
            }

            // Apply the supplemental manifest of a multi-release bundle
            OSGiMetaData supplemented = MultiReleaseSupport.getSupplementedMetaData(deployment.getRoot(), metadata, getJavaVersion());
            if (supplemented != metadata) {
                metadata = supplemented;
                deployment.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);
            }

            // The syntax of the headers is validated before they are used
            if (metadata.getBundleManifestVersion() > 1)
                BundleManifestSyntaxValidator.validate(metadata, deployment.getLocation());

            // A Fragment-Host that cannot be parsed is a manifest error
            ParameterizedAttribute fragmentHost;
            try {
                fragmentHost = metadata.getFragmentHost();
            } catch (RuntimeException ex) {
                throw new BundleException(ex.getMessage(), BundleException.MANIFEST_ERROR, ex);
            }

            // Create the bundle revision
            if (fragmentHost == null) {
                brev = new HostBundleRevisionFactory(getFrameworkState(), context, deployment, serviceTarget).create();
            } else {
                brev = new FragmentBundleRevisionFactory(getFrameworkState(), context, deployment, serviceTarget).create();
            }
        } catch (RuntimeException rte) {
            VFSUtils.safeClose(deployment.getRoot());
            throw rte;
        } catch (BundleException ex) {
            VFSUtils.safeClose(deployment.getRoot());
            throw ex;
        }

        return brev;
    }

    private boolean isUpdateOrRefresh(Deployment dep) {
        return dep.isBundleUpdate() || dep.getAttachment(IntegrationConstants.BUNDLE_KEY) != null;
    }

    private RevisionIdentifier createRevisionIdentifier(String symbolicName, Deployment dep) {
        Long resourceId;
        StorageState storageState = dep.getAttachment(STORAGE_STATE_KEY);
        if (!dep.isBundleUpdate() && storageState != null) {
            resourceId = new Long(storageState.getBundleId());
        } else {
            XEnvironment env = injectedEnvironment.getValue();
            // The id is higher than any id that was assigned before, also before the framework was launched again (4.4.3)
            StorageManager storageManager = getFrameworkState().getStorageManager();
            if (storageManager instanceof StorageManagerImpl)
                env.nextResourceIdentifier(((StorageManagerImpl) storageManager).getLastBundleId(), symbolicName);
            resourceId = env.nextResourceIdentifier(null, symbolicName);
        }
        return new RevisionIdentifier(resourceId);
    }

    void resolveBundleLifecycle(XBundle bundle) throws ResolutionException {
        getBundleLifecycle().resolve(bundle);
    }

    @Override
    public void resolveBundle(XBundle bundle) throws ResolutionException {
        LockManager lockManager = getFrameworkState().getLockManager();
        LockableItem[] items = getLockableItems(lockManager, Method.RESOLVE, bundle);
        LockContext lockContext = lockManager.lockItems(Method.RESOLVE, items);
        try {
            LOGGER.debugf("Resolving bundle: %s", bundle);
            Set<XBundleRevision> mandatory = Collections.singleton(bundle.getBundleRevision());
            XResolver resolver = getFrameworkState().getFrameworkResolver();
            XEnvironment env = getFrameworkState().getEnvironment();
            XResolveContext context = resolver.createResolveContext(env, mandatory, null);
            resolver.resolveAndApply(context);
            LOGGER.debugf("Resolved bundle: %s", bundle);
        } finally {
            lockManager.unlockItems(lockContext);
        }
    }

    /**
     * Take the locks of a bundle state change: a lock that cannot be obtained in time fails the state change with a
     * BundleException (STATECHANGE_ERROR), as the Bundle methods specify
     */
    private static LockContext lockForStateChange(LockManager lockManager, Method method, LockableItem... items) throws BundleException {
        try {
            return lockManager.lockItems(method, items);
        } catch (LockException ex) {
            throw new BundleException(ex.getMessage(), BundleException.STATECHANGE_ERROR, ex);
        }
    }

    private static void awaitUninterruptibly(ExecutorService service, long timeout, TimeUnit unit) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            try {
                service.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    void startBundleLifecycle(XBundle bundle, int options) throws BundleException {
        BundleLifecycle bundleLifecycle = getBundleLifecycle();
        bundleLifecycle.start(bundle, options);
    }

    @Override
    public void startBundle(XBundle bundle, int options) throws BundleException {
        LockManager lockManager = getFrameworkState().getLockManager();
        LockableItem[] items = getLockableItems(lockManager, Method.START, bundle);
        LockContext lockContext = lockForStateChange(lockManager, Method.START, items);
        boolean started = false;
        try {
            AbstractBundleState.assertBundleState(bundle).startInternal(options);
            started = true;
        } catch (BundleException ex) {
            LOGGER.debugf(ex, "Cannot start bundle: %s", bundle);
            throw ex;
        } finally {
            lockManager.unlockItems(lockContext);
            // The STARTED and LAZY_ACTIVATION events are delivered after the state change lock is released
            Integer deferred = UserBundleState.takeDeferredStartEvent(bundle);
            if (started && deferred != null)
                getFrameworkState().getFrameworkEvents().fireBundleEvent(bundle, deferred);
        }
    }

    void stopBundleLifecycle(XBundle bundle, int options) throws BundleException {
        BundleLifecycle bundleLifecycle = getBundleLifecycle();
        bundleLifecycle.stop(bundle, options);
    }

    @Override
    public void stopBundle(XBundle bundle, int options) throws BundleException {
        LockManager lockManager = getFrameworkState().getLockManager();

        LockableItem[] items = getLockableItems(lockManager, Method.STOP, bundle);
        LockContext lockContext = lockForStateChange(lockManager, Method.STOP, items);
        try {
            AbstractBundleState.assertBundleState(bundle).stopInternal(options);
        } catch (BundleException ex) {
            LOGGER.debugf(ex, "Cannot stop bundle: %s", bundle);
            throw ex;
        } finally {
            lockManager.unlockItems(lockContext);
        }
    }

    @Override
    public void updateBundle(XBundle bundle, InputStream input) throws BundleException {
        LockManager lockManager = getFrameworkState().getLockManager();
        LockableItem[] items = getLockableItems(lockManager, Method.UPDATE, bundle);
        LockContext lockContext = lockForStateChange(lockManager, Method.UPDATE, items);
        int restartOptions;
        try {
            restartOptions = AbstractBundleState.assertBundleState(bundle).updateInternal(input);
        } catch (BundleException ex) {
            LOGGER.debugf(ex, "Cannot update bundle: %s", bundle);
            throw ex;
        } finally {
            lockManager.unlockItems(lockContext);
        }

        // The updated bundle is started like with Bundle.start, without the locks of the update: the bundle lifecycle
        // of an integration may activate the deployment of the new revision in other threads, which take the locks of
        // the start. If Bundle.start throws an exception, a Framework event of type FrameworkEvent.ERROR is fired
        if (restartOptions >= 0) {
            try {
                startBundleLifecycle(bundle, restartOptions);
            } catch (BundleException ex) {
                getFrameworkState().getFrameworkEvents().fireFrameworkEvent(bundle, FrameworkEvent.ERROR, ex);
            }
        }
    }

    void uninstallBundleLifecycle(XBundle bundle, int options) throws BundleException {
        // The headers stay available after the uninstall. They are taken now, the bundle lifecycle may remove the
        // content of the bundle before the bundle is uninstalled
        UserBundleState userBundle = bundle instanceof UserBundleState ? (UserBundleState) bundle : null;
        if (userBundle != null)
            userBundle.setHeadersBeforeUninstall(userBundle.getHeaders(null));
        try {
            BundleLifecycle bundleLifecycle = getBundleLifecycle();
            bundleLifecycle.uninstall(bundle, options);
        } finally {
            if (userBundle != null)
                userBundle.setHeadersBeforeUninstall(null);
        }
    }

    @Override
    public void uninstallBundle(XBundle bundle, int options) throws BundleException {
        LockManager lockManager = getFrameworkState().getLockManager();

        LockableItem[] items = getLockableItems(lockManager, Method.UNINSTALL, bundle);
        LockContext lockContext = lockForStateChange(lockManager, Method.UNINSTALL, items);
        try {
            items = getTransitiveLockForUninstall(lockManager, bundle);
            LockContext transitiveClosureLock = lockForStateChange(lockManager, Method.UNINSTALL, items);
            try {
                AbstractBundleState.assertBundleState(bundle).uninstallInternal(options);
            } finally {
                lockManager.unlockItems(transitiveClosureLock);
            }
        } catch (BundleException ex) {
            LOGGER.errorCannotUninstallBundle(ex, bundle);
            throw ex;
        } finally {
            lockManager.unlockItems(lockContext);
        }

        // Released without the locks of the uninstall, the bundle lifecycle may need them in another thread
        if (bundle instanceof UserBundleState)
            ((UserBundleState) bundle).removeUnusedUninstalledBundles(options);
    }

    void removeBundle(UserBundleState userBundle, int options) {
        LOGGER.debugf("Removing bundle: %s", userBundle);
        for (XBundleRevision brev : userBundle.getAllBundleRevisions()) {
            if ((options & InternalConstants.UNINSTALL_INTERNAL) != 0) {
                removeRevisionLifecycle(brev, options);
            } else {
                removeRevision(brev, options);
            }
        }
        LOGGER.debugf("Removed bundle: %s", userBundle);
    }

    void removeRevisionLifecycle(XBundleRevision brev, int options) {
        BundleLifecycle bundleLifecycle = getBundleLifecycle();
        bundleLifecycle.removeRevision(brev, options);
    }

    @Override
    public void removeRevision(XBundleRevision brev, int options) {
        LOGGER.debugf("Removing revision: %s", brev);
        getFrameworkState().getFrameworkEnvironment().uninstallResources(brev);
        if (brev instanceof UserBundleRevision) {
            UserBundleRevision userRev = (UserBundleRevision) brev;
            userRev.getBundleState().removeRevision(userRev);
            userRev.close();
        }
        LOGGER.debugf("Removed revision: %s", brev);
    }

    private LockableItem[] getLockableItems(LockManager lockManager, Method method, XBundle bundle) {
        LockableItem wireLock = lockManager.getItemForType(FrameworkWiringLock.class);
        LockableItem[] items = null;
        switch (method) {
        	case INSTALL:
        		// do nothing
        		break;
        	case RESOLVE:
            case UPDATE:
            case REFRESH:
            case UNINSTALL:
                items = new LockableItem[] { wireLock, (LockableItem) bundle };
                break;
            case STOP:
                // A stop changes no wiring, the activator is not called under the framework wiring lock
                items = new LockableItem[] { (LockableItem) bundle };
                break;
            case START:
                items = new LockableItem[] { bundle.isResolved() ? (LockableItem) bundle : wireLock, (LockableItem) bundle };
                break;
        }
        return items;
    }

    private BundleLifecycle getBundleLifecycle() {
        return getFrameworkState().getCoreServices().getBundleLifecycle();
    }

    void checkUniqunessPolicy(Bundle targetBundle, String symbolicName, Version version, int policy) throws BundleException {

        if (uniquenessPolicy == UniquenessPolicy.multiple)
            return;

        Set<Bundle> candidates = new HashSet<Bundle>();
        if (symbolicName != null) {
            // Only bundles with the exact version collide, also for the version 0.0.0
            for (XBundle aux : getBundles(symbolicName, null)) {
                if (aux != targetBundle && aux.getState() != Bundle.UNINSTALLED && version.equals(aux.getVersion())) {
                    candidates.add(aux);
                }
            }
        }
        if (candidates.isEmpty())
            return;

        if (uniquenessPolicy == UniquenessPolicy.managed) {
            BundleContext syscontext = getFrameworkState().getSystemBundle().getBundleContext();
            Collection<ServiceReference<CollisionHook>> srefs = null;
            try {
                srefs = syscontext.getServiceReferences(CollisionHook.class, null);
            } catch (InvalidSyntaxException ex) {
                // ignore
            }
            // The hooks can only remove candidates. They are called in service ranking order, highest first; a hook
            // that cannot be obtained is skipped
            Collection<Bundle> shrinkable = new RemoveOnlyCollection<Bundle>(candidates);
            List<ServiceReference<CollisionHook>> sorted = new ArrayList<ServiceReference<CollisionHook>>(srefs != null ? srefs : Collections.<ServiceReference<CollisionHook>>emptyList());
            Collections.sort(sorted, Collections.reverseOrder());
            for (ServiceReference<CollisionHook> sref : sorted) {
                CollisionHook hook = syscontext.getService(sref);
                if (hook == null)
                    continue;
                try {
                    hook.filterCollisions(policy, targetBundle, shrinkable);
                } finally {
                    syscontext.ungetService(sref);
                }
            }
        }
        if (!candidates.isEmpty()) {
            throw new BundleException(MESSAGES.nameAndVersionAlreadyInstalled(symbolicName, version), BundleException.DUPLICATE_BUNDLE_ERROR);
        }
    }

    void unresolveBundle(UserBundleState userBundle) {
        LOGGER.debugf("Unresolving bundle: %s", userBundle);
        ModuleManager moduleManager = getFrameworkState().getModuleManager();
        for (XBundleRevision brev : userBundle.getAllBundleRevisions()) {
            UserBundleRevision userRev = (UserBundleRevision) brev;
            if (userRev.isFragment() == false) {
                ModuleIdentifier identifier = moduleManager.getModuleIdentifier(brev);
                moduleManager.removeModule(brev, identifier);
            }
        }
    }

    private LockableItem[] getTransitiveLockForUninstall(LockManager lockManager, XBundle bundle) {
        Set<LockableItem> lockableItems = new HashSet<LockableItem>();
        lockableItems.add(lockManager.getItemForType(FrameworkWiringLock.class));
        getTransitiveSetOfLockableItemsForUninstall(lockableItems, bundle);
        return lockableItems.toArray(new LockableItem[lockableItems.size()]);
    }

    private void getTransitiveSetOfLockableItemsForUninstall(Set<LockableItem> lockableItems, Bundle bundle) {
        if (!lockableItems.contains(bundle) && bundle.getBundleId() > 0) {
            lockableItems.add((LockableItem) bundle);
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            if (wiring != null) {
                for (BundleWire wire : wiring.getRequiredWires(null)) {
                    Bundle provider = wire.getProvider().getBundle();
                    if (provider.getState() == Bundle.UNINSTALLED) {
                        getTransitiveSetOfLockableItemsForUninstall(lockableItems, provider);
                    }
                }
                for (BundleWire wire : wiring.getProvidedWires(null)) {
                    Bundle requirer = wire.getRequirer().getBundle();
                    if (requirer.getState() == Bundle.UNINSTALLED) {
                        getTransitiveSetOfLockableItemsForUninstall(lockableItems, requirer);
                    }
                }
            }
        }
    }

    @Override
    public void registerExecutorService(ExecutorService executorService) {
        synchronized (executorServices) {
            executorServices.add(executorService);
        }
    }

    @Override
    public void unregisterExecutorService(ExecutorService executorService) {
        synchronized (executorServices) {
            executorServices.remove(executorService);
        }
    }

    void fireFrameworkError(XBundle bundle, String context, Throwable t) {
        FrameworkEvents plugin = getFrameworkState().getFrameworkEvents();
        if (t instanceof BundleException) {
            plugin.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, t);
        } else if (bundle != null) {
            plugin.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, new BundleException("Error " + context + " bundle: " + bundle, t));
        } else {
            SystemBundleState systemBundle = injectedSystemBundle.getValue();
            plugin.fireFrameworkEvent(systemBundle, FrameworkEvent.ERROR, new BundleException("Error " + context, t));
        }
    }

    void fireFrameworkWarning(AbstractBundleState<?> bundleState, String context, Throwable t) {
        FrameworkEvents plugin = getFrameworkState().getFrameworkEvents();
        if (t instanceof BundleException) {
            plugin.fireFrameworkEvent(bundleState, FrameworkEvent.WARNING, t);
        } else if (bundleState != null) {
            plugin.fireFrameworkEvent(bundleState, FrameworkEvent.WARNING, new BundleException("Error " + context + " bundle: " + bundleState, t));
        } else {
            SystemBundleState systemBundle = injectedSystemBundle.getValue();
            plugin.fireFrameworkEvent(systemBundle, FrameworkEvent.WARNING, new BundleException("Error " + context, t));
        }
    }

    boolean hasStopped() {
        return shutdownContainer.isShutdownInitiated() || managerStopped.get();
    }

    void assertNotStopped() {
        if (hasStopped())
            throw MESSAGES.illegalStateFrameworkAlreadyStopped();
    }

    ModuleConnector getModuleConnector() {
        return frameworkBuilder.getModuleConnector();
    }

    /**
     * Initialize the module connector with the storage area, once for the framework: after the storage area was cleaned
     * (storage.clean) and before the connect bundles of the storage are restored
     */
    void initializeModuleConnector(File storageArea) {
        if (frameworkBuilder instanceof FrameworkBuilderImpl && ((FrameworkBuilderImpl) frameworkBuilder).markModuleConnectorInitialized()) {
            Map<String, String> configuration = new HashMap<String, String>(frameworkBuilder.getProperties());
            getModuleConnector().initialize(storageArea, Collections.unmodifiableMap(configuration));
        }
    }

    /**
     * Ask the module connector of a connect framework for the content of the bundle at the given location.
     *
     * @return null if this is not a connect framework, or when the location is not for a connect bundle
     */
    VirtualFile connect(String location) throws BundleException {
        ModuleConnector connector = getModuleConnector();
        if (connector == null)
            return null;

        // A BundleException or an unchecked exception of the connector propagates as it is (60.4.1)
        Optional<ConnectModule> module = connector.connect(location);
        if (module == null || module.isPresent() == false)
            return null;

        // The content is obtained and opened now: a failure of the connect content, also an unchecked exception of the
        // connector code, fails the install with a BundleException that wraps it
        try {
            ConnectVirtualFile file = ConnectVirtualFile.create(location, module.get(), connectContents);
            file.openContent();
            return file;
        } catch (IOException | RuntimeException ex) {
            throw MESSAGES.cannotConnectBundle(ex, location);
        }
    }

    /** Start the activator of the module connector. This is done whenever the framework is initialized. */
    void startConnectActivator() throws BundleException {
        ModuleConnector connector = getModuleConnector();
        if (connector == null)
            return;

        // A failing activator does not fail the init: the failure is published as a framework ERROR event and the
        // activator is not stopped (60.4.2)
        try {
            Optional<BundleActivator> activator = connector.newBundleActivator();
            if (activator != null && activator.isPresent()) {
                activator.get().start(getSystemBundle().getBundleContext());
                connectActivator = activator.get();
            }
        } catch (Exception ex) {
            getFrameworkState().getFrameworkEvents().fireFrameworkEvent(getSystemBundle(), FrameworkEvent.ERROR, MESSAGES.cannotStartConnectActivator(ex));
        }
    }

    private void stopConnectActivator() {
        BundleActivator activator = connectActivator;
        connectActivator = null;
        if (activator != null) {
            try {
                activator.stop(getSystemBundle().getBundleContext());
            } catch (Throwable th) {
                getFrameworkState().getFrameworkEvents().fireFrameworkEvent(getSystemBundle(), FrameworkEvent.ERROR, MESSAGES.cannotStopConnectActivator(th));
            }
        }
    }

    FrameworkExtensions getFrameworkExtensions() {
        return frameworkExtensions;
    }

    /**
     * Stop the framework on another thread and return immediately, as Framework.stop and update do. The given task
     * runs on that thread after the stop, a stop that is already in progress is not started again.
     */
    void stopFramework(final int stoppedEventType, final Runnable afterStop) {
        int state = getManagerState();
        if (state != Bundle.STARTING && state != Bundle.ACTIVE)
            return;
        synchronized (this) {
            if (stopThread != null && stopThread.isAlive())
                return;
            stopThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdownManager(stoppedEventType);
                    if (afterStop != null)
                        afterStop.run();
                }
            }, "Framework stop thread");
            stopThread.start();
        }
    }

    void shutdownManager(boolean stopForUpdate) {
        shutdownManager(stopForUpdate ? FrameworkEvent.STOPPED_UPDATE : FrameworkEvent.STOPPED);
    }

    /**
     * Stop the framework.
     *
     * @param stoppedEventType The type of the event that tells why the framework stopped
     */
    void shutdownManager(int stoppedEventType) {

        // If the Framework is not STARTING and not ACTIVE there is nothing to do
        int state = getManagerState();
        if (state != Bundle.STARTING && state != Bundle.ACTIVE)
            return;

        LOGGER.debugf("Stop framework");

        stoppedEvent = stoppedEventType;
        getSystemBundle().changeState(Bundle.STOPPING);
        setManagerState(Bundle.STOPPING);

        stopBundlesAndActivators();

        // Shutdown all executor services
        synchronized (executorServices) {
            for (ExecutorService service : executorServices) {
                service.shutdown();
            }
            for (ExecutorService service : executorServices) {
                // An interrupt does not cut the shutdown short, the thread keeps its status
                awaitUninterruptibly(service, 10, TimeUnit.SECONDS);
            }
        }

        // The stopped framework keeps no reference to its runtime state (bundles, class loaders): the stop event of
        // waitForStop has the Framework object as its source
        if (framework == null)
            cachedSystemBundle = getSystemBundle();
        shutdownContainer.shutdown();
        if (frameworkBuilder instanceof FrameworkBuilderImpl)
            ((FrameworkBuilderImpl) frameworkBuilder).releaseIntegrationServices();
        setManagerState(Bundle.RESOLVED);
    }

    /**
     * The framework services are stopped without {@link #shutdownManager(int)}, as the server does on a stop or a
     * reload: the bundles are stopped like on a shutdown of the framework (start level 0), their persistent state is
     * kept. The executors end with the services that own them.
     */
    void stopFrameworkServices() {
        // A framework that is stopping or stopped through the system bundle has stopped its bundles
        int state = getManagerState();
        if (state == Bundle.STOPPING || state == Bundle.RESOLVED)
            return;

        SystemBundleState systemBundle = getSystemBundle();
        if (systemBundle == null || systemBundle.getState() != Bundle.ACTIVE)
            return;

        LOGGER.debugf("Stop framework services");
        systemBundle.changeState(Bundle.STOPPING);
        stopBundlesAndActivators();
        systemBundle.changeState(Bundle.RESOLVED);
    }

    private void stopBundlesAndActivators() {
        final CountDownLatch latch = new CountDownLatch(1);
        FrameworkListener listener = new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED) {
                    latch.countDown();
                }
            }
        };

        // Move to start level 0 in the current thread
        FrameworkStartLevel startLevel = getSystemBundle().adapt(FrameworkStartLevel.class);
        ((FrameworkStartLevelSupport) startLevel).shutdownFramework(listener);

        // Wait for the start level event, also when the thread is interrupted; it keeps its status
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();

        // Stop the extension bundle activators before event handling is disabled
        frameworkExtensions.stopActivators();
        stopConnectActivator();
    }

    /**
     * Wait until this Framework has completely stopped.
     *
     * The stop and update methods on a Framework performs an asynchronous stop of the Framework. This method can be used to
     * wait until the asynchronous stop of this Framework has completed. This method will only wait if called when this
     * Framework is in the Bundle.STARTING, Bundle.ACTIVE, or Bundle.STOPPING states. Otherwise it will return immediately.
     *
     * A Framework Event is returned to indicate why this Framework has stopped.
     */
    FrameworkEvent waitForStop(long timeout) throws InterruptedException {
        SystemBundleState systemBundle = getSystemBundle();
        Bundle eventSource = systemBundle != null ? systemBundle : framework != null ? framework : cachedSystemBundle;

        FrameworkEvent frameworkEvent = null;
        shutdownContainer.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        if (eventSource != null) {
            int eventType = shutdownContainer.isShutdownComplete() ? stoppedEvent : FrameworkEvent.WAIT_TIMEDOUT;
            frameworkEvent = new FrameworkEvent(eventType, eventSource, null);
        }
        return frameworkEvent;
    }

    static {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {

            @Override
            public Object run() {
                List<String> execEnvironments = new ArrayList<String>();
                if (Java.isCompatible(Java.VERSION_1_1)) {
                    // OSGi/Minimum-1.0 is a subset of OSGi/Minimum-1.1
                    execEnvironments.add("OSGi/Minimum-1.0");
                    execEnvironments.add("OSGi/Minimum-1.1");
                    execEnvironments.add("J2SE-1.1");
                }
                if (Java.isCompatible(Java.VERSION_1_2)) {
                    execEnvironments.add("OSGi/Minimum-1.2");
                    execEnvironments.add("J2SE-1.2");
                }
                if (Java.isCompatible(Java.VERSION_1_3))
                    execEnvironments.add("J2SE-1.3");
                if (Java.isCompatible(Java.VERSION_1_4))
                    execEnvironments.add("J2SE-1.4");
                if (Java.isCompatible(Java.VERSION_1_5))
                    execEnvironments.add("J2SE-1.5");
                if (Java.isCompatible(Java.VERSION_1_6))
                    execEnvironments.add("JavaSE-1.6");
                if (Java.isCompatible(Java.VERSION_1_7))
                    execEnvironments.add("JavaSE-1.7");
                if (Java.isCompatible(Java.VERSION_1_8)) {
                    execEnvironments.add("JavaSE-1.8");
                    // The compact profiles of Java 8 are subsets of every later Java platform
                    execEnvironments.add("JavaSE/compact1-1.8");
                    execEnvironments.add("JavaSE/compact2-1.8");
                    execEnvironments.add("JavaSE/compact3-1.8");
                }
                // Every version since Java 9 up to the running Java platform
                if (Java.isCompatible(Java.VERSION_9)) {
                    for (int version = 9; version <= Java.getFeatureVersion(); version++)
                        execEnvironments.add("JavaSE-" + version);
                }

                String envlist = execEnvironments.toString();
                envlist = envlist.substring(1, envlist.length() - 1);
                OSGi_FRAMEWORK_EXECUTIONENVIRONMENT = envlist;

                OSGi_FRAMEWORK_OS_NAME = SecurityActions.getSystemProperty("os.name", null);
                OSGi_FRAMEWORK_OS_VERSION = getOSVersionInOSGiFormat();
                OSGi_FRAMEWORK_PROCESSOR = SecurityActions.getSystemProperty("os.arch", null);

                return null;
            }
        });
    }

    // Turn the OS version into an OSGi-compatible version. The spec says that an external operator
    // should do this by changing the framework properties, but this is pretty inconvenient and other
    // OSGi frameworks seem to automatically fix this too. The original os version is still available
    // in the "os.version" system property.
    static String getOSVersionInOSGiFormat() {
        return getOSVersionInOSGiFormat(SecurityActions.getSystemProperty("os.version", null));
    }

    /**
     * The os version as a version: the configured value, the part of it that is a version for a value like
     * 5.15.0-91-generic or 1.2.3.a+b, or 0.0.0 when no part of it is a version.
     */
    static Version getOSVersion(String osVersion) {
        if (osVersion == null)
            return Version.emptyVersion;
        String numeric = getOSVersionInOSGiFormat(osVersion);
        String[] parts = numeric.split("\\.");
        String withoutQualifier = parts.length > 3 ? parts[0] + "." + parts[1] + "." + parts[2] : numeric;
        for (String candidate : new String[] { osVersion, numeric, withoutQualifier }) {
            try {
                return Version.parseVersion(candidate);
            } catch (IllegalArgumentException ex) {
                // try the next part
            }
        }
        return Version.emptyVersion;
    }

    static String getOSVersionInOSGiFormat(String sysVersion) {
        StringBuilder osgiVersion = new StringBuilder();

        String[] elements = sysVersion.split("\\.");
        int i = 0;
        for (; i < 3 && i < elements.length; i++) {
            try {
                Integer.parseInt(elements[i]);
                if (i > 0)
                    osgiVersion.append('.');
                osgiVersion.append(elements[i]);
            } catch (NumberFormatException nfe) {
                break;
            }
        }

        if (i == 3 && elements.length > 3) {
            // All the parts were ok so far, now add the qualifier
            osgiVersion.append('.');
            osgiVersion.append(elements[3]);
        }

        return osgiVersion.toString();
    }

    // The TCK calls waitForStop before it initiates the shutdown, in which case the {@link ServiceContainer}
    // returns imediately from awaitTermination and returns false from isShutdownComplete.
    // We compensate with a grace periode that allows the shutdown to get initiated after awaitTermination.
    static class ShutdownContainer {
        private final ServiceContainer serviceContainer;
        private final AtomicBoolean shutdownInitiated;
        private final boolean allowContainerShutdown;

        ShutdownContainer(final ServiceContainer serviceContainer, boolean allowContainerShutdown) {
            this.serviceContainer = serviceContainer;
            this.allowContainerShutdown = allowContainerShutdown;
            this.shutdownInitiated = new AtomicBoolean(false);
        }

        ServiceController<?> getRequiredService(ServiceName serviceName) {
            return serviceContainer.getRequiredService(serviceName);
        }

        boolean isShutdownInitiated() {
            return shutdownInitiated.get();
        }

        void shutdown() {
            if (allowContainerShutdown) {
                serviceContainer.shutdown();
                synchronized (shutdownInitiated) {
                    shutdownInitiated.set(true);
                    LOGGER.debugf("shutdownInitiated.notifyAll");
                    shutdownInitiated.notifyAll();
                }
            }
        }

        void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            LOGGER.debugf("awaitTermination: %dms", unit.toMillis(timeout));
            synchronized (shutdownInitiated) {
                if (shutdownInitiated.get() == false) {
                    LOGGER.debugf("shutdownInitiated.wait");
                    shutdownInitiated.wait(2000);
                }
            }
            serviceContainer.awaitTermination(timeout == 0 ? Long.MAX_VALUE : timeout, unit);
        }

        boolean isShutdownComplete() {
            return serviceContainer.isShutdownComplete();
        }
    }
}