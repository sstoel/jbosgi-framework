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
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * Lock order inversion between the fallback (dynamic import) loader lock of a bundle and the framework lifecycle
 * locks: a class load through DynamicImport-Package holds the bundle's FallbackLoader lock while it tries to resolve
 * the INSTALLED bundles (which needs the FrameworkWiringLock), and a lifecycle operation that holds the
 * FrameworkWiringLock (e.g. Bundle.stop) can load a class of the same bundle through the fallback loader. The two
 * threads deadlock until the 30 second lock timeout of the LockManager (the LockException is then swallowed and the
 * class load ends with ClassNotFoundException, after 30 s).
 *
 * <h3>Severity</h3>
 * major - class loading and lifecycle operations on different threads stall for 30 s (every time the interleaving
 * occurs); with the fuzzer's 8 threads, refreshBundles and setStartLevel did not complete within 60 s.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} ({@code loadClassLocal},
 * {@code findInUnresolvedRevisions}).
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful multi-threaded API fuzzer ({@code Stateful}, 8 threads, seed 6001): "refreshBundles did
 * not complete in 60s"; the thread dump showed fz-worker-6 (holding two LockManager locks) waiting for the
 * FallbackLoader lock owned by fz-worker-7, and fz-worker-7 waiting in LockManagerImpl.lockItems from
 * BundleManagerPlugin.resolveBundle &lt;- AbstractBundleState.ensureResolved (called by the fallback loader). Made
 * deterministic here with a BundleActivator.stop that loads a class through DynamicImport-Package.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 10.1.5 Bundle and 10.1.7 BundleContext: "Concurrency Thread-safe"; Bundle.loadClass javadoc (10.1.5.32): "Throws
 * ClassNotFoundException - If no such class can be found or if this bundle is a fragment bundle or if the caller does
 * not have the appropriate AdminPermission[this,CLASS], and the Java Runtime Environment supports permissions." -
 * no other exception is declared; 3.9.2 Dynamic Import Package: dynamic imports are resolved at class load time.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:130-141}
 * ({@code lockFallbackLoader()} around {@code findRevisionDynamically}) and {@code :392-408}
 * ({@code findInUnresolvedRevisions}: {@code ensureResolved(false)} for every INSTALLED bundle under that lock), which
 * reaches {@code BundleManagerPlugin.resolveBundle} (:586-587, {@code lockManager.lockItems(Method.RESOLVE, items)},
 * FrameworkWiringLock). Bundle.stop/uninstall/update hold the FrameworkWiringLock while the activator runs
 * ({@code BundleManagerPlugin.getLockableItems}, :708-725).
 *
 * <h3>Root cause</h3>
 * The fallback loader acquires the global wiring lock while holding its own lock, the lifecycle operations acquire
 * them in the opposite order.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FallbackLoaderLockOrderIssueTestCase} (in jbosgi-framework; about 30 s).
 *
 * <h3>Observed</h3>
 * <pre>
 * testDynamicClassLoadDuringStop (31 s): Bundle.loadClass must not wait for a lock timeout, it took 30006 ms
 * </pre>
 *
 * <h3>Expected</h3>
 * Both the class load and Bundle.stop complete without waiting for a lock timeout; the class load ends with a
 * ClassNotFoundException.
 *
 * <h3>Reference behaviour</h3>
 * Not compared (jbosgi specific locking).
 *
 * <h3>Suggested fix</h3>
 * Do not resolve bundles while holding the FallbackLoader lock (collect the candidates under the lock, resolve outside
 * it), or take the FrameworkWiringLock before the FallbackLoader lock.
 *
 * <h3>Regression vs WF41</h3>
 * no - FallbackLoader locking is unchanged by the R8 branch (ca465b35 only added the framework extension lookup).
 *
 * @since 13-Oct-2026
 */
public class FallbackLoaderLockOrderIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testDynamicClassLoadDuringStop() throws Exception {
        final Bundle dynamic = install("fallback-lock-dynamic", "DynamicImport-Package", "*", DynamicLoadOnStopActivator.class);
        // an INSTALLED bundle that the fallback loader tries to resolve
        Bundle unresolved = install("fallback-lock-unresolved", "Import-Package", "org.acme.does.not.exist", null);
        try {
            dynamic.start();
            final AtomicReference<Throwable> stopFailure = new AtomicReference<Throwable>();
            Thread stopper = new Thread(new Runnable() {
                public void run() {
                    try {
                        // holds the STOP lock (incl. FrameworkWiringLock) while the activator runs
                        dynamic.stop();
                    } catch (Throwable th) {
                        stopFailure.set(th);
                    }
                }
            });
            stopper.start();
            Thread.sleep(500);

            // takes the FallbackLoader lock of the bundle, then wants the FrameworkWiringLock to resolve the INSTALLED bundle
            long start = System.currentTimeMillis();
            Throwable loadFailure = null;
            try {
                dynamic.loadClass("org.acme.fallback.missing.FirstClass");
            } catch (ClassNotFoundException ex) {
                // expected
            } catch (Throwable th) {
                loadFailure = th;
            }
            long loadMillis = System.currentTimeMillis() - start;
            stopper.join(60000);

            assertNull("Bundle.loadClass must fail with ClassNotFoundException only, but threw: " + loadFailure, loadFailure);
            assertTrue("Bundle.loadClass must not wait for a lock timeout, it took " + loadMillis + " ms", loadMillis < 10000);
            assertNull("Bundle.stop must not fail: " + stopFailure.get(), stopFailure.get());
        } finally {
            unresolved.uninstall();
            dynamic.uninstall();
        }
    }

    public static class DynamicLoadOnStopActivator implements BundleActivator {
        public void start(BundleContext context) {
        }

        public void stop(BundleContext context) throws Exception {
            Thread.sleep(1500);
            try {
                getClass().getClassLoader().loadClass("org.acme.fallback.missing.SecondClass");
            } catch (ClassNotFoundException ex) {
                // expected
            }
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
