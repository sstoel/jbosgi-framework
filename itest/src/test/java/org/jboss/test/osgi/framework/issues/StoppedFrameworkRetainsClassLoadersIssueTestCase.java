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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

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
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A stopped framework object keeps every bundle class loader (and with it all loaded classes and the whole bundle
 * state) reachable for as long as the framework object is referenced.
 *
 * <h3>Severity</h3>
 * minor. Launchers and containers that keep the Framework object after stop (to restart it later, or simply in a
 * field, e.g. WildFly's OSGi subsystem across a reload) keep the complete class space of the previous run in memory;
 * repeated stop/start cycles of the same Framework object do not release it either.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkProxy} (field {@code bundleManager}) and
 * {@code BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * connect workstream (framework lifecycle / resource leaks, compliance matrix framework.lifecycle#59), exploratory test
 * plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.6 Stopping a Framework
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.stopping): "The
 * framework then moves to start level 0, calls stop on the Extension Bundle Activators ..., stops event handling and
 * releases any resources (like threads, class loaders, etc.) it held. ... Framework implementations must ensure that
 * holding on to a framework object does not consume significant resources."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:69} keeps the
 * {@code BundleManagerPlugin} after stop ({@code getState()} and {@code waitForStop} use it), which references the
 * {@code FrameworkState}, all bundle states, revisions, modules and their class loaders;
 * {@code BundleManagerPlugin.shutdownManager} only shuts down the MSC container and keeps
 * {@code cachedSystemBundle}. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The framework object holds the entire runtime state of the last run instead of a small record of the stop result.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=StoppedFrameworkRetainsClassLoadersIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A stopped framework must release the bundle class loaders, but the class loader of the
 *   bundle is still reachable from the stopped framework object
 * </pre>
 *
 * <h3>Expected</h3>
 * After stop/waitForStop, with only the Framework object referenced, the bundle class loader becomes unreachable.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: the same check (bundle class loader in a WeakReference, framework object kept, stop + GC) finds
 * the class loader collected.
 *
 * <h3>Suggested fix</h3>
 * At the end of {@code shutdownManager}, drop the references to the framework state (bundles, modules, module
 * loader, services) and keep only what {@code getState}/{@code waitForStop} need; or let FrameworkProxy replace its
 * {@code bundleManager} with a small stopped-state holder once the stop completed.
 *
 * <h3>Regression vs WF41</h3>
 * no (same failure on WF41; on WF41 even the class loader of a bundle that never defined a class is retained, on this
 * branch only the class loader of a bundle that defined a class, here its activator, stays reachable).
 */
public class StoppedFrameworkRetainsClassLoadersIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testClassLoadersReleasedAfterStop() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();

        WeakReference<ClassLoader> loader = installAndResolve();
        assertNotNull(loader.get());

        framework.stop();
        framework.waitForStop(10000);
        for (int i = 0; i < 20 && loader.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertNull("A stopped framework must release the bundle class loaders, but the class loader of the bundle is still reachable from the stopped framework object",
                loader.get());
    }

    private WeakReference<ClassLoader> installAndResolve() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "retained-bundle");
        archive.addClasses(Activator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(Activator.class);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        Bundle bundle = framework.getBundleContext().installBundle(archive.getName(), archive.as(ZipExporter.class).exportAsInputStream());
        bundle.start();
        return new WeakReference<ClassLoader>(bundle.adapt(BundleWiring.class).getClassLoader());
    }

    /** A bundle activator, so that the bundle defines a class */
    public static class Activator implements BundleActivator {

        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) {
        }
    }
}
