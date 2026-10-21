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
 * A stopped framework that had a framework extension attached keeps the class loaders of its bundles reachable: the
 * attached extension revisions are never released.
 *
 * <h2>Severity</h2>
 * minor. As connect/16, for every framework with an extension: the class space of the stopped run stays in memory.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkExtensions}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of connect/16 (fw-minor-A-02).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.2.7 Framework.stop: "All resources held by this Framework are released. This includes threads, bundle
 * class loaders, open files, etc."
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkExtensions.java:97 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The list of the attached extension revisions was not cleared when the framework stopped.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=StoppedFrameworkExtensionRetainsClassLoadersIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The class loader of a bundle of the stopped framework can be collected.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class StoppedFrameworkExtensionRetainsClassLoadersIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testClassLoadersReleasedWithExtension() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/stopped-framework-extension").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        Bundle extension = framework.getBundleContext().installBundle("retained-extension", extension());
        framework.stop();
        framework.waitForStop(10000);

        // The extension is attached when the framework is launched again
        framework.start();
        extension = framework.getBundleContext().getBundle("retained-extension");
        assertEquals("The extension is attached", Bundle.RESOLVED, extension.getState());
        extension = null;

        WeakReference<ClassLoader> loader = installAndStart();
        assertNotNull(loader.get());

        framework.stop();
        framework.waitForStop(10000);
        for (int i = 0; i < 20 && loader.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertNull("The stopped framework releases the bundle class loaders, also with an extension attached", loader.get());
    }

    private WeakReference<ClassLoader> installAndStart() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "retained-with-extension");
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

    private static InputStream extension() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "retained-extension");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addFragmentHost("system.bundle;extension:=framework");
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
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
