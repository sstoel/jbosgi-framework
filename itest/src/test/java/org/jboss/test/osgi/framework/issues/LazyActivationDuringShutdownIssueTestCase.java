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
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.InputStream;
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
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A bundle with the lazy activation policy is activated while the framework shuts down when another bundle loads a
 * class from it during shutdown (e.g. from its BundleActivator.stop).
 *
 * <h3>Severity</h3>
 * minor. Lazily activated bundles get their activator started in a framework that is going down: services are
 * registered into a dying registry, threads are started, and (depending on the stop order) the bundle is never
 * stopped again, so its stop method never runs.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, lazy activation in {@code HostBundleClassLoader} / {@code BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * connect workstream (framework lifecycle, compliance matrix framework.lifecycle#57), exploratory test.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.6 Stopping a Framework
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.stopping): "During
 * the shutdown, bundles with a lazy policy must not be activated even when classes are loaded from them and they are
 * not yet activated."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/HostBundleClassLoader.java:91-111}: when
 * {@code awaitLazyActivation()} is set and the package matches, {@code LazyActivationTracker.preDefineClass/
 * postDefineClass} activate the bundle, without looking at the framework state;
 * {@code BundleManagerPlugin.shutdownManager} ({@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:931-...})
 * puts the system bundle into STOPPING and stops the bundles while such class loads still activate lazy bundles.
 * Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * No "framework is stopping" guard on lazy activation.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=LazyActivationDuringShutdownIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A lazy bundle must not be activated during the shutdown of the framework, but its activator
 *   was started when another bundle loaded its class from BundleActivator.stop
 * </pre>
 *
 * <h3>Expected</h3>
 * The class is loaded, the lazy bundle stays in STARTING (not activated), its activator start is never called.
 *
 * <h3>Reference behaviour</h3>
 * Not run as a differential test (Equinox checks the framework state before lazy activation:
 * {@code EquinoxContainerAdaptor}/{@code ModuleContainer} do not activate during STOPPING).
 *
 * <h3>Suggested fix</h3>
 * Skip the lazy activation trigger when the framework (system bundle) is STOPPING.
 *
 * <h3>Regression vs WF41</h3>
 * no (same behaviour on WF41).
 */
public class LazyActivationDuringShutdownIssueTestCase extends OSGiTest {

    private Framework framework;

    @Before
    public void setUp() {
        System.clearProperty(LazyActivator.STARTED);
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(LazyActivator.STARTED);
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testLazyBundleNotActivatedDuringShutdown() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();

        // The lazy bundle is started first, the loader bundle is stopped first (reverse order of the bundle ids)
        Bundle lazy = install("lazy-bundle", LazyActivator.class, true);
        lazy.start(Bundle.START_ACTIVATION_POLICY);
        assertEquals("The lazy bundle waits for its activation", Bundle.STARTING, lazy.getState());
        install("loader-bundle", LoaderActivator.class, false).start();

        framework.stop();
        framework.waitForStop(10000);

        assertFalse("A lazy bundle must not be activated during the shutdown of the framework, but its activator was started when another bundle loaded its class from BundleActivator.stop",
                Boolean.getBoolean(LazyActivator.STARTED));
    }

    private Bundle install(final String name, final Class<?> activator, final boolean lazy) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(activator);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addBundleActivator(activator);
                builder.addImportPackages("org.osgi.framework");
                if (lazy)
                    builder.addBundleActivationPolicy(Constants.ACTIVATION_LAZY);
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }

    /** The activator of the lazy bundle, it reports through a system property (the bundle has its own copy of the class) */
    public static class LazyActivator implements BundleActivator {

        static final String STARTED = "issues.lazy.started";

        @Override
        public void start(BundleContext context) {
            System.setProperty(STARTED, "true");
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** Loads the activator class of the lazy bundle while the framework stops */
    public static class LoaderActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) {
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            for (Bundle bundle : context.getBundles()) {
                if ("lazy-bundle".equals(bundle.getSymbolicName()))
                    bundle.loadClass("org.jboss.test.osgi.framework.issues.LazyActivationDuringShutdownIssueTestCase$LazyActivator");
            }
        }
    }
}
