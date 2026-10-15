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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * When a lifecycle operation cannot get the bundle's state change lock in time (another thread is starting the
 * bundle and its activator takes long), Bundle.stop/start/update/uninstall throw the framework-internal unchecked
 * {@code org.jboss.osgi.framework.spi.LockException} instead of the BundleException that the API specifies for this
 * case.
 *
 * <h3>Severity</h3>
 * minor. The callers' error handling (catch BundleException, e.g. management agents, deployers, the WildFly
 * subsystem's bundle operations, BundleTracker-based extenders) does not see the failure; an unexpected
 * RuntimeException propagates instead, out of event handlers or worker threads. The 30 s timeout is reached in
 * practice by slow activators and by the lock-order inversions fuzz/16 and fuzz/22 (and concurrency/05).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.LockManagerImpl.lockItemsInternal} and its callers
 * {@code BundleManagerPlugin.startBundle/stopBundle/updateBundle/uninstallBundle}.
 *
 * <h3>Found by</h3>
 * concurrency workstream (review of the LockManager timeout path while building the lock graph:
 * {@code MESSAGES.cannotObtainLockTimely} is a {@code LockException extends RuntimeException}, and no caller of
 * {@code lockItems} translates it); made deterministic with a BundleActivator.start that waits until the test has
 * collected the results.
 *
 * <h3>Spec reference</h3>
 * Bundle.stop(int) javadoc (osgi.core 8.0.0, 10.1.5.35): "If this bundle is in the process of being activated or
 * deactivated then this method must wait for activation or deactivation to complete before continuing. If this does
 * not occur in a reasonable time, a BundleException is thrown to indicate this bundle was unable to be stopped."; the
 * same sentence for start (10.1.5.33: "... a BundleException is thrown to indicate this bundle was unable to be
 * started"); Bundle.update and uninstall: "Throws BundleException - If ... this bundle could not be updated/uninstalled";
 * BundleException.STATECHANGE_ERROR (10.1.9.11): "The operation failed to complete the requested lifecycle state
 * change." Only BundleException, IllegalStateException and SecurityException are declared.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/LockManagerImpl.java:98-100} ({@code throw
 * MESSAGES.cannotObtainLockTimely(new TimeoutException(), context)} after 30 s) and {@code :106-108} (the same for an
 * interrupt); {@code core/src/main/java/org/jboss/osgi/framework/spi/LockException.java} ({@code extends
 * RuntimeException}); {@code BundleManagerPlugin.java:610, 631, 646, 662} ({@code lockManager.lockItems(...)} in
 * startBundle/stopBundle/updateBundle/uninstallBundle, outside their {@code try} blocks, no translation).
 * Pre-existing (WF41 has the same LockManager and callers).
 *
 * <h3>Root cause</h3>
 * The lock timeout is reported with an unchecked framework SPI exception that the lifecycle methods let through
 * instead of mapping it to {@code BundleException(STATECHANGE_ERROR)}.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundleLockTimeoutExceptionTypeIssueTestCase
 * </pre>
 * Bundle X is resolved; its BundleActivator.start waits (at most 60 s) until the test has the results. While X is
 * STARTING, three threads call X.stop(), X.update() and X.uninstall(); each must wait and then fail with a
 * BundleException. The test takes about 31 s (the LockManager timeout is a fixed 30 s).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: stop/update/uninstall of a bundle whose activator does not return in time must fail with
 * BundleException (STATECHANGE_ERROR), but got [stop: org.jboss.osgi.framework.spi.LockException: JBOSGI011282: Cannot
 * obtain lock in timely fashion: (STOP) [FrameworkWiringLock, concurrency06.x:0.0.0], update: ...LockException ...
 * (UPDATE) ..., uninstall: ...LockException ... (UNINSTALL) ...]
 * </pre>
 * 2 of 2 runs failed (JDK 25), each after 30.5 s.
 *
 * <h3>Expected</h3>
 * Each call throws a {@code BundleException} (type STATECHANGE_ERROR) after the framework's timeout.
 *
 * <h3>Reference behaviour</h3>
 * Equinox throws {@code BundleException} of type STATECHANGE_ERROR ("Unable to acquire the state change lock for the
 * module", after equinox.statechange.timeout, default 5 s). Felix waits for the state change without a timeout.
 * (Code reading of Equinox Module.lockStateChange and Felix Felix.acquireBundleLock; not run as a differential because
 * Felix does not time out.)
 *
 * <h3>Suggested fix</h3>
 * Catch LockException in BundleManagerPlugin.startBundle/stopBundle/updateBundle/uninstallBundle (and wherever a
 * BundleException is declared) and throw {@code new BundleException(message, BundleException.STATECHANGE_ERROR, ex)};
 * for FrameworkWiring.resolveBundles/refreshBundles see fuzz/16 and fuzz/23. Verified with a trial patch
 * (issues/concurrency/06-.../fix.diff, the translation in the four BundleManagerPlugin methods): the test passes.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * fuzz/16 ResolverWiringLockOrderIssueTestCase (its lock-order inversion ends in this LockException), fuzz/22,
 * concurrency/05 ActivatorUnderGlobalWiringLockIssueTestCase.
 */
public class BundleLockTimeoutExceptionTypeIssueTestCase extends OSGiTest {

    private Framework framework;
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void tearDown() throws Exception {
        release.countDown();
        if (framework != null) {
            framework.stop();
            framework.waitForStop(60000);
        }
    }

    @Test
    public void testLifecycleOperationsTimingOutWhileStarting() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency06-lock-timeout").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext context = framework.getBundleContext();

        final Bundle x = context.installBundle("concurrency06-x", bundle());
        assertTrue("precondition: X resolves", framework.adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(x)));

        // X's activator start waits until the test has collected the results
        final CountDownLatch inActivator = new CountDownLatch(1);
        Hashtable<String, Object> callbackProps = new Hashtable<String, Object>();
        callbackProps.put("concurrency06", "start");
        context.registerService(Runnable.class, new Runnable() {
            public void run() {
                inActivator.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, callbackProps);
        Thread starting = new Thread(new Runnable() {
            public void run() {
                try {
                    x.start();
                } catch (BundleException ex) {
                    // not part of the test
                }
            }
        }, "concurrency06-start-X");
        starting.setDaemon(true);
        starting.start();
        assertTrue("X's activator must be called", inActivator.await(30, TimeUnit.SECONDS));

        final List<String> results = Collections.synchronizedList(new ArrayList<String>());
        final List<String> details = Collections.synchronizedList(new ArrayList<String>());
        List<Thread> threads = new ArrayList<Thread>();
        threads.add(operation("stop", results, details, new Op() {
            public void run() throws Exception {
                x.stop();
            }
        }));
        threads.add(operation("update", results, details, new Op() {
            public void run() throws Exception {
                x.update(bundle());
            }
        }));
        threads.add(operation("uninstall", results, details, new Op() {
            public void run() throws Exception {
                x.uninstall();
            }
        }));
        for (Thread thread : threads)
            thread.join(45000);
        release.countDown();

        List<String> expected = new ArrayList<String>();
        for (String op : new String[] { "stop", "update", "uninstall" })
            expected.add(op + ": BundleException type " + BundleException.STATECHANGE_ERROR);
        List<String> actual = new ArrayList<String>(results);
        Collections.sort(expected);
        Collections.sort(actual);
        assertEquals("stop/update/uninstall of a bundle whose activator does not return in time must fail with BundleException (STATECHANGE_ERROR), but got "
                + details, expected, actual);
    }

    interface Op {
        void run() throws Exception;
    }

    private static Thread operation(final String name, final List<String> results, final List<String> details, final Op op) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    op.run();
                    results.add(name + ": returned normally");
                    details.add(name + ": returned normally");
                } catch (BundleException ex) {
                    results.add(name + ": BundleException type " + ex.getType());
                    details.add(name + ": BundleException type " + ex.getType() + " " + ex.getMessage());
                } catch (Throwable th) {
                    results.add(name + ": " + th.getClass().getName());
                    details.add(name + ": " + th);
                }
            }
        }, "concurrency06-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static InputStream bundle() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "concurrency06.x");
        archive.addClasses(WaitingActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("concurrency06.x");
                builder.addBundleActivator(WaitingActivator.class.getName());
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    /** Runs the test's callback service (concurrency06=start) in BundleActivator.start */
    public static class WaitingActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            ServiceReference<?>[] refs = context.getServiceReferences(Runnable.class.getName(), "(concurrency06=start)");
            if (refs != null)
                ((Runnable) context.getService(refs[0])).run();
        }

        public void stop(BundleContext context) {
        }
    }
}
