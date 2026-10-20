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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.issues.refresh.CallbackActivator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A refresh holds the locks of the bundles of its dependency closure and then waits for the framework wiring lock, while
 * the start of an unresolved bundle holds the wiring lock and its activator waits for the lock of a bundle of the closure.
 * Both threads wait for each other until the 30 s lock timeout.
 *
 * <h2>Severity</h2>
 * critical. The activator's lifecycle call and the refresh stall for 30 s; when the refresh loses, an ACTIVE bundle of the
 * closure is left INSTALLED and only an ERROR event shows it.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkWiringImpl.refreshBundlesLocked}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 3 (fw-small-01).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:209, 225, 284 (169c8c00).
 *
 * <h2>Root cause</h2>
 * The refresh locked the bundles of the closure and then, in a second call of the lock manager, the wiring lock for the
 * stop and for the start of the bundles: the opposite order of the lifecycle operations, which lock the wiring lock and
 * their bundle together and then call activators.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=RefreshLockOrderInversionIssueTestCase
 * </pre>
 * The UNRESOLVED event of Z, fired by the refresh, starts the unresolved bundle Y on another thread; Y's activator stops
 * Z once the refresh waits for a lock.
 *
 * <h2>Expected</h2>
 * Z.stop() from Y's activator does not wait for the refresh, and the refresh starts W, an ACTIVE bundle of the closure,
 * again.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 169c8c00.
 */
public class RefreshLockOrderInversionIssueTestCase extends OSGiTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(30000);
        }
    }

    @Test
    public void testRefreshVersusStartOfUnresolvedBundle() throws Exception {
        BundleContext context = framework.getBundleContext();
        final Bundle z = install(context, "refresh-lock-z", false);
        final Bundle w = install(context, "refresh-lock-w", false);
        final Bundle y = install(context, "refresh-lock-y", true);
        z.start();
        w.start();
        assertEquals("Precondition: Y is unresolved", Bundle.INSTALLED, y.getState());

        final CountDownLatch activatorEntered = new CountDownLatch(1);
        final AtomicReference<String> zStopResult = new AtomicReference<String>("Y's activator did not run");
        final AtomicReference<Thread> yThread = new AtomicReference<Thread>();
        final Thread refreshThread[] = new Thread[1];

        // Y's activator waits until the refresh waits for a lock, then stops Z
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("issues.callback", "refresh-lock-y");
        context.registerService(Runnable.class, new Runnable() {
            public void run() {
                activatorEntered.countDown();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && !isWaitingForLock(refreshThread[0]))
                    sleep(20);
                long start = System.currentTimeMillis();
                try {
                    z.stop();
                    zStopResult.set("Z.stop() returned after " + (System.currentTimeMillis() - start) + " ms");
                } catch (Throwable th) {
                    zStopResult.set("Z.stop() failed after " + (System.currentTimeMillis() - start) + " ms: " + th);
                }
            }
        }, props);

        final AtomicBoolean triggered = new AtomicBoolean();
        context.addBundleListener(new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.UNRESOLVED && event.getBundle() == z && triggered.compareAndSet(false, true)) {
                    refreshThread[0] = Thread.currentThread();
                    Thread thread = new Thread(new Runnable() {
                        public void run() {
                            try {
                                y.start();
                            } catch (Throwable th) {
                                zStopResult.compareAndSet("Y's activator did not run", "Y.start() failed: " + th);
                            }
                        }
                    }, "refresh-lock-start-y");
                    thread.setDaemon(true);
                    yThread.set(thread);
                    thread.start();
                    try {
                        activatorEntered.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });

        final CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<String>();
        context.addFrameworkListener(new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR)
                    errors.add(event.getBundle().getSymbolicName() + ": " + event.getThrowable());
            }
        });
        final CountDownLatch refreshed = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(Arrays.asList(z, w), new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                refreshed.countDown();
            }
        });
        assertTrue("The refresh finished", refreshed.await(60, TimeUnit.SECONDS));
        Thread thread = yThread.get();
        if (thread != null)
            thread.join(60000);

        String info = zStopResult.get() + "; W state=" + w.getState() + "; Y state=" + y.getState() + "; ERROR events=" + errors;
        assertTrue("Z.stop() from Y's activator must not wait for the refresh: " + info, zStopResult.get().startsWith("Z.stop() returned")
                && Long.parseLong(zStopResult.get().replaceAll("\\D+", " ").trim().split(" ")[0]) < 5000);
        assertEquals("The refresh starts W again: " + info, Bundle.ACTIVE, w.getState());
        assertTrue("No ERROR events: " + info, errors.isEmpty());
    }

    // The thread waits in the lock manager
    private static boolean isWaitingForLock(Thread thread) {
        if (thread == null || thread.isAlive() == false)
            return false;
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING)
            return false;
        for (StackTraceElement element : thread.getStackTrace()) {
            if (element.getClassName().endsWith("LockManagerImpl") && element.getMethodName().equals("lockItemsInternal"))
                return true;
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Bundle install(BundleContext context, final String name, final boolean activator) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (activator)
            archive.addClasses(CallbackActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (activator) {
                    builder.addBundleActivator(CallbackActivator.class);
                    builder.addImportPackages("org.osgi.framework");
                }
                return builder.openStream();
            }
        });
        return context.installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }
}
