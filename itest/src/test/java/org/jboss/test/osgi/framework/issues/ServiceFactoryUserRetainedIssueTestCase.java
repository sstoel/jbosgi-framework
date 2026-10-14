package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

/**
 * A bundle that once got a bundle-scoped service (ServiceFactory) stays in memory forever: the service keeps a
 * ServiceFactoryHolder per requesting bundle id, which references the bundle, and never removes it.
 *
 * <h3>Severity</h3>
 * critical - unbounded memory leak in the running server. The framework's own LogService/LoggerFactory and Felix
 * ConfigurationAdmin are bundle-scoped services: every redeployed bundle that logs through the OSGi Log Service or uses
 * ConfigurationAdmin (and every DS bundle, for which Felix SCR gets a LogService with the bundle's context) keeps its
 * bundle object, revision, class loader and classes for the life of the service.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceStateImpl} ({@code factoryValues},
 * {@code ServiceFactoryHolder}).
 *
 * <h3>Found by</h3>
 * wildfly workstream, round 2 of the redeploy leak analysis (after wildfly/06): with the resolver/19 and resolver/20
 * fixes applied as a trial patch, 40 of the remaining retained bundles were held by HPROF root path
 * {@code UserBundleState <- .bundle ServiceStateImpl$ServiceFactoryHolder <- HashMap .factoryValues <- ServiceStateImpl
 * (the framework's LogService) <- ServiceReferenceWrapper <- static org.apache.felix.cm.impl.Log.logger}.
 * In the server (patched WildFly 41.0.1.Final + jbosgi R8 overlay, Docker eclipse-temurin:25-jdk, with trial patches for
 * resolver/19 and resolver/20 applied so that the other leaks are out of the way) 10 redeploys ({@code deploy --force})
 * of each smoke bundle retained:
 * smoke.r8 (gets the LoggerFactory) 10 of 10 (UserBundleState 30 -> 40) and smoke.ds (a DS component; Felix SCR gets
 * the LogService through the DS bundle's context) 10 of 10 (16 -> 26); with the trial fixes for this issue,
 * StoppedBundleListenersNotRemovedIssueTestCase and LoggerForOtherBundleRetainedIssueTestCase: 0.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 5.9.3 / ServiceFactory.ungetService javadoc: the framework calls ungetService "when a bundle releases
 * the service" and, Bundle.stop step 7, "Any services used by this bundle must be released"; BundleContext.ungetService
 * javadoc: "If the context bundle's use count for the service is zero ... the service object for the context bundle is
 * released" - after the release the framework has no reason to reference the bundle. Together with the requirement of
 * resolver/19/resolver/20 (BundleWiring.isInUse: stale wirings are discarded) an uninstalled bundle must become
 * collectable. https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.ServiceFactory
 *
 * <h3>Code location</h3>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:80 ({@code factoryValues}
 * keyed by bundle id), :133-168 (getScopedValue puts a ServiceFactoryHolder(bundle, factory)), :171-183
 * (ungetScopedValue calls holder.ungetService() but never removes the holder), :696-746 (the holder keeps the bundle).
 * Pre-existing (same on origin/WF41).
 *
 * <h3>Root cause</h3>
 * The per-bundle holder is created on the first getService and kept after its use count drops to zero.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ServiceFactoryUserRetainedIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests -Dmaven.compiler.proc=full}).
 * The bundles of the test import nothing, so the provided-wire leak (wildfly/06, resolver/19) and the
 * initialRevision retention (resolver/20) do not interfere: the test fails for this root cause alone.
 *
 * <h3>Observed</h3>
 * <pre>
 * testBundleThatUsedServiceFactoryCollectable: Expected an uninstalled and refreshed bundle that got and released a
 *   bundle-scoped service to be garbage collectable, but it is still reachable
 * testBundleThatUsedLoggerFactoryCollectable: Expected an uninstalled and refreshed bundle that used the framework's
 *   LoggerFactory to be garbage collectable, but it is still reachable
 * </pre>
 *
 * <h3>Expected</h3>
 * The holder is removed when the bundle's use count reaches zero (and when the bundle releases all its services on
 * stop), so the bundle is collectable.
 *
 * <h3>Reference behaviour</h3>
 * Not checked in a test; Equinox (ServiceUse/ServiceRegistrationImpl.contextsUsing) and Felix (ServiceRegistry
 * usage counts) drop the per-bundle entries when the use count reaches zero.
 *
 * <h3>Suggested fix</h3>
 * In ungetScopedValue remove the holder from factoryValues when its use count is zero after the unget (and in
 * BundleServices.release when the bundle's use count was forced to zero). Verified with a trial patch: both methods
 * pass, and in the server 10 smoke.r8 / smoke.ds redeploys retain nothing.
 *
 * <h3>Regression vs WF41</h3>
 * no - same code on WF41 (on R8 the framework's LogService is new and bundle-scoped, which makes the leak hit every
 * logging bundle).
 *
 * @since 13-Oct-2026
 */
public class ServiceFactoryUserRetainedIssueTestCase extends AbstractFrameworkLaunchTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.ACTIVE | Bundle.STARTING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    /** A bundle without Import-Package, Require-* and classes: it has no wires, so the provided-wire leak (resolver/19) cannot hold it */
    private Bundle installPlainBundle(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return framework.getBundleContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }

    private void uninstallAndRefresh(Bundle bundle) throws Exception {
        bundle.uninstall();
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(bundle), new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Refresh completes", latch.await(10, TimeUnit.SECONDS));
    }

    /** The Log Service keeps the last 100 entries, which reference the bundles; push them out, then GC */
    private boolean isCollected(WeakReference<?> ref) throws Exception {
        BundleContext syscontext = framework.getBundleContext();
        ServiceReference<LogService> sref = syscontext.getServiceReference(LogService.class);
        LogService log = syscontext.getService(sref);
        for (int i = 0; i < 300; i++)
            log.log(LogService.LOG_INFO, "filler " + i);
        syscontext.ungetService(sref);
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ref.get() == null;
    }

    @Test
    public void testBundleThatUsedServiceFactoryCollectable() throws Exception {
        ServiceRegistration<?> reg = framework.getBundleContext().registerService(Runnable.class.getName(), new ServiceFactory<Object>() {
            public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
                return new Runnable() {
                    public void run() {
                    }
                };
            }

            public void ungetService(Bundle bundle, ServiceRegistration<Object> registration, Object service) {
            }
        }, null);
        try {
            Bundle bundle = installPlainBundle("service-factory-user");
            bundle.start();
            BundleContext context = bundle.getBundleContext();
            ServiceReference<?> sref = context.getServiceReference(Runnable.class.getName());
            context.getService(sref);
            context.ungetService(sref);
            context = null;
            bundle.stop();
            uninstallAndRefresh(bundle);
            WeakReference<Bundle> ref = new WeakReference<Bundle>(bundle);
            bundle = null;
            assertTrue("Expected an uninstalled and refreshed bundle that got and released a bundle-scoped service to be garbage collectable, but it is still reachable",
                    isCollected(ref));
        } finally {
            reg.unregister();
        }
    }

    @Test
    public void testBundleThatUsedLoggerFactoryCollectable() throws Exception {
        Bundle bundle = installPlainBundle("logger-factory-user");
        bundle.start();
        BundleContext context = bundle.getBundleContext();
        ServiceReference<LoggerFactory> sref = context.getServiceReference(LoggerFactory.class);
        context.getService(sref).getLogger("test").info("hello");
        context.ungetService(sref);
        context = null;
        bundle.stop();
        uninstallAndRefresh(bundle);
        WeakReference<Bundle> ref = new WeakReference<Bundle>(bundle);
        bundle = null;
        assertTrue("Expected an uninstalled and refreshed bundle that used the framework's LoggerFactory to be garbage collectable, but it is still reachable",
                isCollected(ref));
    }
}
