/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jboss.modules.DependencySpec;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleSpec;
import org.jboss.modules.ResourceLoaderSpec;
import org.jboss.osgi.framework.spi.AbstractBundleRevisionAdaptor;
import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkModuleLoader;
import org.jboss.osgi.framework.spi.IntegrationConstants;
import org.jboss.osgi.framework.spi.IntegrationServices;
import org.jboss.osgi.framework.spi.VirtualFileResourceLoader;
import org.jboss.osgi.metadata.OSGiMetaData;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.jboss.osgi.resolver.XEnvironment;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.Version;

/**
 * A module bundle (a JBoss Modules module exposed as a Bundle through the AbstractBundleAdaptor SPI, as the WildFly
 * subsystem does for module capabilities and module deployments) does not follow the Bundle.start/stop state machine:
 * no bundle events are fired, a failing activator start leaves its services registered, and a failing activator stop
 * leaves the bundle ACTIVE.
 *
 * <h2>Severity</h2>
 * major (module bundles are visible to every bundle as ordinary Bundles; extenders and BundleTrackers (DS, Blueprint,
 * Http whiteboards) never see them start or stop, and a failed activator leaks registered services and listeners).
 *
 * <h2>Component</h2>
 * jbosgi-framework, module core, class org.jboss.osgi.framework.spi.AbstractBundleAdaptor (start/stop), used through
 * AbstractBundleRevisionAdaptor by jbosgi/wildfly/subsystem ResourceInstallerService.installModuleResource and
 * ModuleRegistrationTracker.registerInternal (module bundles with OSGi metadata, which can name a Bundle-Activator).
 *
 * <h2>Found by</h2>
 * Tests workstream, round 3 coverage follow-up (the TCK does not reach the adaptor SPI; the itest leaves the activator,
 * failure and event paths of AbstractBundleAdaptor.start/stop uncovered). Exploratory test that installs a module the
 * way the WildFly subsystem does.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8, org.osgi.framework.Bundle.start(int) javadoc: "This bundle's state is set to STARTING. A bundle event
 * of type BundleEvent.STARTING is fired. ... If the BundleActivator is invalid or throws an exception then: This
 * bundle's state is set to STOPPING. A bundle event of type BundleEvent.STOPPING is fired. Any services registered by
 * this bundle must be unregistered. Any services used by this bundle must be released. Any listeners registered by this
 * bundle must be removed. This bundle's state is set to RESOLVED. A bundle event of type BundleEvent.STOPPED is fired.
 * A BundleException is then thrown. ... A bundle event of type BundleEvent.STARTED is fired."
 * Bundle.stop(int): "A bundle event of type BundleEvent.STOPPING is fired. ... the BundleActivator.stop(BundleContext)
 * method ... is called. If that method throws an exception, this method must continue to stop this bundle and a
 * BundleException must be thrown after completion of the remaining steps. ... This bundle's state is set to RESOLVED.
 * A bundle event of type BundleEvent.STOPPED is fired." Core R8 4.7.1: bundle events are delivered to
 * BundleListeners/SynchronousBundleListeners for every bundle.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/spi/AbstractBundleAdaptor.java:244-300 (start: only
 * {@code bundleState.set(...)}, no FrameworkEvents.fireBundleEvent; the catch block sets RESOLVED without releasing the
 * ModuleBundleContext, :292-293) and :306-338 (stop: no events; when the activator throws, the exception escapes before
 * {@code bundleState.set(Bundle.RESOLVED)} at :330, so the bundle stays ACTIVE). Unchanged on WF41 (pre-existing).
 *
 * <h2>Root cause</h2>
 * The adaptor implements its own minimal lifecycle instead of the framework's (UserBundleState/BundleLifecycle), and
 * the failure branches were never completed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full install -DskipTests
 * JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ModuleBundleLifecycleIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full only works around tests/01, LoggerImplementationMissingIssueTestCase.)
 *
 * <h2>Observed</h2>
 * <pre>
 * testBundleEventsFired: Expected bundle events [STARTING, STARTED, STOPPING, STOPPED] for start/stop of a module
 *   bundle, but got [] expected:&lt;[128, 2, 256, 4]&gt; but was:&lt;[]&gt;
 * testFailedStartUnregistersServices: Expected no registered services after the activator start failed, but 1 service
 *   is still registered
 * testFailedStopSetsResolved: Expected state RESOLVED (4) after the activator stop failed, but was 32 (ACTIVE)
 * </pre>
 *
 * <h2>Expected</h2>
 * The same behaviour as an ordinary bundle: STARTING/STARTED/STOPPING/STOPPED events, services and listeners released
 * and state RESOLVED when the activator fails in start or stop.
 *
 * <h2>Reference behaviour</h2>
 * Ordinary bundles in jbosgi (UserBundleState) and in Equinox/Felix follow the javadoc; module bundles have no
 * counterpart there.
 *
 * <h2>Suggested fix</h2>
 * In AbstractBundleAdaptor.start/stop fire the bundle events through
 * {@code bundleManager.getFrameworkState().getFrameworkEvents().fireBundleEvent(...)} (as UserBundleState does);
 * in the start catch block set STOPPING, release the ModuleBundleContext (unregister services, release used services,
 * remove listeners), set RESOLVED, fire STOPPED; in stop use try/finally so that the context is released, the state is
 * set to RESOLVED and STOPPED is fired before the BundleException is thrown.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing; this test class run against origin/WF41 fails with the same 3 assertions: no events, a leaked service
 * after a failed start, ACTIVE after a failed stop).
 */
public class ModuleBundleLifecycleIssueTestCase extends OSGiFrameworkTest {

    static final String FAIL_START = "issues.modulebundle.failStart";
    static final String FAIL_STOP = "issues.modulebundle.failStop";

    public static class ModuleActivator implements BundleActivator {
        @Override
        public void start(BundleContext context) {
            // A lambda, the module only contains the activator class
            Runnable service = () -> { };
            context.registerService(Runnable.class, service, null);
            if (Boolean.getBoolean(FAIL_START))
                throw new IllegalStateException("start failure");
        }

        @Override
        public void stop(BundleContext context) {
            if (Boolean.getBoolean(FAIL_STOP))
                throw new IllegalStateException("stop failure");
        }
    }

    private Module module;
    private VirtualFile virtualFile;
    private XBundleRevision brev;

    @After
    public void tearDown() throws Exception {
        System.clearProperty(FAIL_START);
        System.clearProperty(FAIL_STOP);
        if (brev != null) {
            XBundle bundle = brev.getBundle();
            if (bundle.getState() != Bundle.UNINSTALLED)
                bundle.uninstall();
            ((XBundle) getSystemContext().getBundle()).adapt(XEnvironment.class).uninstallResources(brev);
        }
        if (module != null) {
            XBundleRevision mock = Mockito.mock(XBundleRevision.class);
            Mockito.when(mock.getModuleIdentifier()).thenReturn(module.getIdentifier());
            getModuleLoader().removeModule(mock);
        }
        VFSUtils.safeClose(virtualFile);
        super.tearDown();
    }

    private FrameworkModuleLoader getModuleLoader() throws Exception {
        BundleManager bundleManager = getFramework().adapt(BundleManager.class);
        return (FrameworkModuleLoader) bundleManager.getServiceContainer().getRequiredService(IntegrationServices.FRAMEWORK_MODULE_LOADER_PLUGIN).getValue();
    }

    /** Install a module bundle with an activator, like the WildFly subsystem installs module resources */
    private XBundle installModuleBundle(String name) throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(ModuleActivator.class);
        virtualFile = toVirtualFile(archive);
        ModuleIdentifier identifier = ModuleIdentifier.fromString(name);
        ModuleSpec.Builder specBuilder = ModuleSpec.build(identifier);
        specBuilder.addResourceRoot(ResourceLoaderSpec.createResourceLoaderSpec(new VirtualFileResourceLoader(virtualFile)));
        specBuilder.addDependency(DependencySpec.createSystemDependencySpec(Collections.singleton("org/osgi/framework")));
        specBuilder.addDependency(DependencySpec.createLocalDependencySpec());
        FrameworkModuleLoader moduleLoader = getModuleLoader();
        moduleLoader.addModuleSpec(Mockito.mock(XBundleRevision.class), specBuilder.create());
        module = moduleLoader.getModuleLoader().loadModule(identifier);

        final BundleContext context = getSystemContext();
        XBundleRevisionBuilderFactory factory = new XBundleRevisionBuilderFactory() {
            @Override
            public XBundleRevision createResource() {
                return new AbstractBundleRevisionAdaptor(context, module);
            }
        };
        OSGiMetaDataBuilder metaBuilder = OSGiMetaDataBuilder.createBuilder(name, Version.parseVersion("1.0"));
        metaBuilder.setBundleActivator(ModuleActivator.class.getName());
        metaBuilder.addImportPackages("org.osgi.framework");
        OSGiMetaData metadata = metaBuilder.getOSGiMetaData();
        brev = XBundleRevisionBuilderFactory.create(factory).loadFrom(metadata).getResource();
        brev.putAttachment(IntegrationConstants.OSGI_METADATA_KEY, metadata);
        ((XBundle) context.getBundle()).adapt(XEnvironment.class).installResources(brev);
        return brev.getBundle();
    }

    @Test
    public void testBundleEventsFired() throws Exception {
        final XBundle bundle = installModuleBundle("module-bundle-events");
        final List<Integer> events = Collections.synchronizedList(new ArrayList<Integer>());
        SynchronousBundleListener listener = new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle() == bundle)
                    events.add(event.getType());
            }
        };
        getSystemContext().addBundleListener(listener);
        try {
            bundle.start();
            bundle.stop();
        } finally {
            getSystemContext().removeBundleListener(listener);
        }
        List<Integer> expected = new ArrayList<Integer>();
        Collections.addAll(expected, BundleEvent.STARTING, BundleEvent.STARTED, BundleEvent.STOPPING, BundleEvent.STOPPED);
        assertEquals("Expected bundle events [STARTING, STARTED, STOPPING, STOPPED] for start/stop of a module bundle, but got " + events, expected, events);
    }

    @Test
    public void testFailedStartUnregistersServices() throws Exception {
        XBundle bundle = installModuleBundle("module-bundle-failstart");
        System.setProperty(FAIL_START, "true");
        try {
            bundle.start();
            fail("Expected a BundleException from start when the activator throws");
        } catch (BundleException ex) {
            // expected
        }
        assertEquals("Expected state RESOLVED after the activator start failed", Bundle.RESOLVED, bundle.getState());
        assertNull("Expected no registered services after the activator start failed, but "
                + (bundle.getRegisteredServices() == null ? 0 : bundle.getRegisteredServices().length) + " service is still registered",
                bundle.getRegisteredServices());
    }

    @Test
    public void testFailedStopSetsResolved() throws Exception {
        XBundle bundle = installModuleBundle("module-bundle-failstop");
        bundle.start();
        System.setProperty(FAIL_STOP, "true");
        try {
            bundle.stop();
            fail("Expected a BundleException from stop when the activator throws");
        } catch (BundleException ex) {
            // expected
        }
        assertEquals("Expected state RESOLVED (4) after the activator stop failed, but was " + bundle.getState(), Bundle.RESOLVED, bundle.getState());
    }
}
