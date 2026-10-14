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
 * The service and framework listeners of a stopping bundle are not removed: a framework listener of a previous
 * activation is called again after the bundle is restarted, and every bundle that ever registered a service listener
 * (every ServiceTracker user) stays in memory after it is uninstalled.
 *
 * <h3>Severity</h3>
 * critical - unbounded memory leak in the running server plus a lifecycle violation. Nearly every real bundle opens a
 * ServiceTracker (a service listener) and many add framework listeners; each redeploy of such a bundle keeps its
 * bundle object, revision, class loader and classes. Even a bundle that cleanly removes its listener (ServiceTracker.close)
 * leaks, because the per-bundle entry stays in the listener map with an empty list.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleServices.release} (stop/uninstall clean-up) and
 * {@code FrameworkEventsImpl} ({@code serviceListeners}/{@code frameworkListeners} maps).
 *
 * <h3>Found by</h3>
 * wildfly workstream, round 2 of the redeploy leak analysis (after wildfly/06): with the resolver/19 and resolver/20
 * fixes applied as a trial patch, redeploying smoke.consumer (ServiceTracker opened in start, closed in stop) still
 * retained one bundle per redeploy; HPROF root path
 * {@code UserBundleState <- key ConcurrentHashMap .serviceListeners <- FrameworkEventsImpl}.
 * In the server (patched WildFly 41.0.1.Final + jbosgi R8 overlay, Docker eclipse-temurin:25-jdk, with trial patches for
 * resolver/19 and resolver/20 applied so that the other leaks are out of the way) 10 redeploys ({@code deploy --force})
 * of each smoke bundle retained:
 * smoke.consumer 10 of 10 (UserBundleState 41 -> 51); with the trial fix below: 0 (18 -> 18).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, Bundle.stop(int) javadoc, step 7: "Any listeners registered by this bundle must be removed." (same in the
 * failure path of Bundle.start(int), step 8.5). BundleContext javadoc: "The BundleContext object is only valid during
 * the execution of its context bundle". https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Bundle.stop-int-
 *
 * <h3>Code location</h3>
 * jbosgi-framework core/src/main/java/org/jboss/osgi/framework/internal/BundleServices.java:129-141 ({@code release}
 * unregisters services, releases used services and calls only {@code frameworkEvents.removeBundleListeners(bundle)});
 * UserBundleState.java:832-839 (removeServicesAndListeners delegates to it); FrameworkEventsImpl.java:87/89 (maps keyed
 * by the bundle) and :259-284 ({@code removeServiceListener} removes the registration but leaves the bundle's empty
 * list in the map). Only ModuleBundleContext.release (lines 89-95) also removes service and framework listeners.
 * Pre-existing (same on origin/WF41).
 *
 * <h3>Root cause</h3>
 * The stop/uninstall clean-up of user bundles forgets the service and framework listeners, and removing the last
 * listener of a bundle does not remove the bundle's map entry.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=StoppedBundleListenersNotRemovedIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests -Dmaven.compiler.proc=full}).
 * The bundles of the test import nothing, so the provided-wire leak (wildfly/06, resolver/19) and the
 * initialRevision retention (resolver/20) do not interfere: the test fails for this root cause alone.
 *
 * <h3>Observed</h3>
 * <pre>
 * testFrameworkListenerRemovedOnStop: Expected the framework listener added during the first activation not to be
 *   called after stop and restart, but it was called 1 times
 * testBundleWithServiceListenerCollectable: Expected an uninstalled and refreshed bundle that had a service listener
 *   when it was stopped to be garbage collectable, but it is still reachable
 * testBundleThatRemovedItsServiceListenerCollectable: Expected an uninstalled and refreshed bundle that had removed its
 *   service listener (like ServiceTracker.close) to be garbage collectable, but it is still reachable
 * </pre>
 *
 * <h3>Expected</h3>
 * All listeners of the bundle are removed when it stops; no reference to the bundle remains in the listener maps.
 *
 * <h3>Reference behaviour</h3>
 * Not checked against Equinox/Felix in a test; both remove all listeners of a stopping bundle (EventDispatcher /
 * EventListeners are per BundleContext and dropped with it).
 *
 * <h3>Suggested fix</h3>
 * In BundleServices.release also call {@code frameworkEvents.removeServiceListeners(bundle)} and
 * {@code frameworkEvents.removeFrameworkListeners(bundle)}, and in FrameworkEventsImpl.removeServiceListener remove the
 * bundle's entry when its list becomes empty. Verified with a trial patch: all three methods pass, and in the server 10
 * consumer redeploys retain nothing (see above).
 *
 * <h3>Regression vs WF41</h3>
 * no - same code on WF41.
 *
 * @since 13-Oct-2026
 */
public class StoppedBundleListenersNotRemovedIssueTestCase extends AbstractFrameworkLaunchTest {

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
    public void testFrameworkListenerRemovedOnStop() throws Exception {
        Bundle bundle = installPlainBundle("framework-listener-user");
        bundle.start();
        final AtomicInteger calls = new AtomicInteger();
        bundle.getBundleContext().addFrameworkListener(new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                calls.incrementAndGet();
            }
        });
        bundle.stop();
        bundle.start();
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(null, new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Refresh completes", latch.await(10, TimeUnit.SECONDS));
        Thread.sleep(500);
        assertEquals("Expected the framework listener added during the first activation not to be called after stop and restart, but it was called "
                + calls.get() + " times", 0, calls.get());
    }

    @Test
    public void testBundleWithServiceListenerCollectable() throws Exception {
        Bundle bundle = installPlainBundle("service-listener-user");
        bundle.start();
        bundle.getBundleContext().addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
            }
        });
        bundle.stop();
        uninstallAndRefresh(bundle);
        WeakReference<Bundle> ref = new WeakReference<Bundle>(bundle);
        bundle = null;
        assertTrue("Expected an uninstalled and refreshed bundle that had a service listener when it was stopped to be garbage collectable, but it is still reachable",
                isCollected(ref));
    }

    @Test
    public void testBundleThatRemovedItsServiceListenerCollectable() throws Exception {
        Bundle bundle = installPlainBundle("service-listener-remover");
        bundle.start();
        ServiceListener listener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
            }
        };
        bundle.getBundleContext().addServiceListener(listener);
        bundle.getBundleContext().removeServiceListener(listener);
        bundle.stop();
        uninstallAndRefresh(bundle);
        WeakReference<Bundle> ref = new WeakReference<Bundle>(bundle);
        bundle = null;
        assertTrue("Expected an uninstalled and refreshed bundle that had removed its service listener (like ServiceTracker.close) to be garbage collectable, but it is still reachable",
                isCollected(ref));
    }
}
