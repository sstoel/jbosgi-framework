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

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
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
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * FrameworkWiring.refreshBundles does not return immediately: it computes the dependency closure on the caller's
 * thread under the FrameworkWiringLock, so the caller blocks for as long as any other lifecycle operation holds that
 * lock (for example a Bundle.stop whose activator is slow), and gets a LockException after 30 s.
 *
 * <h3>Severity</h3>
 * minor - callers (management operations, listeners) that rely on the asynchronous contract can be blocked or fail
 * with an undeclared RuntimeException; a refresh requested from inside a lifecycle callback of another thread's
 * operation can deadlock until the timeout.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundles}.
 *
 * <h3>Found by</h3>
 * R8 verification, delivered test; committed for the R8 release check.
 * fuzz workstream, while making the refresh race of fuzz/19 deterministic: the refreshBundles call itself blocked the
 * test thread for the 3 s that another thread's Bundle.stop held the wiring lock (timestamps of the recorded events).
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.refreshBundles javadoc (OSGi Core R8, 10.4.8.4): "This method returns to the caller immediately and
 * then performs the following steps on a separate thread: Compute the dependency closure of the specified bundles.
 * ..."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:122-139}:
 * {@code lockEnvironment(LockManager.Method.REFRESH)} (:412-415, the FrameworkWiringLock, 30 s timeout) and the
 * dependency closure are taken on the calling thread before the work is handed to the executor (:144-151).
 *
 * <h3>Root cause</h3>
 * Step 1 of the refresh (dependency closure) runs synchronously under the global wiring lock.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=RefreshBundlesBlocksCallerIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testRefreshReturnsImmediately: refreshBundles must return to the caller immediately, it blocked for 2501 ms
 * </pre>
 *
 * <h3>Expected</h3>
 * refreshBundles returns at once; the closure is computed on the refresh thread.
 *
 * <h3>Reference behaviour</h3>
 * Not compared with Equinox.
 *
 * <h3>Suggested fix</h3>
 * Move the computation of the dependency closure into the Runnable that the executor runs (together with the stop
 * list, see fuzz/19).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 13-Oct-2026
 */
public class RefreshBundlesBlocksCallerIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRefreshReturnsImmediately() throws Exception {
        final Bundle slow = install("refresh-blocks-slow-stop", SlowStopActivator.class);
        Bundle other = install("refresh-blocks-other", null);
        try {
            slow.start();
            Thread stopper = new Thread(new Runnable() {
                public void run() {
                    try {
                        slow.stop();
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            });
            stopper.start();
            Thread.sleep(500);

            final CountDownLatch latch = new CountDownLatch(1);
            long start = System.currentTimeMillis();
            getSystemContext().getBundle().adapt(FrameworkWiring.class).refreshBundles(Collections.singleton(other), new FrameworkListener() {
                public void frameworkEvent(FrameworkEvent event) {
                    latch.countDown();
                }
            });
            long millis = System.currentTimeMillis() - start;
            stopper.join(10000);
            latch.await(30, TimeUnit.SECONDS);
            assertTrue("refreshBundles must return to the caller immediately, it blocked for " + millis + " ms", millis < 1000);
        } finally {
            other.uninstall();
            slow.uninstall();
        }
    }

    public static class SlowStopActivator implements BundleActivator {
        public void start(BundleContext context) {
        }

        public void stop(BundleContext context) throws Exception {
            Thread.sleep(3000);
        }
    }

    private Bundle install(String symbolicName, Class<?> activator) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (activator != null) {
            attributes.putValue(Constants.BUNDLE_ACTIVATOR, activator.getName());
            attributes.putValue(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        }
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
