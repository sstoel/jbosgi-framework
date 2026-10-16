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
import static org.junit.Assert.assertNotNull;

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
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A dynamic import is not wired to a bundle that waits for its lazy activation (STARTING): the class cannot be loaded and
 * the bundle is never activated.
 *
 * <h2>Severity</h2>
 * major. Since lazy activation only applies to started bundles (LazyBundleActivatedWithoutStartIssueTestCase), a lazy
 * bundle that other bundles reach through DynamicImport-Package has to be started, and then it is not found. In WildFly the
 * hk2 osgi-resource-locator (Bundle-ActivationPolicy: lazy) is reached that way by Jakarta Activation and Jakarta Mail:
 * not started, its activator never runs and it finds no providers; started, the dynamic import of
 * org.glassfish.hk2.osgiresourcelocator fails. Either way Jakarta Activation finds no MailcapRegistryProvider and
 * Transport.send fails with "no object DCH for MIME type multipart/alternative".
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FallbackLoader.findInResolvedRevisions}, the provider selection of dynamic imports.
 *
 * <h2>Found by</h2>
 * The email of an application on WildFly 41 stopped with the lazy activation fix of the major round, the hk2
 * osgi-resource-locator capability stayed RESOLVED; with a start level it stayed STARTING and the dynamic import failed.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.9.2 Dynamic Import Package: "Dynamic imports are matched to Export-Package (given the restrictions) during
 * class loading" from the resolved exporters; a bundle in STARTING, ACTIVE or STOPPING is resolved (4.4.2 Bundle State).
 * 4.4.6.2 Lazy Activation Policy: a bundle started with its lazy policy waits in STARTING for "the first request to load a
 * class", which then activates it.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:383
 * ({@code bundleManager.getBundles(Bundle.RESOLVED | Bundle.ACTIVE)}).
 *
 * <h2>Root cause</h2>
 * The candidates of a dynamic import are the bundles in state RESOLVED or ACTIVE, the resolved states STARTING and STOPPING
 * are left out.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=DynamicImportOfLazyStartingBundleIssueTestCase
 * </pre>
 * Deterministic.
 *
 * <h2>Observed</h2>
 * <pre>
 * java.lang.ClassNotFoundException: org.jboss.test.osgi.framework.issues.DynamicImportOfLazyStartingBundleIssueTestCase$Trigger
 *   from [Module "jbosgi.lazy-dynamic-importer:0.0.0.bid2rev0" from FrameworkModuleLoaderImpl]
 * </pre>
 *
 * <h2>Expected</h2>
 * The dynamic import is wired to the STARTING bundle, the class is loaded from it and the bundle is activated.
 *
 * <h2>Regression vs WF41</h2>
 * no (pre-existing), it shows since a never started lazy bundle is no longer activated by a class load.
 */
public class DynamicImportOfLazyStartingBundleIssueTestCase extends OSGiTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        System.clearProperty(LazyActivator.STARTED);
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(LazyActivator.STARTED);
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testDynamicImportActivatesStartingBundle() throws Exception {
        Bundle lazy = installLazyExporter();
        lazy.start(Bundle.START_ACTIVATION_POLICY);
        assertEquals("Precondition: the lazy bundle waits for its activation", Bundle.STARTING, lazy.getState());

        Bundle importer = installDynamicImporter();
        Class<?> clazz = importer.loadClass(Trigger.class.getName());
        assertEquals("Expected the class to be loaded from the lazy bundle", lazy, ((BundleReference) clazz.getClassLoader()).getBundle());
        assertEquals("Expected the class load through the dynamic import to activate the lazy bundle, but it is in state " + lazy.getState(),
                Bundle.ACTIVE, lazy.getState());
        assertNotNull("Expected the activator of the lazy bundle to be called", System.getProperty(LazyActivator.STARTED));
    }

    private Bundle installLazyExporter() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "lazy-dynamic-exporter");
        archive.addClasses(LazyActivator.class, Trigger.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("lazy-dynamic-exporter");
                builder.addBundleActivator(LazyActivator.class);
                builder.addImportPackages("org.osgi.framework");
                builder.addExportPackages(Trigger.class);
                builder.addBundleActivationPolicy(Constants.ACTIVATION_LAZY);
                return builder.openStream();
            }
        });
        Bundle bundle = framework.getBundleContext().installBundle("lazy-dynamic-exporter", archive.as(ZipExporter.class).exportAsInputStream());
        assertEquals("Precondition: installed", Bundle.INSTALLED, bundle.getState());
        return bundle;
    }

    private Bundle installDynamicImporter() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "lazy-dynamic-importer");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("lazy-dynamic-importer");
                builder.addDynamicImportPackages(Trigger.class);
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle("lazy-dynamic-importer", archive.as(ZipExporter.class).exportAsInputStream());
    }

    /** The activator of the lazy bundle, it reports through a system property (the bundle has its own copy of the class) */
    public static class LazyActivator implements BundleActivator {

        static final String STARTED = "issues.lazy.dynamic.activated";

        @Override
        public void start(BundleContext context) {
            System.setProperty(STARTED, "true");
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** A class of the lazy bundle */
    public static class Trigger {
    }
}
