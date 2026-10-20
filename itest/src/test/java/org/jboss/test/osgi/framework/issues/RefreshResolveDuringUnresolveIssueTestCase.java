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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.issues.refresh.a.RefreshProviderA;
import org.jboss.test.osgi.framework.issues.refresh.b.RefreshProviderB;
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
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle that is resolved while a refresh unresolves the bundles of its closure is wired to a bundle that the refresh
 * unresolves next: it keeps a wire to a revision without a current wiring.
 *
 * <h2>Severity</h2>
 * critical. The bundle becomes ACTIVE and keeps loading classes from a module that the refresh removed.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkWiringImpl.refreshBundlesLocked}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 4 (fw-small-02).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:239-282 (169c8c00).
 *
 * <h2>Root cause</h2>
 * The refresh unresolved the bundles without the framework wiring lock, which serializes the resolve operations.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=RefreshResolveDuringUnresolveIssueTestCase
 * </pre>
 * A and B export a package each and are refreshed together. The UNRESOLVED event of the first of them starts the
 * bundle C that imports the package of the other one, on another thread.
 *
 * <h2>Expected</h2>
 * After the refresh C is not resolved, or it is wired to a provider with a current wiring.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 169c8c00.
 */
public class RefreshResolveDuringUnresolveIssueTestCase extends OSGiTest {

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
    public void testResolveDuringRefresh() throws Exception {
        BundleContext context = framework.getBundleContext();
        String packageA = RefreshProviderA.class.getPackage().getName();
        String packageB = RefreshProviderB.class.getPackage().getName();
        final Bundle a = install(context, "refresh-unresolve-a", Constants.EXPORT_PACKAGE, packageA, RefreshProviderA.class);
        final Bundle b = install(context, "refresh-unresolve-b", Constants.EXPORT_PACKAGE, packageB, RefreshProviderB.class);
        final Bundle importerA = install(context, "refresh-unresolve-ca", Constants.IMPORT_PACKAGE, packageA, null);
        final Bundle importerB = install(context, "refresh-unresolve-cb", Constants.IMPORT_PACKAGE, packageB, null);
        FrameworkWiring frameworkWiring = framework.adapt(FrameworkWiring.class);
        assertTrue(frameworkWiring.resolveBundles(Arrays.asList(a, b)));
        assertEquals(Bundle.INSTALLED, importerA.getState());
        assertEquals(Bundle.INSTALLED, importerB.getState());

        final AtomicReference<Bundle> lateImporter = new AtomicReference<Bundle>();
        final AtomicReference<String> startResult = new AtomicReference<String>("not started");
        final AtomicReference<Thread> startThread = new AtomicReference<Thread>();
        context.addBundleListener(new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.UNRESOLVED && (event.getBundle() == a || event.getBundle() == b) && lateImporter.get() == null) {
                    // The importer of the provider that is not unresolved yet
                    final Bundle importer = event.getBundle() == a ? importerB : importerA;
                    lateImporter.set(importer);
                    final CountDownLatch done = new CountDownLatch(1);
                    Thread thread = new Thread(new Runnable() {
                        public void run() {
                            try {
                                importer.start();
                                startResult.set("started");
                            } catch (Throwable th) {
                                startResult.set("start failed: " + th);
                            } finally {
                                done.countDown();
                            }
                        }
                    }, "refresh-unresolve-start");
                    thread.setDaemon(true);
                    startThread.set(thread);
                    thread.start();
                    try {
                        done.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });

        final CountDownLatch refreshed = new CountDownLatch(1);
        frameworkWiring.refreshBundles(Arrays.asList(a, b), new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                refreshed.countDown();
            }
        });
        assertTrue("The refresh finished", refreshed.await(60, TimeUnit.SECONDS));
        startThread.get().join(60000);

        Bundle importer = lateImporter.get();
        BundleWiring wiring = importer.adapt(BundleWiring.class);
        if (importer.getState() != Bundle.INSTALLED && wiring != null) {
            for (BundleWire wire : wiring.getRequiredWires(null)) {
                Bundle provider = wire.getProvider().getBundle();
                BundleWiring providerWiring = wire.getProviderWiring();
                String info = importer.getSymbolicName() + " (" + startResult.get() + ") is wired to " + provider.getSymbolicName() + " in state "
                        + provider.getState() + ", provider wiring current=" + (providerWiring != null && providerWiring.isCurrent());
                assertTrue("The importer must not stay wired to a revision that the refresh unresolved: " + info,
                        providerWiring != null && providerWiring.isCurrent() && wire.getProvider() == provider.adapt(BundleRevision.class));
            }
        }
    }

    private Bundle install(BundleContext context, final String name, final String header, final String value, Class<?> clazz) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (clazz != null)
            archive.addClasses(clazz);
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
