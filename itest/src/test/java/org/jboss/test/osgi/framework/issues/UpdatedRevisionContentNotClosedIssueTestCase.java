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

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Every {@code Bundle.update} keeps the content of the replaced revision open (one file handle per update, one more
 * per Bundle-ClassPath jar) until the bundle is uninstalled, even when the replaced revision was never in use and the
 * removal-pending bundles were refreshed.
 *
 * <h3>Severity</h3>
 * minor. A long running framework whose bundles are updated repeatedly (redeployments, provisioning) accumulates open
 * jar files and the memory of the old revisions; with enough updates the process runs out of file descriptors. On
 * Windows the stored revision jars cannot be deleted either.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState.updateInternal},
 * {@code FrameworkWiringImpl.refreshBundles}, {@code BundleManagerPlugin.removeRevision}.
 *
 * <h3>Found by</h3>
 * connect workstream (resource leaks), counting the open file descriptors of the test JVM across install/update/
 * uninstall cycles; install/uninstall cycles do not leak, update does.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.9 Updating Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.updatingbundles):
 * "If the old bundle revision has an isInUse() bundle wiring then all capabilities provided by the old bundle wiring
 * must remain available for existing bundles and future resolves until the bundle is refreshed ... Otherwise the
 * capabilities provided by the old revision must be removed."<br>
 * FrameworkWiring.getRemovalPendingBundles javadoc: "Returns the bundles that have non-current, in use bundle
 * wirings." A revision that is not in use is therefore never removal pending, so nothing ever releases it before
 * uninstall. (BundleRevisions allows "intermediate bundle revisions which are not in use" to be listed, so this is a
 * resource leak rather than a visible API violation.)
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:280-297} ({@code updateInternal}
 * only makes the old wiring uneffective; since 8322ec6d it closes the old content only for connect bundles, line
 * 292-293); the revision stays in {@code UserBundleState.revisions} and its {@code RevisionContent}s stay open until
 * {@code BundleManagerPlugin.removeRevision} (line 697-704) runs, which only happens on uninstall or when the bundle
 * itself is in a refresh closure ({@code UserBundleState.refresh}, line 409-412). Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * A replaced revision that is not in use is neither removed at update time nor treated as removal pending.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=UpdatedRevisionContentNotClosedIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests});
 * needs /dev/fd (macOS, Linux).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The content of revisions replaced by update must be closed, but after 40 updates and a
 *   refresh of the removal pending bundles the JVM holds 80 more open file descriptors (before: 132, after: 212)
 * </pre>
 * (exploratory runs: 50 updates of a plain jar leave 50 descriptors open, with a nested Bundle-ClassPath jar 100; the
 * same on the WF41 build; all are released when the bundle is uninstalled)
 *
 * <h3>Expected</h3>
 * The descriptors of the replaced, unused revisions are released at update time (or at the latest by
 * {@code refreshBundles(null)}), so the count stays flat.
 *
 * <h3>Reference behaviour</h3>
 * Not run as a differential test; Equinox discards a replaced revision that is not in use during the update.
 *
 * <h3>Suggested fix</h3>
 * At the end of {@code UserBundleState.updateInternal}, when the old revision's wiring is not in use, remove it like
 * {@code refresh} does ({@code bundleManager.removeRevisionLifecycle(oldRev, 0)}, which closes its contents), instead
 * of only closing connect content.
 *
 * <h3>Regression vs WF41</h3>
 * no (same leak on WF41).
 */
public class UpdatedRevisionContentNotClosedIssueTestCase extends OSGiTest {

    private static final int UPDATES = 40;

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testUpdatesDoNotLeakFileDescriptors() throws Exception {
        assumeTrue("Open file descriptors are counted through /dev/fd", openFileDescriptors() > 0);

        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();

        byte[] content = createBundle();
        Bundle bundle = framework.getBundleContext().installBundle("updated-bundle", new ByteArrayInputStream(content));
        bundle.start();
        settle();
        int before = openFileDescriptors();

        for (int i = 0; i < UPDATES; i++) {
            bundle.update(new ByteArrayInputStream(content));
            bundle.getResource("resource.txt");
        }
        refreshRemovalPendingBundles();
        settle();
        int after = openFileDescriptors();

        assertTrue("The content of revisions replaced by update must be closed, but after " + UPDATES + " updates and a refresh of the removal pending bundles the JVM holds "
                + (after - before) + " more open file descriptors (before: " + before + ", after: " + after + ")", after - before < UPDATES / 2);
    }

    private void refreshRemovalPendingBundles() throws InterruptedException {
        FrameworkWiring wiring = framework.adapt(FrameworkWiring.class);
        Collection<Bundle> pending = wiring.getRemovalPendingBundles();
        final CountDownLatch latch = new CountDownLatch(1);
        wiring.refreshBundles(pending, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        latch.await(30, TimeUnit.SECONDS);
    }

    private static void settle() throws InterruptedException {
        System.gc();
        Thread.sleep(300);
    }

    private static int openFileDescriptors() {
        String[] names = new File("/dev/fd").list();
        return names != null ? names.length : -1;
    }

    private static byte[] createBundle() throws Exception {
        final JavaArchive inner = ShrinkWrap.create(JavaArchive.class, "inner.jar");
        inner.addAsResource(new StringAsset("inner"), "inner.txt");
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "updated-bundle");
        archive.add(inner, "/", ZipExporter.class);
        archive.addAsResource(new StringAsset("resource"), "resource.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleClasspath(".,inner.jar");
                return builder.openStream();
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = archive.as(ZipExporter.class).exportAsInputStream()) {
            input.transferTo(out);
        }
        return out.toByteArray();
    }
}
