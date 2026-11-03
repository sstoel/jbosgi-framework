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
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.awaitWaitingForLock;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.bundle;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hook;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hookedBundle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.launch.Framework;

/**
 * Bundle.update and Bundle.uninstall of the same bundle on two threads: when the uninstall takes the locks first, the
 * update that waited for them updates the uninstalled bundle. It creates a storage directory for it again, fires
 * UPDATED and leaves a bundle object in the state INSTALLED that the framework does not know.
 *
 * <h2>Severity</h2>
 * minor. A stray storage directory and a bundle object that looks installed; the uninstall is not undone for the
 * framework.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.updateBundle}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-11.
 *
 * <h2>Spec reference</h2>
 * Bundle.update: IllegalStateException if the bundle has been uninstalled.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleState.java:536-539 checks the state once, before the
 * update waits for its locks; BundleManagerPlugin.java:973-980 does not check it again (84ac5c2e).
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=UpdateRacingUninstallIssueTestCase
 * </pre>
 * The uninstall stops the bundle first; its activator waits until the update waits for the lock of the bundle. Which of
 * the two gets the lock next varies, the test repeats until the uninstall was first.
 *
 * <h2>Expected</h2>
 * The update throws IllegalStateException, fires no UPDATED event, the bundle stays UNINSTALLED, it has no storage and
 * it is not installed after a restart of the framework.
 *
 * <h2>Regression vs WF41</h2>
 * no; the window is wider since the fix of concurrency/05.
 */
public class UpdateRacingUninstallIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
        ReleaseCheckSupport.clearHooks();
    }

    @Test
    public void testUpdateAfterUninstall() throws Exception {
        File storage = ReleaseCheckSupport.storage("update-racing-uninstall");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true);
        framework.start();
        BundleContext context = framework.getBundleContext();

        String verdict = null;
        long uninstalledId = -1;
        for (int round = 0; round < 20 && verdict == null; round++) {
            final String name = "update.racing.uninstall." + round;
            final CountDownLatch inStop = new CountDownLatch(1);
            final CountDownLatch releaseStop = new CountDownLatch(1);
            hook("stop", name, ctx -> {
                inStop.countDown();
                try {
                    releaseStop.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            final Bundle bundle = context.installBundle(name, hookedBundle(name, Constants.BUNDLE_VERSION, "1.0"));
            bundle.start();
            long id = bundle.getBundleId();
            final StringBuffer order = new StringBuffer();
            SynchronousBundleListener listener = new SynchronousBundleListener() {
                public void bundleChanged(BundleEvent event) {
                    if (event.getBundle() == bundle && (event.getType() == BundleEvent.UPDATED || event.getType() == BundleEvent.UNINSTALLED))
                        order.append(event.getType() == BundleEvent.UPDATED ? "UPDATED " : "UNINSTALLED ");
                }
            };
            context.addBundleListener(listener);

            final AtomicReference<Throwable> updateFailure = new AtomicReference<Throwable>();
            Thread uninstaller = new Thread(() -> {
                try {
                    bundle.uninstall();
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            }, "update-racing-uninstall-u");
            Thread updater = new Thread(() -> {
                try {
                    bundle.update(bundle(name, Constants.BUNDLE_VERSION, "2.0"));
                } catch (Throwable th) {
                    updateFailure.set(th);
                }
            }, "update-racing-uninstall-t");

            // The uninstall stops the bundle, the update waits for the lock of the bundle meanwhile
            uninstaller.start();
            await(inStop, "the uninstall stops the bundle");
            updater.start();
            assertTrue("Precondition: the update waits for the lock of the bundle", awaitWaitingForLock(updater, 10000));
            releaseStop.countDown();
            uninstaller.join(30000);
            updater.join(30000);
            context.removeBundleListener(listener);

            if (order.toString().startsWith("UNINSTALLED")) {
                uninstalledId = id;
                File dir = ReleaseCheckSupport.bundleDir(storage, id);
                verdict = "round " + round + ": events " + order + ", update threw " + updateFailure.get() + ", state " + bundle.getState()
                        + ", storage " + (dir.exists() ? "exists" : "gone") + ", in the framework " + (context.getBundle(id) != null);
                assertTrue("The update fails with IllegalStateException: " + verdict, updateFailure.get() instanceof IllegalStateException);
                assertEquals("No UPDATED event: " + verdict, "UNINSTALLED ", order.toString());
                assertEquals("The bundle stays UNINSTALLED: " + verdict, Bundle.UNINSTALLED, bundle.getState());
                assertFalse("The uninstalled bundle has no storage: " + verdict, dir.exists());
            } else if (bundle.getState() != Bundle.UNINSTALLED) {
                bundle.uninstall();
            }
        }
        assertTrue("Precondition: the uninstall took the lock first in one of the rounds", verdict != null);

        ReleaseCheckSupport.stop(framework);
        framework = ReleaseCheckSupport.newFramework(storage, false);
        framework.start();
        assertNull("The uninstalled bundle is not installed after a restart: " + verdict, framework.getBundleContext().getBundle(uninstalledId));
    }
}
