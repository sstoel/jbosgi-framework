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

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

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
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * Concurrent {@code installBundle} calls with the same location install several bundles with that location instead of
 * returning the one bundle that is installed for it.
 *
 * <h3>Severity</h3>
 * major. Management agents commonly install from several threads (deployment scanners, provisioning, the WildFly
 * management layer); two threads installing the same location end up with duplicate bundles that share one storage
 * record (the storage states are keyed by location), so one of them is lost or mixed up on the next launch, and
 * {@code getBundle(location)} returns an arbitrary one.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.AbstractBundleContext.installBundleInternal},
 * {@code BundleManagerPlugin.createBundleRevision}.
 *
 * <h3>Found by</h3>
 * connect workstream (concurrency of install/update/uninstall), exploratory stress test; confirmed for stream installs
 * on the WF41 build as well.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.3 Installing Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.installingbundles):
 * "Every bundle is uniquely identified by its location string. If an installed bundle is using the specified
 * location, the installBundle methods must return the Bundle object for that installed bundle and not install a new
 * bundle."<br>
 * 4.4.1 Bundle Identifiers: "Within a particular Framework, a location must be unique."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleContext.java:171-181} checks
 * {@code getBundleByLocation(location)} and only much later (line 224, {@code createBundleRevisionLifecycle}) installs
 * the bundle, without any lock or re-check in between; {@code BundleManagerPlugin.createBundleRevision} does not check
 * the location again. Unchanged since WF41 (the connect path added in 3680cfcc goes through the same unguarded window).
 *
 * <h3>Root cause</h3>
 * Check-then-act race: the "is this location installed" check and the install are not atomic.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConcurrentInstallSameLocationIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testConcurrentInstallOfConnectLocation: Concurrent installs of one location must install exactly one bundle, but
 *   there are 8 bundles with the location connect:same (7 or 8 in three runs)
 * testConcurrentInstallOfFileLocation: Concurrent installs of one location must install exactly one bundle, but
 *   there are 8 bundles with the location /.../target/issues-same-location.jar (8 in three runs)
 * </pre>
 *
 * <h3>Expected</h3>
 * Exactly one bundle per location; every concurrent caller gets that same Bundle object.
 *
 * <h3>Reference behaviour</h3>
 * Equinox serializes installs per location (Container.install takes a location lock and re-checks), so it always ends
 * with a single bundle (not run as a separate differential test).
 *
 * <h3>Suggested fix</h3>
 * Serialize installs of the same location: take a per-location lock (or the bundle manager's install lock) around the
 * existing-bundle check and {@code createBundleRevisionLifecycle}, and re-check {@code getBundleByLocation} under it;
 * close the virtual file (or connect content) of the losing caller and return the existing bundle.
 *
 * <h3>Regression vs WF41</h3>
 * no (stream installs of the same location race the same way on WF41); the connect install path is new and inherits it.
 */
public class ConcurrentInstallSameLocationIssueTestCase extends OSGiTest {

    private static final int THREADS = 8;
    private static final String CONNECT_LOCATION = "connect:same";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConcurrentInstallOfConnectLocation() throws Exception {
        startFramework();
        assertSingleBundle(CONNECT_LOCATION);
    }

    @Test
    public void testConcurrentInstallOfFileLocation() throws Exception {
        startFramework();
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "same-location");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        File file = new File("target/issues-same-location.jar").getAbsoluteFile();
        archive.as(ZipExporter.class).exportTo(file, true);
        assertSingleBundle(file.getPath());
    }

    private void assertSingleBundle(final String location) throws Exception {
        final BundleContext context = framework.getBundleContext();
        final CyclicBarrier barrier = new CyclicBarrier(THREADS);
        final List<Object> results = new CopyOnWriteArrayList<Object>();
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                        results.add(context.installBundle(location));
                    } catch (Throwable th) {
                        results.add(th);
                    }
                }
            };
            thread.start();
            threads.add(thread);
        }
        for (Thread thread : threads)
            thread.join(30000);

        int count = 0;
        for (Bundle bundle : context.getBundles()) {
            if (location.equals(bundle.getLocation()))
                count++;
        }
        assertEquals("Concurrent installs of one location must install exactly one bundle, but there are " + count + " bundles with the location " + location
                + " (results: " + results + ")", 1, count);
    }

    private void startFramework() throws Exception {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                if (CONNECT_LOCATION.equals(location) == false)
                    return Optional.empty();
                return Optional.<ConnectModule> of(new ConnectModule() {
                    @Override
                    public ConnectContent getContent() {
                        return new SimpleContent();
                    }
                });
            }

            @Override
            public Optional<BundleActivator> newBundleActivator() {
                return Optional.empty();
            }
        };
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        // Several bundles with the same symbolic name and version are allowed, the location alone must prevent them
        props.put(Constants.FRAMEWORK_BSNVERSION, Constants.FRAMEWORK_BSNVERSION_MULTIPLE);
        framework = ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
        framework.start();
    }

    static class SimpleContent implements ConnectContent {

        @Override
        public Optional<Map<String, String>> getHeaders() {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.same");
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            return Collections.emptyList();
        }

        @Override
        public Optional<ConnectEntry> getEntry(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            return Optional.empty();
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }
    }
}
