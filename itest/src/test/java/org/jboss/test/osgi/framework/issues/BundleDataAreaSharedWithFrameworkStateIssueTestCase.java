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
import static org.junit.Assert.fail;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
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
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * The persistent storage area that a bundle gets from {@code getDataFile} is the directory in which the framework
 * keeps the bundle's own persistent state and stored content; a bundle that deletes the files of its data area
 * (which the spec explicitly allows) makes the framework fail to launch again.
 *
 * <h3>Severity</h3>
 * critical. One bundle that cleans up its data area (a common pattern for caches) destroys the framework's record of
 * that bundle; the next launch of the framework fails with "Cannot initialize Framework" (IllegalArgumentException
 * "Required property 'BundleRev' missing"), so the OSGi subsystem (and with it the WildFly server's OSGi deployments)
 * cannot start until the storage area is repaired by hand. The bundle also sees and can read or overwrite the
 * framework's files ({@code bundle-persistent.properties}, {@code bundle-N-rev-M.jar}).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.StorageManagerImpl.getDataFile} /
 * {@code getStorageDir}, {@code StorageManagerImpl.initialize}/{@code restoreStorageState},
 * {@code org.jboss.osgi.framework.spi.StorageState}.
 *
 * <h3>Found by</h3>
 * connect workstream (persistence across restarts), exploratory test of the data area, confirmed on the WF41 build
 * through {@code BundleContext.getDataFile}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.5.2 Persistent Storage
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.persistentstorage):
 * "The Framework should provide a private persistent storage area for each installed bundle ... The Framework must
 * automatically provide the bundle with FilePermission[&lt;storage area&gt;, READ | WRITE | DELETE] to allow the bundle to
 * read, write, and delete files in that storage area."<br>
 * Bundle.getDataFile javadoc (osgi.core 8.0.0): "Creates a File object for a file in the persistent storage area
 * provided for this bundle by the Framework."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/StorageManagerImpl.java:220-232} ({@code getDataFile})
 * resolves the file name against {@code getStorageDir(bundleId)} (lines 200-212), which is the same
 * {@code <storage>/bundle-N} directory in which {@code StorageState} writes {@code bundle-persistent.properties} and
 * the {@code bundle-N-rev-M.jar} content ({@code core/src/main/java/org/jboss/osgi/framework/spi/StorageState.java}).
 * On the next launch {@code StorageManagerImpl.initialize} (line 100) restores every {@code bundle-*} directory and
 * {@code StorageState.<init>} (line 143) throws IllegalArgumentException for the missing properties, which aborts
 * {@code Framework.init}. Unchanged since WF41 ({@code BundleContext.getDataFile} uses the same method;
 * {@code Bundle.getDataFile} was added on top of it in 5ceeef0b).
 *
 * <h3>Root cause</h3>
 * The framework's per-bundle bookkeeping directory doubles as the bundle's private data area, and a damaged or
 * incomplete bundle directory is a fatal error for the whole framework instead of a skipped bundle.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundleDataAreaSharedWithFrameworkStateIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testDataAreaOfNewBundleIsEmpty: The data area of a newly installed bundle must only contain what the bundle put
 *   there, but it contains [bundle-persistent.properties, bundle-1-rev-0.jar]
 * testFrameworkLaunchesAfterBundleCleanedItsDataArea: The framework must launch again after a bundle deleted the files
 *   of its data area, but start() threw org.osgi.framework.BundleException: JBOSGI011246: Cannot initialize Framework
 *   (caused by IllegalArgumentException: JBOSGI011202: Required property 'BundleRev' missing in: .../bundle-1)
 * </pre>
 *
 * <h3>Expected</h3>
 * The data area is a private directory (Equinox: {@code <storage>/org.eclipse.osgi/<id>/data}); deleting its contents
 * affects nothing but the bundle's own data, and the framework relaunches with the bundle installed.
 *
 * <h3>Reference behaviour</h3>
 * Equinox keeps the data area in a separate {@code data} sub-directory of the bundle's storage directory.
 *
 * <h3>Suggested fix</h3>
 * Return files below a dedicated sub-directory from {@code StorageManagerImpl.getDataFile}, e.g.
 * {@code getStorageDir(bundleId)/data}, created on demand (keep the old location readable for one release if bundles
 * depend on it), and let {@code StorageManagerImpl.initialize} log and skip (or delete) a bundle directory that cannot
 * be restored instead of failing the framework.
 *
 * <h3>Regression vs WF41</h3>
 * no (same behaviour through {@code BundleContext.getDataFile} on WF41; {@code Bundle.getDataFile} is new and exposes
 * the same directory).
 */
public class BundleDataAreaSharedWithFrameworkStateIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        stopFramework();
    }

    private void stopFramework() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testDataAreaOfNewBundleIsEmpty() throws Exception {
        Bundle bundle = startFrameworkWithBundle();
        File dataArea = bundle.getDataFile("");
        assertNotNull("The platform has file system support, so there must be a data area", dataArea);
        String[] contents = dataArea.list();
        assertEquals("The data area of a newly installed bundle must only contain what the bundle put there, but it contains " + Arrays.toString(contents),
                0, contents == null ? 0 : contents.length);
    }

    @Test
    public void testFrameworkLaunchesAfterBundleCleanedItsDataArea() throws Exception {
        Bundle bundle = startFrameworkWithBundle();
        long bundleId = bundle.getBundleId();

        // The bundle cleans up its data area, which it is permitted to do
        for (File file : bundle.getDataFile("").listFiles())
            file.delete();
        stopFramework();

        framework = newFramework(false);
        try {
            framework.start();
        } catch (BundleException ex) {
            fail("The framework must launch again after a bundle deleted the files of its data area, but start() threw " + ex + " (caused by " + ex.getCause() + ")");
        }
        assertNotNull("The bundle must still be installed after the framework was launched again", framework.getBundleContext().getBundle(bundleId));
    }

    private Bundle startFrameworkWithBundle() throws Exception {
        framework = newFramework(true);
        framework.start();
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "data-area-bundle");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        Bundle bundle = framework.getBundleContext().installBundle(archive.getName(), archive.as(ZipExporter.class).exportAsInputStream());
        bundle.start();
        return bundle;
    }

    private Framework newFramework(boolean clean) {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
