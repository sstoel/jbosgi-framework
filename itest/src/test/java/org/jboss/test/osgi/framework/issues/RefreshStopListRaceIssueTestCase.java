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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * FrameworkWiring.refreshBundles decides which bundles to stop before it acquires its locks. A bundle of the
 * dependency closure that is started in between is refreshed while ACTIVE: it is unresolved and set to INSTALLED
 * without being stopped (no STOPPING/STOPPED, its activator's stop is never called, its services stay registered),
 * and it is not started again.
 *
 * <h3>Severity</h3>
 * major - concurrent start and refresh (common in a server where deployments and management operations run in
 * parallel) silently corrupt the bundle state; the bundle reports INSTALLED while its activator and services are still
 * live and it can no longer be stopped.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundlesInternal}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful multi-threaded API fuzzer ({@code Stateful}, 4 and 8 threads, resolveBundles disabled to
 * avoid fuzz/16): the event state machine oracle reported "event UNRESOLVED after STARTED ... on thread Framework
 * Refresh Thread" (seeds 3000-3013, 4000-4006). This test makes the interleaving deterministic: a bundle of the closure
 * is being started (its activator's start() waits, holding only that bundle's lock) while the refresh is requested, so
 * the refresh thread waits for the lock after it has computed its stop list; meanwhile the exporter is started.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.refreshBundles javadoc (OSGi Core R8, 10.4.8.4): "This method returns to the caller immediately and
 * then performs the following steps on a separate thread: ... Each bundle in the dependency closure that is in the
 * ACTIVE state will be stopped as described in the Bundle.stop method. Each bundle in the dependency closure that is
 * in the RESOLVED state is unresolved and thus moved to the INSTALLED state. ... Each bundle in the dependency closure
 * that was in the ACTIVE state prior to Step 2 is started as described in the Bundle.start method".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:170-188}: the
 * {@code stopList} is built from {@code bundle.getState()} before {@code lockManager.lockItems(Method.REFRESH, items)};
 * the bundles that became ACTIVE while the refresh waited for the lock are not stopped, but
 * {@code userBundle.refresh()} (:217-228, {@code UserBundleState.refresh}) unresolves them and sets INSTALLED.
 *
 * <h3>Root cause</h3>
 * Check-then-act: the state snapshot that decides stop/restart is taken outside the lock that serializes lifecycle
 * operations.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RefreshStopListRaceIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testBundleStartedDuringRefreshIsStoppedAndRestarted: The ACTIVE exporter must be stopped by the refresh,
 *   events: [STARTING, STARTED, UNRESOLVED, INSTALLED, RESOLVED]
 * (the exporter was ACTIVE when the refresh thread got its locks; it was unresolved without STOPPING/STOPPED and not
 * started again)
 * </pre>
 *
 * <h3>Expected</h3>
 * The exporter, ACTIVE when the refresh runs, is stopped (STOPPING, STOPPED) before it is unresolved and started again;
 * at the end it is ACTIVE.
 *
 * <h3>Reference behaviour</h3>
 * Not compared with Equinox (timing dependent); Equinox computes the stop set under its container lock.
 *
 * <h3>Suggested fix</h3>
 * Build the stop list after {@code lockItems} has succeeded (and take the locks of the whole dependency closure
 * before inspecting the states).
 *
 * <h3>Regression vs WF41</h3>
 * no - the refresh code is unchanged by the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class RefreshStopListRaceIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testBundleStartedDuringRefreshIsStoppedAndRestarted() throws Exception {
        final Bundle exporter = install("refresh-race-exporter", "Export-Package", "org.jboss.test.osgi.refreshrace", null);
        final Bundle importer = install("refresh-race-importer", "Import-Package", "org.jboss.test.osgi.refreshrace,org.osgi.framework", SlowStartActivator.class);
        try {
            assertTrue("Precondition: importer resolved and wired to the exporter",
                    getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(java.util.Arrays.asList(exporter, importer)));

            final List<String> events = Collections.synchronizedList(new ArrayList<String>());
            SynchronousBundleListener listener = new SynchronousBundleListener() {
                public void bundleChanged(BundleEvent event) {
                    if (event.getBundle() == exporter)
                        events.add(name(event.getType()));
                }
            };
            getSystemContext().addBundleListener(listener);

            // 1. start the importer on another thread, its activator's start() blocks for 3 s while the START lock of the
            // importer is held (only the bundle lock, the importer is resolved)
            Thread starter = new Thread(new Runnable() {
                public void run() {
                    try {
                        importer.start();
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            });
            starter.start();
            Thread.sleep(500);

            // 2. refresh the exporter: the refresh thread computes its stop list (exporter RESOLVED) and then waits for the
            // lock of the importer, which is in the dependency closure
            final CountDownLatch latch = new CountDownLatch(1);
            getSystemContext().getBundle().adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(exporter), new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                    latch.countDown();
                }
            });
            Thread.sleep(500);

            // 3. start the exporter while the refresh waits
            exporter.start();
            assertEquals("Precondition: exporter ACTIVE before the refresh proceeds", Bundle.ACTIVE, exporter.getState());

            assertTrue("Refresh completes", latch.await(30, TimeUnit.SECONDS));
            starter.join(10000);
            getSystemContext().removeBundleListener(listener);

            assertTrue("The ACTIVE exporter must be stopped by the refresh, events: " + events, events.contains("STOPPING") && events.contains("STOPPED"));
            assertEquals("The exporter must be ACTIVE again after the refresh, events: " + events, Bundle.ACTIVE, exporter.getState());
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private static String name(int type) {
        switch (type) {
        case BundleEvent.INSTALLED:
            return "INSTALLED";
        case BundleEvent.RESOLVED:
            return "RESOLVED";
        case BundleEvent.UNRESOLVED:
            return "UNRESOLVED";
        case BundleEvent.STARTING:
            return "STARTING";
        case BundleEvent.STARTED:
            return "STARTED";
        case BundleEvent.STOPPING:
            return "STOPPING";
        case BundleEvent.STOPPED:
            return "STOPPED";
        default:
            return "EVENT" + type;
        }
    }

    public static class SlowStartActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            Thread.sleep(3000);
        }

        public void stop(BundleContext context) {
        }
    }

    private Bundle install(String symbolicName, String header, String value, Class<?> activator) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(header, value);
        if (activator != null)
            attributes.putValue(Constants.BUNDLE_ACTIVATOR, activator.getName());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        if (activator != null) {
            String path = activator.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(path));
            InputStream in = getClass().getClassLoader().getResourceAsStream(path);
            byte[] buffer = new byte[4096];
            for (int len = in.read(buffer); len > 0; len = in.read(buffer))
                jar.write(buffer, 0, len);
            in.close();
            jar.closeEntry();
        }
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
