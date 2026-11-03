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
package org.jboss.test.osgi.framework.issues;

import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.await;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.bundle;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hook;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hookedBundle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.jboss.test.osgi.framework.issues.hooks.p.HookP;
import org.jboss.test.osgi.framework.issues.hooks.x.HookX;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * FrameworkWiring.refreshBundles computes the dependency closure in the caller, before the refresh has its locks, and
 * never again. A bundle that is resolved against a bundle of the closure in between is not refreshed: it stays ACTIVE
 * with its wires to the revision that the refresh removed, and it sees other classes than the refreshed bundles.
 *
 * <h2>Severity</h2>
 * major. A plain {@code refreshBundles(P); X.start()} from one thread leaves X with stale wires in most rounds.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkWiringImpl.refreshBundles}, {@code refreshBundlesLocked}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-01 (residual of regression 4 of the R8 fix review).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 10.3.10 FrameworkWiring.refreshBundles: the refresh stops, unresolves and resolves the dependency closure
 * of the bundles; a bundle that is wired to a bundle of the refresh is part of that closure.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:121-149 computes the closure, :207 locks
 * that list without computing it again (84ac5c2e).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=RefreshStaleClosureIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * After the refresh every bundle that is wired to the refreshed exporter is wired to its current revision and loads the
 * class that the exporter loads.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class RefreshStaleClosureIssueTestCase extends OSGiTest {

    private static final String PKG = HookP.class.getPackage().getName();
    private final CountDownLatch inActivator = new CountDownLatch(1);
    private final CountDownLatch releaseActivator = new CountDownLatch(1);
    private Framework framework;

    @After
    public void tearDown() throws Exception {
        releaseActivator.countDown();
        ReleaseCheckSupport.stop(framework);
        ReleaseCheckSupport.clearHooks();
    }

    @Test
    public void testBundleResolvedWhileRefreshWaits() throws Exception {
        BundleContext context = startFramework("stale-closure");
        Bundle p = context.installBundle("stale-closure-p", bundle("stale.closure.p", new Class<?>[] { HookP.class }, Constants.EXPORT_PACKAGE, PKG));
        hook("start", "stale.closure.a", ctx -> {
            inActivator.countDown();
            try {
                releaseActivator.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        Bundle a = context.installBundle("stale-closure-a", hookedBundle("stale.closure.a", Constants.IMPORT_PACKAGE, PKG));
        Bundle x = context.installBundle("stale-closure-x", bundle("stale.closure.x", new Class<?>[] { HookX.class }, Constants.IMPORT_PACKAGE, PKG));
        p.start();

        // A is wired to P, the start of A holds the lock of A but not the wiring lock while its activator runs
        Thread starter = new Thread(() -> {
            try {
                a.start();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, "stale-closure-start-a");
        starter.start();
        await(inActivator, "the activator of A runs");

        // The closure of the refresh is {P, A}: the refresh waits for the lock of A
        CountDownLatch refreshed = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(p), refreshedListener(refreshed));
        Thread refreshThread = null;
        for (int i = 0; i < 2000 && refreshThread == null; i++) {
            refreshThread = findRefreshWaitingForLock();
            Thread.sleep(5);
        }
        assertTrue("Precondition: the refresh waits for the lock of A", refreshThread != null);

        // X is resolved against the revision of P that the refresh replaces
        x.start();
        assertEquals("Precondition: X starts while the refresh waits", Bundle.ACTIVE, x.getState());

        releaseActivator.countDown();
        await(refreshed, "PACKAGES_REFRESHED");
        starter.join(30000);

        assertEquals("X is started again by the refresh", Bundle.ACTIVE, x.getState());
        assertCurrent(x, p);
    }

    @Test
    public void testRefreshThenStartFromOneThread() throws Exception {
        int staleRounds = 0;
        String first = null;
        for (int round = 0; round < 30; round++) {
            BundleContext context = startFramework("stale-closure-plain");
            Bundle p = context.installBundle("stale-closure-plain-p", bundle("stale.closure.plain.p", new Class<?>[] { HookP.class }, Constants.EXPORT_PACKAGE, PKG));
            Bundle x = context.installBundle("stale-closure-plain-x", bundle("stale.closure.plain.x", new Class<?>[] { HookX.class }, Constants.IMPORT_PACKAGE, PKG));
            p.start();
            CountDownLatch refreshed = new CountDownLatch(1);
            framework.adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(p), refreshedListener(refreshed));
            x.start();
            await(refreshed, "PACKAGES_REFRESHED");
            String stale = getStaleWire(x, p);
            if (stale != null) {
                staleRounds++;
                if (first == null)
                    first = "round " + round + ": " + stale;
            }
            ReleaseCheckSupport.stop(framework);
            framework = null;
        }
        assertEquals("Rounds in which X kept a wire to the removed revision of P, the first: " + first, 0, staleRounds);
    }

    private BundleContext startFramework(String name) throws Exception {
        File storage = ReleaseCheckSupport.storage(name);
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true);
        framework.start();
        return framework.getBundleContext();
    }

    private static FrameworkListener refreshedListener(final CountDownLatch refreshed) {
        return new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.PACKAGES_REFRESHED)
                    refreshed.countDown();
            }
        };
    }

    // The thread of the refresh, when it waits for a lock
    private static Thread findRefreshWaitingForLock() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            boolean refresh = false;
            for (StackTraceElement element : thread.getStackTrace())
                refresh |= element.getClassName().endsWith("FrameworkWiringImpl") && element.getMethodName().startsWith("refreshBundles");
            if (refresh && ReleaseCheckSupport.isWaitingForLock(thread))
                return thread;
        }
        return null;
    }

    private static void assertCurrent(Bundle x, Bundle p) throws Exception {
        String stale = getStaleWire(x, p);
        assertTrue("X keeps a wire to the removed revision of P: " + stale, stale == null);
    }

    // A description of the stale wire of X, null when X is wired to the current revision of P
    private static String getStaleWire(Bundle x, Bundle p) throws Exception {
        BundleWiring wiring = x.adapt(BundleWiring.class);
        if (wiring == null)
            return "X has no wiring, state " + x.getState();
        for (BundleWire wire : wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (wire.getProviderWiring().isCurrent() == false)
                return "wire to " + wire.getProvider() + ", whose wiring is not current, X state " + x.getState();
        }
        if (x.loadClass(HookP.class.getName()) != p.loadClass(HookP.class.getName()))
            return "X loads another class " + HookP.class.getSimpleName() + " than P";
        return null;
    }
}
