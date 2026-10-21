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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * A bundle storage directory that an interrupted install left behind, with content but without its properties, or with
 * properties that name content that is not there, is skipped on every start and never removed.
 *
 * <h2>Severity</h2>
 * minor. The directories stay in the storage area for good, with a warning on every start.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code StorageState.createStorageState}, {@code StorageManagerImpl.initialize}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of connect/24 (fw-small, 0cad6c4a).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.3: an installed bundle is persistent; a bundle that was not installed has no storage.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/spi/StorageState.java:100-122,
 * core/src/main/java/org/jboss/osgi/framework/internal/StorageManagerImpl.java:121-130 (0cad6c4a).
 *
 * <h2>Root cause</h2>
 * The content and the properties were written in place, a crash in between left a directory that cannot be restored.
 * Restore failures were only skipped.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=IncompleteBundleStorageIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The incomplete directories and the temporary files of an interrupted write are removed at the next start, the
 * installed bundles are restored.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class IncompleteBundleStorageIssueTestCase extends OSGiTest {

    private final File storage = new File("target/incomplete-bundle-storage").getAbsoluteFile();
    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testIncompleteStorageRemovedAtStart() throws Exception {
        framework = newFramework(true);
        Bundle bundle = framework.getBundleContext().installBundle("incomplete-storage-ok", bundle("incomplete-storage-ok"));
        long bundleId = bundle.getBundleId();
        File bundleDir = new File(storage, "bundle-" + bundleId);
        assertTrue("The storage of the bundle exists: " + bundleDir, bundleDir.isDirectory());
        assertEquals("No temporary file is left after the install", 0, temporaryFiles(bundleDir));
        framework.stop();
        framework.waitForStop(20000);

        // Content without properties, as a crash before the properties were written leaves it
        File noProperties = new File(storage, "bundle-" + (bundleId + 10));
        assertTrue(new File(noProperties, "data").mkdirs());
        write(new File(noProperties, "bundle-" + (bundleId + 10) + "-rev-0.jar"), "content");
        // Properties that name content that is not there
        File noContent = new File(storage, "bundle-" + (bundleId + 11));
        assertTrue(noContent.mkdirs());
        Properties props = new Properties();
        props.setProperty("BundleId", Long.toString(bundleId + 11));
        props.setProperty("BundleRev", "0");
        props.setProperty("Location", "incomplete-storage-missing");
        props.setProperty("StartLevel", "1");
        props.setProperty("LastModified", "0");
        props.setProperty("BundleFile", "bundle-" + (bundleId + 11) + "-rev-0.jar");
        OutputStream output = new FileOutputStream(new File(noContent, "bundle-persistent.properties"));
        try {
            props.store(output, null);
        } finally {
            output.close();
        }
        // The temporary file of an interrupted write of the properties
        write(new File(bundleDir, "bundle-persistent.properties.tmp"), "partial");

        framework = newFramework(false);
        assertNotNull("The installed bundle is restored", framework.getBundleContext().getBundle("incomplete-storage-ok"));
        assertFalse("The directory without properties is removed: " + noProperties, noProperties.exists());
        assertFalse("The directory without its content is removed: " + noContent, noContent.exists());
        assertEquals("The temporary file is removed", 0, temporaryFiles(bundleDir));
        assertEquals("No bundle is restored from the incomplete directories", null, framework.getBundleContext().getBundle("incomplete-storage-missing"));
    }

    private Framework newFramework(boolean clean) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, storage.getPath());
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        Framework result = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        result.start();
        return result;
    }

    private static int temporaryFiles(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        for (File file : files != null ? files : new File[0]) {
            if (file.getName().endsWith(".tmp"))
                count++;
        }
        return count;
    }

    private static void write(File file, String text) throws Exception {
        OutputStream output = new FileOutputStream(file);
        try {
            output.write(text.getBytes("UTF-8"));
        } finally {
            output.close();
        }
    }

    private static InputStream bundle(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }
}
