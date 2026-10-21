/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;

/**
 * A bundle whose start began before the framework stopped, and that was still resolved when the stop stopped the
 * active bundles, is activated in the stopping framework. Its activator is never stopped.
 *
 * <h2>Severity</h2>
 * minor. A narrow window, the activator of the bundle keeps running after the framework stopped.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code UserBundleState.startInternalNow}, {@code BundleManagerPlugin.isFrameworkStopping}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of shutdown/08 (fw-small-04).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.2.7 Framework.stop: all installed bundles are stopped, no bundle is active after the stop.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:698 and :743 (fa32e5ab).
 *
 * <h2>Root cause</h2>
 * The start checked whether the framework stops before it resolved the bundle. The stop only stops bundles that are
 * STARTING or ACTIVE, a bundle that became STARTING after that was missed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=StartResolvingDuringShutdownIssueTestCase
 * </pre>
 * A resolver hook holds the start of X in its resolve until the stop has stopped bundle C. An asynchronous listener
 * keeps the framework from completing its stop until the start of X returned.
 *
 * <h2>Expected</h2>
 * X is not activated, or its activator is stopped again.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class StartResolvingDuringShutdownIssueTestCase {

    static final String COUNTERS = "issues.shutdown.startresolving.counters";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
        System.getProperties().remove(COUNTERS);
    }

    @Test
    public void testStartThatResolvesWhileTheFrameworkStops() throws Exception {
        final AtomicInteger[] counters = new AtomicInteger[] { new AtomicInteger(), new AtomicInteger() };
        System.getProperties().put(COUNTERS, counters);

        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/shutdown-start-resolving").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext context = framework.getBundleContext();

        final Bundle bundleC = context.installBundle("start-resolving-c", bundle("start-resolving-c", false));
        bundleC.start();
        final Bundle bundleX = context.installBundle("start-resolving-x", bundle("start-resolving-x", true));

        final CountDownLatch inResolve = new CountDownLatch(1);
        final CountDownLatch cStopped = new CountDownLatch(1);
        final CountDownLatch xStartReturned = new CountDownLatch(1);

        // The stop waits for the delivery of the asynchronous events until the start of X returned
        context.addBundleListener(new BundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().equals(bundleC) && event.getType() == BundleEvent.STOPPED) {
                    cStopped.countDown();
                    await(xStartReturned, 10);
                }
            }
        });

        // The resolve of X waits until the stop has stopped the active bundles
        final AtomicReference<Thread> starter = new AtomicReference<Thread>();
        context.registerService(ResolverHookFactory.class, new ResolverHookFactory() {
            @Override
            public ResolverHook begin(Collection<BundleRevision> triggers) {
                if (Thread.currentThread() == starter.get() && triggers.contains(bundleX.adapt(BundleRevision.class))) {
                    inResolve.countDown();
                    await(cStopped, 10);
                    sleep(500);
                }
                return new ResolverHook() {
                    public void filterResolvable(Collection<BundleRevision> candidates) {
                    }

                    public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                    }

                    public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                    }

                    public void end() {
                    }
                };
            }
        }, null);

        final AtomicReference<String> startResult = new AtomicReference<String>("not started");
        Thread thread = new Thread("start-resolving-x") {
            @Override
            public void run() {
                try {
                    bundleX.start();
                    startResult.set("returned");
                } catch (Throwable th) {
                    startResult.set(th.toString());
                } finally {
                    xStartReturned.countDown();
                }
            }
        };
        starter.set(thread);
        thread.start();
        assertTrue("The start of X resolves X", inResolve.await(10, TimeUnit.SECONDS));

        framework.stop();
        thread.join(30000);
        FrameworkEvent stopped = framework.waitForStop(30000);
        assertEquals("The framework stopped", FrameworkEvent.STOPPED, stopped.getType());
        assertEquals("The activator of X is not left running in the stopped framework (start of X: " + startResult.get() + ")",
                counters[0].get(), counters[1].get());
    }

    static void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static InputStream bundle(final String symbolicName, final boolean withActivator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        if (withActivator)
            archive.addClasses(CountingActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                if (withActivator) {
                    builder.addBundleActivator(CountingActivator.class.getName());
                    builder.addImportPackages("org.osgi.framework");
                }
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    /** Counts its starts and stops in the counters of the test, a JDK type in the system properties */
    public static class CountingActivator implements BundleActivator {
        public void start(BundleContext context) {
            ((AtomicInteger[]) System.getProperties().get(COUNTERS))[0].incrementAndGet();
        }

        public void stop(BundleContext context) {
            ((AtomicInteger[]) System.getProperties().get(COUNTERS))[1].incrementAndGet();
        }
    }
}
