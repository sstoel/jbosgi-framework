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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
import org.jboss.test.osgi.framework.issues.dynwire.DynamicWireA;
import org.jboss.test.osgi.framework.issues.dynwire.DynamicWireB;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Two threads that load classes of a package through a dynamic import for the first time at the same time give the
 * importer two dynamic wires for the package.
 *
 * <h2>Severity</h2>
 * minor. The wiring of the importer has two wires for one package, which the wiring API and the JMX and management
 * views report; the two loads could get the package from different exporters.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FallbackLoader.addDynamicWire}.
 *
 * <h2>Found by</h2>
 * R8 fix review, confirmed by test (fw-major-02).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.9.2 Dynamic Import Package: once a package is wired dynamically, later loads of the package use that
 * wire.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:525-545.
 *
 * <h2>Root cause</h2>
 * The fallback loader releases its lock while it resolves the exporter. The second thread looks for an existing wire
 * before that, and both add their wire.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=DynamicImportConcurrentWireIssueTestCase
 * </pre>
 * A resolver hook holds the first thread in the resolve of the exporter until the second thread waits for it.
 *
 * <h2>Expected</h2>
 * The importer has one dynamic wire for the package.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing race).
 */
public class DynamicImportConcurrentWireIssueTestCase extends OSGiTest {

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
    public void testConcurrentFirstDynamicImport() throws Exception {
        BundleContext context = framework.getBundleContext();
        final String pkg = DynamicWireA.class.getPackage().getName();
        final Bundle exporter = install(context, "dynwire-exporter", Constants.EXPORT_PACKAGE, pkg, DynamicWireA.class, DynamicWireB.class);
        final Bundle importer = install(context, "dynwire-importer", Constants.DYNAMICIMPORT_PACKAGE, pkg);
        assertTrue(framework.adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(importer)));
        assertEquals("Precondition: the exporter is not resolved", Bundle.INSTALLED, exporter.getState());

        // The first thread resolves the exporter, the hook waits until the second thread waits for that resolve
        final AtomicReference<Thread> first = new AtomicReference<Thread>();
        final AtomicReference<Thread> second = new AtomicReference<Thread>();
        final CountDownLatch inHook = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean();
        context.registerService(ResolverHookFactory.class, new ResolverHookFactory() {
            public ResolverHook begin(Collection<BundleRevision> triggers) {
                if (Thread.currentThread() == first.get() && triggers.contains(exporter.adapt(BundleRevision.class)) && once.compareAndSet(false, true)) {
                    inHook.countDown();
                    long deadline = System.currentTimeMillis() + 5000;
                    while (!isWaiting(second.get()) && System.currentTimeMillis() < deadline)
                        sleep(20);
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

        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        first.set(loader(importer, DynamicWireA.class.getName(), failure));
        second.set(loader(importer, DynamicWireB.class.getName(), failure));
        first.get().start();
        assertTrue("The first thread resolves the exporter", inHook.await(10, TimeUnit.SECONDS));
        second.get().start();
        first.get().join(30000);
        second.get().join(30000);
        assertEquals("Both classes are loaded", null, failure.get());

        int count = 0;
        for (BundleWire wire : importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (pkg.equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                count++;
        }
        assertEquals("The importer has one dynamic wire for the package", 1, count);
    }

    private static Thread loader(final Bundle bundle, final String className, final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    bundle.loadClass(className);
                } catch (Throwable th) {
                    failure.set(th);
                }
            }
        }, "dynwire-" + className);
        thread.setDaemon(true);
        return thread;
    }

    // The thread waits in the lock manager, for the resolve of the exporter
    private static boolean isWaiting(Thread thread) {
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

    private Bundle install(BundleContext context, final String name, final String header, final String value, Class<?>... classes) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (classes.length > 0)
            archive.addClasses(classes);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return context.installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }
}
