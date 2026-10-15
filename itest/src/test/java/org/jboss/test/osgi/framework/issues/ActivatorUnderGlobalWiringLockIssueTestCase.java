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

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * BundleActivator.stop (and start of an unresolved bundle, update, uninstall) runs while the framework holds the
 * framework-wide FrameworkWiringLock, so the lifecycle operations of every other bundle (stop, update, uninstall, start
 * of an unresolved bundle, resolveBundles, refreshBundles) block until that activator returns: an activator that waits
 * for another thread which stops an unrelated bundle stalls both threads, for up to 30 s, after which the other thread
 * gets an unchecked LockException.
 *
 * <h3>Severity</h3>
 * major. The specification allows a lifecycle call to wait only for the state change of the same bundle; here every
 * bundle shares one lock for the whole duration of foreign code. Extenders and management agents commonly stop or
 * uninstall the bundles they manage from worker threads and wait for them in their own activator stop (Felix
 * FileInstall-style watchers, deployment agents, Karaf feature-like installers); in WildFly the deployment of one
 * bundle on an MSC thread waits for MSC services of other bundles, whose lifecycle runs on other MSC threads. Every
 * such wait becomes a 30 s stall followed by an exception that the API does not declare. Equinox 3.21 and Felix 7.0.5
 * let the unrelated stop complete immediately.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin} ({@code getLockableItems},
 * {@code startBundle}, {@code stopBundle}, {@code updateBundle}, {@code uninstallBundle}) and
 * {@code org.jboss.osgi.framework.internal.UserBundleState} (start/stop algorithm calling the activator).
 *
 * <h3>Found by</h3>
 * concurrency workstream: lock order recorder (bytecode agent, {@code agents/concurrency/lockrec}) over the framework
 * core + itest suites: callouts {@code BundleActivator.stop @UserBundleState.stopInternalNow:699 held=[FrameworkWiringLock,
 * bundle lock]} (33 times), {@code BundleActivator.start ... held=[FrameworkWiringLock, bundle lock]} (35 times),
 * likewise for STOPPING/STOPPED/UNRESOLVED SynchronousBundleListeners, bundle EventHooks, WeavingHooks and ServiceListeners
 * (UNREGISTERING); Equinox/Felix differential {@code explore/concurrency/diff/Diff.java c06}.
 *
 * <h3>Spec reference</h3>
 * Bundle.stop(int) javadoc (osgi.core 8.0.0, 10.1.5.35): "If this bundle is in the process of being activated or
 * deactivated then this method must wait for activation or deactivation to complete before continuing. If this does
 * not occur in a reasonable time, a BundleException is thrown to indicate this bundle was unable to be stopped." - the
 * wait is defined for this bundle's own state change only; the same for Bundle.start (10.1.5.33), update, uninstall.
 * Bundle (10.1.5): "Concurrency Thread-safe". OSGi Core R8, 4.7.3 Synchronization Pitfalls: "Calling the OSGi Framework
 * from synchronized code can cause unexpected side effects. One of these side effects might be deadlock." /
 * 10.1.31 SynchronousBundleListener: the framework holds "the referenced bundle's state change lock" - one lock per
 * bundle, not a framework-wide one.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:708-726}
 * ({@code getLockableItems}: RESOLVE, UPDATE, REFRESH, UNINSTALL, STOP and START of an unresolved bundle lock
 * {@code { wireLock, bundle }}, wireLock being the single FrameworkWiringLock of the framework), {@code :627-639}
 * ({@code stopBundle} holds the items around {@code stopInternal}) and {@code UserBundleState.java:699}
 * ({@code bundleActivator.stop(getBundleContext())} inside stopInternalNow, i.e. under both locks; the same for
 * {@code bundleActivator.start} at :615 when the bundle was not resolved). {@code LockManagerImpl.java:79-81, 100}: 30 s
 * timeout, then {@code LockException} (RuntimeException). Pre-existing: the item lists are the same on WF41.
 *
 * <h3>Root cause</h3>
 * The FrameworkWiringLock, which protects the wiring state (resolve/refresh), is also taken for the complete
 * stop/update/uninstall/start-of-unresolved operation, including the callouts to the activator and listeners, instead
 * of only around the wiring changes.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ActivatorUnderGlobalWiringLockIssueTestCase
 * </pre>
 * Two started bundles X and Y that have nothing in common. X's BundleActivator.stop calls back into the test, which
 * starts a thread that stops Y and waits up to 5 s for it, as an extender does with the bundles it manages. About 6 s.
 *
 * <h3>Observed</h3>
 * <pre>
 * org.junit.ComparisonFailure: Y.stop() on another thread must not wait for the BundleActivator.stop of the unrelated
 * bundle X, but after 5 s it was still blocked: TIMED_WAITING on org.jboss.osgi.framework.internal.LockManagerImpl@...
 * at org.jboss.osgi.framework.internal.LockManagerImpl.lockItemsInternal(LockManagerImpl.java:105)
 * expected:&lt;[Y stopped]&gt; but was:&lt;[blocked: TIMED_WAITING ...]&gt;
 * </pre>
 * The test prints both stacks (run.log): "concurrency05-stop-Y" in LockManagerImpl.lockItemsInternal &lt;-
 * BundleManagerPlugin.stopBundle:631 (waiting for the FrameworkWiringLock); "main" in the callback &lt;-
 * CallbackActivator.stop &lt;- UserBundleState.stopInternalNow:699 &lt;- BundleManagerPlugin.stopBundle:633 (holding
 * the FrameworkWiringLock and X's bundle lock). 3 of 3 runs failed (JDK 25).
 * When the activator waits longer than 30 s, Y.stop() throws
 * {@code org.jboss.osgi.framework.spi.LockException: JBOSGI011282: Cannot obtain lock in timely fashion: (STOP)
 * [FrameworkWiringLock, ...]} (see concurrency/06 for the exception type).
 *
 * <h3>Expected</h3>
 * Y.stop() completes while X's activator is still running (X and Y do not share a state change lock).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5 (Diff c06, same scenario with the wait in a SynchronousBundleListener for STOPPING of
 * X, JDK 25): "Y.stop() completed while X was stopping"; jbosgi: blocked for 5 s in LockManagerImpl.lockItemsInternal.
 *
 * <h3>Suggested fix</h3>
 * Lock only the bundle (its state change lock) for start/stop of a resolved bundle and for the activator/listener
 * callouts of stop, update and uninstall; take the FrameworkWiringLock only around the wiring changes themselves
 * (resolve of an unresolved bundle, removal of the revision/module in update/uninstall/refresh), and never while
 * foreign code runs. Verified with a minimal trial patch (issues/concurrency/05-.../fix.diff: STOP locks only the
 * bundle, like START of a resolved bundle): the test passes and the whole itest suite shows no new failure (441 tests;
 * only the 2 locale-dependent LocalizationTestCase failures of the baseline).
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * fuzz/16 ResolverWiringLockOrderIssueTestCase and fuzz/22 FallbackLoaderLockOrderIssueTestCase (lock-order inversions
 * with the same FrameworkWiringLock, both use a BundleActivator.stop as their trigger), fuzz/20
 * RefreshBundlesBlocksCallerIssueTestCase, wildfly/26 and lifecycle/04 (server operations nested inside framework
 * callouts), concurrency/06 BundleLockTimeoutExceptionTypeIssueTestCase.
 */
public class ActivatorUnderGlobalWiringLockIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(40000);
        }
    }

    @Test
    public void testUnrelatedStopDuringActivatorStop() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency05-activator-wiring-lock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext context = framework.getBundleContext();

        final Bundle y = context.installBundle("concurrency05-y", bundle("concurrency05.y", false));
        y.start();
        Bundle x = context.installBundle("concurrency05-x", bundle("concurrency05.x", true));
        x.start();

        // Called from X's BundleActivator.stop: stop the unrelated bundle Y on another thread and wait for it
        final AtomicReference<String> result = new AtomicReference<String>("activator stop not called");
        Hashtable<String, Object> callbackProps = new Hashtable<String, Object>();
        callbackProps.put("concurrency05", "stop");
        context.registerService(Runnable.class, new Runnable() {
            public void run() {
                final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            y.stop();
                        } catch (Throwable th) {
                            failure.set(th);
                        }
                    }
                }, "concurrency05-stop-Y");
                thread.setDaemon(true);
                thread.start();
                try {
                    thread.join(5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (thread.isAlive()) {
                    ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[] { thread.getId() }, true, true)[0];
                    StackTraceElement waitingAt = info.getStackTrace()[0];
                    for (StackTraceElement element : info.getStackTrace()) {
                        if (element.getClassName().startsWith("org.jboss.osgi")) {
                            waitingAt = element;
                            break;
                        }
                    }
                    result.set("blocked: " + info.getThreadState() + " on " + info.getLockName() + " at " + waitingAt);
                    // the two stacks for the report
                    System.out.println("---- blocked thread ----\n" + stack(info) + "---- thread in X's BundleActivator.stop ----\n"
                            + stack(ManagementFactory.getThreadMXBean().getThreadInfo(new long[] { Thread.currentThread().getId() }, true, true)[0]));
                } else if (failure.get() != null) {
                    result.set("failed: " + failure.get());
                } else {
                    result.set("Y stopped");
                }
            }
        }, callbackProps);

        x.stop();
        assertEquals("Y.stop() on another thread must not wait for the BundleActivator.stop of the unrelated bundle X, but after 5 s it was still "
                + result.get(), "Y stopped", result.get());
    }

    private static String stack(ThreadInfo info) {
        StringBuilder sb = new StringBuilder("\"" + info.getThreadName() + "\" " + info.getThreadState() + (info.getLockName() != null ? " on " + info.getLockName() : "") + "\n");
        for (StackTraceElement element : info.getStackTrace())
            sb.append("\tat ").append(element).append("\n");
        return sb.toString();
    }

    private static InputStream bundle(final String symbolicName, final boolean withActivator) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        if (withActivator)
            archive.addClasses(CallbackActivator.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                if (withActivator) {
                    builder.addBundleActivator(CallbackActivator.class.getName());
                    builder.addImportPackages("org.osgi.framework");
                }
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    /** Runs the test's callback service (concurrency05=stop) in BundleActivator.stop */
    public static class CallbackActivator implements BundleActivator {
        public void start(BundleContext context) {
        }

        public void stop(BundleContext context) throws Exception {
            ServiceReference<?>[] refs = context.getServiceReferences(Runnable.class.getName(), "(concurrency05=stop)");
            if (refs != null)
                ((Runnable) context.getService(refs[0])).run();
        }
    }
}
