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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Lock order inversion between the resolver and the framework wiring lock: FrameworkResolverImpl.resolveAndApply
 * holds the resolver monitor while it waits for the FrameworkWiringLock, and the lifecycle operations (start of an
 * unresolved bundle, stop, update, refresh) hold the FrameworkWiringLock while they enter resolveAndApply. Two such
 * calls on different threads deadlock until the 30 second lock timeout, and then one of them fails with an unchecked
 * LockException ("Cannot obtain lock in timely fashion").
 *
 * <h3>Severity</h3>
 * major - concurrent bundle lifecycle operations (e.g. deployments starting bundles while another thread resolves)
 * stall for 30 seconds and fail with a RuntimeException that the OSGi API does not declare.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkResolverImpl} (synchronized
 * {@code resolveAndApply}), {@code FrameworkExtensions.resolve}, {@code LockManagerImpl}, {@code BundleManagerPlugin}
 * lockable items.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful multi-threaded API fuzzer ({@code Stateful}, 4 and 8 worker threads doing random
 * install/start/stop/update/resolve/refresh): workers got
 * {@code LockException: JBOSGI011282: Cannot obtain lock in timely fashion} from resolveBundles, start, stop, update,
 * uninstall and refresh, and some operations hung for more than 90 s. A thread dump showed a worker in
 * {@code FrameworkResolverImpl.resolveAndApply} (holding the resolver monitor) waiting in
 * {@code LockManagerImpl.lockItemsInternal} for the wiring lock, and a worker in {@code BundleManagerPlugin.startBundle}
 * (holding the wiring lock) blocked on the resolver monitor in {@code resolveAndApply}. This test makes the
 * interleaving deterministic with a BundleActivator.stop callback, which runs while Bundle.stop holds the wiring lock.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.resolveBundles javadoc (OSGi Core R8, 10.4.8.5): "Returns true if all specified bundles are
 * resolved; false otherwise." - only IllegalArgumentException (foreign bundles) and SecurityException are declared.
 * Bundle.start/stop javadoc (10.1.5.33, 10.1.5.35): failures are reported with a BundleException; 10.1.5 Bundle and
 * 10.4.8 FrameworkWiring are specified as "Concurrency Thread-safe".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:115}
 * ({@code public synchronized Map<Resource, List<Wire>> resolveAndApply}) calls {@code FrameworkExtensions.resolve()}
 * ({@code FrameworkExtensions.java:188}: {@code lockManager.lockItems(Method.RESOLVE, FrameworkWiringLock)}, added
 * with the R7 framework extensions, commit 8d863429) and later locks the wiring lock again for the resolve itself;
 * {@code BundleManagerPlugin.java:708-725} gives START (of an unresolved bundle), STOP, UPDATE, REFRESH, UNINSTALL and
 * RESOLVE the FrameworkWiringLock, taken before resolveAndApply is entered.
 * {@code FrameworkWiringImpl.resolveBundles} (:268-270) enters resolveAndApply without taking the wiring lock first.
 * {@code LockManagerImpl.java:79-81, 105} waits 30 s and then throws {@code LockException} (a RuntimeException).
 *
 * <h3>Root cause</h3>
 * Two lock acquisition orders (resolver monitor then wiring lock; wiring lock then resolver monitor).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ResolverWiringLockOrderIssueTestCase} (in jbosgi-framework; the test takes about 30 s).
 *
 * <h3>Observed</h3>
 * <pre>
 * testConcurrentResolveDuringStop (after 30.7 s): resolveBundles on the other thread must not fail, but threw:
 *   org.jboss.osgi.framework.spi.LockException: JBOSGI011282: Cannot obtain lock in timely fashion: (RESOLVE)
 *   [FrameworkWiringLock]
 * Fuzzer thread dump (4 workers): fz-worker-3 in LockManagerImpl.lockItemsInternal &lt;- FrameworkExtensions.resolve:188
 *   &lt;- FrameworkResolverImpl.resolveInternal:135 &lt;- resolveAndApply:116 (locked FrameworkResolverImpl);
 *   fz-worker-1 waiting to lock FrameworkResolverImpl in resolveAndApply:116 &lt;- BundleManagerPlugin.resolveBundle:594
 *   &lt;- AbstractBundleState.ensureResolved &lt;- UserBundleState.startInternal &lt;- BundleManagerPlugin.startBundle:612
 *   (holding the START lock incl. FrameworkWiringLock)
 * </pre>
 *
 * <h3>Expected</h3>
 * Both resolveBundles calls complete without delay and without exception.
 *
 * <h3>Reference behaviour</h3>
 * Not compared (the deadlock is specific to the jbosgi locking scheme).
 *
 * <h3>Suggested fix</h3>
 * Use one lock order: take the FrameworkWiringLock in FrameworkWiringImpl.resolveBundles (and every other entry to the
 * resolver) before the resolver monitor, or drop the {@code synchronized} on resolveAndApply in favour of the wiring
 * lock.
 *
 * <h3>Regression vs WF41</h3>
 * no for the inversion itself (WF41 also takes the wiring lock inside the synchronized resolveAndApply); the R7
 * extension resolve adds a second acquisition at the very start of every resolve.
 *
 * @since 13-Oct-2026
 */
public class ResolverWiringLockOrderIssueTestCase extends OSGiFrameworkTest {

    // The activator runs in the bundle's class loader, the test and the activator communicate through system properties
    private static final String REQUESTER = "lock-order.requester";
    private static final String FAILURE = "lock-order.other-failure";
    private static final String MILLIS = "lock-order.callback-millis";

    @Test
    public void testConcurrentResolveDuringStop() throws Exception {
        Bundle bundle = install("lock-order-stopper", true);
        Bundle requester = install("lock-order-requester", false);
        System.setProperty(REQUESTER, "" + requester.getBundleId());
        System.clearProperty(FAILURE);
        System.clearProperty(MILLIS);
        try {
            bundle.start();
            long start = System.currentTimeMillis();
            bundle.stop();
            long stopMillis = System.currentTimeMillis() - start;
            assertNull("resolveBundles on the other thread must not fail, but threw: " + System.getProperty(FAILURE), System.getProperty(FAILURE));
            long callbackMillis = Long.parseLong(System.getProperty(MILLIS, "-1"));
            assertTrue("resolveBundles in BundleActivator.stop must not wait for a lock timeout, it took " + callbackMillis + " ms",
                    callbackMillis >= 0 && callbackMillis < 10000);
            assertTrue("Bundle.stop took " + stopMillis + " ms", stopMillis < 10000);
        } finally {
            bundle.uninstall();
            requester.uninstall();
        }
    }

    public static class ResolvingActivator implements BundleActivator {
        public void start(BundleContext context) {
        }

        public void stop(BundleContext context) throws Exception {
            final FrameworkWiring wiring = context.getBundle(0).adapt(FrameworkWiring.class);
            final Bundle requester = context.getBundle(Long.parseLong(System.getProperty("lock-order.requester")));
            Thread other = new Thread(new Runnable() {
                public void run() {
                    try {
                        wiring.resolveBundles(Collections.singleton(requester));
                    } catch (Throwable th) {
                        System.setProperty("lock-order.other-failure", th.toString());
                    }
                }
            }, "lock-order-other");
            other.start();
            // let the other thread enter the resolver and wait for the wiring lock that this stop holds
            Thread.sleep(2000);
            long start = System.currentTimeMillis();
            wiring.resolveBundles(Collections.singleton(requester));
            System.setProperty("lock-order.callback-millis", "" + (System.currentTimeMillis() - start));
            other.join(60000);
        }
    }

    private Bundle install(String symbolicName, boolean activator) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (activator) {
            attributes.putValue(Constants.BUNDLE_ACTIVATOR, ResolvingActivator.class.getName());
            attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework,org.osgi.framework.wiring");
        } else {
            // never resolvable, so that resolveBundles really runs the resolver
            attributes.putValue(Constants.IMPORT_PACKAGE, "org.acme.does.not.exist");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        if (activator) {
            for (String name : new String[] { ResolvingActivator.class.getName(), ResolvingActivator.class.getName() + "$1" }) {
                String path = name.replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(path));
                InputStream in = getClass().getClassLoader().getResourceAsStream(path);
                byte[] buffer = new byte[4096];
                for (int len = in.read(buffer); len > 0; len = in.read(buffer))
                    jar.write(buffer, 0, len);
                in.close();
                jar.closeEntry();
            }
        }
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
