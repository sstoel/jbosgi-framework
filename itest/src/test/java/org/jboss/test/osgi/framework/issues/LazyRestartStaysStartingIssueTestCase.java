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
 * A lazy bundle that was stopped and started again with its activation policy stays STARTING: a class load does not
 * activate it any more.
 *
 * <h2>Severity</h2>
 * critical. A lazy bundle without an activator (a typical Declarative Services bundle) is not activated after its first
 * restart, one with an activator after its second; its components and services never come back.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: the lazy activation trigger, {@code HostBundleClassLoader} and {@code UserBundleState}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 5 (fw-major-01); also affects ee/23 and fuzz/54.
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.6.2 Lazy Activation Policy: a lazily started bundle "must not be activated until it receives the first
 * request to load a class. This request can originate either during normal class loading or via the Bundle loadClass
 * method". The TCK (activationpolicy TestControl.testActivationPolicy04) expects a bundle that is started again to wait
 * in STARTING and to be activated by a load of a class that it defined before.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:150-172, 724 (89ed93ad).
 *
 * <h2>Root cause</h2>
 * The activation was triggered when the bundle class loader defined a class. A class that was defined before is
 * returned without a definition, so after a restart nothing activated the bundle. The flag for a class that was loaded
 * before the lazy start was also armed by the activator class, which activated a bundle with an activator right away
 * once.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=LazyRestartStaysStartingIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * Each start with the activation policy leaves the bundle in STARTING, and the next request for one of its classes,
 * through Bundle.loadClass, an import or a dynamic import, activates it.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 89ed93ad.
 */
public class LazyRestartStaysStartingIssueTestCase extends OSGiTest {

    private Framework framework;

    @Before
    public void setUp() throws Exception {
        System.clearProperty(LazyActivator.STARTS);
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(LazyActivator.STARTS);
        if (framework != null) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testRestartWithoutActivator() throws Exception {
        final Bundle lazy = installLazyBundle("lazy-restart-noactivator", false);
        assertRestarts(lazy, new Trigger() {
            public void loadClass() throws Exception {
                lazy.loadClass(LazyClass.class.getName());
            }
        });
    }

    @Test
    public void testRestartWithActivator() throws Exception {
        final Bundle lazy = installLazyBundle("lazy-restart-activator", true);
        assertRestarts(lazy, new Trigger() {
            public void loadClass() throws Exception {
                lazy.loadClass(LazyClass.class.getName());
            }
        });
        assertEquals("The activator runs on every activation", "3", System.getProperty(LazyActivator.STARTS));
    }

    @Test
    public void testRestartThroughImport() throws Exception {
        Bundle lazy = installLazyBundle("lazy-restart-exporter", true);
        final Bundle importer = installImporter("lazy-restart-importer", false);
        assertRestarts(lazy, new Trigger() {
            public void loadClass() throws Exception {
                importer.loadClass(LazyClass.class.getName());
            }
        });
    }

    @Test
    public void testRestartThroughDynamicImport() throws Exception {
        Bundle lazy = installLazyBundle("lazy-restart-dynamic-exporter", true);
        final Bundle importer = installImporter("lazy-restart-dynamic-importer", true);
        assertRestarts(lazy, new Trigger() {
            public void loadClass() throws Exception {
                importer.loadClass(LazyClass.class.getName());
            }
        });
    }

    private void assertRestarts(Bundle lazy, Trigger trigger) throws Exception {
        for (int cycle = 1; cycle <= 3; cycle++) {
            lazy.start(Bundle.START_ACTIVATION_POLICY);
            assertEquals("Cycle " + cycle + ": the bundle waits for its lazy activation", Bundle.STARTING, lazy.getState());
            trigger.loadClass();
            assertEquals("Cycle " + cycle + ": a request for a class of the bundle activates it", Bundle.ACTIVE, lazy.getState());
            lazy.stop();
            assertEquals(Bundle.RESOLVED, lazy.getState());
        }
    }

    private Bundle installLazyBundle(final String name, final boolean activator) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(LazyClass.class);
        if (activator)
            archive.addClasses(LazyActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (activator) {
                    builder.addBundleActivator(LazyActivator.class);
                    builder.addImportPackages("org.osgi.framework");
                }
                builder.addExportPackages(LazyClass.class);
                builder.addBundleActivationPolicy(Constants.ACTIVATION_LAZY);
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }

    private Bundle installImporter(final String name, final boolean dynamic) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (dynamic)
                    builder.addDynamicImportPackages(LazyClass.class);
                else
                    builder.addImportPackages(LazyClass.class);
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(name, archive.as(ZipExporter.class).exportAsInputStream());
    }

    interface Trigger {
        void loadClass() throws Exception;
    }

    /** The activator of the lazy bundle, it counts its starts in a system property (the bundle has its own copy of the class) */
    public static class LazyActivator implements BundleActivator {

        static final String STARTS = "issues.lazy.restart.starts";

        @Override
        public void start(BundleContext context) {
            System.setProperty(STARTS, String.valueOf(Integer.getInteger(STARTS, 0) + 1));
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    /** A class of the lazy bundle */
    public static class LazyClass {
    }
}
