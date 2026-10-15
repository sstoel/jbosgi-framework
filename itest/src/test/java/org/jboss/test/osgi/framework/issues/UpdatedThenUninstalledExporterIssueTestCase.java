package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Uninstalling a bundle whose previous (updated) revision is still in use drops that revision at once: the bundle is
 * not removal pending, the old revision has no wiring any more although its importers are still wired to it, and
 * refreshBundles(null) never refreshes those importers.
 *
 * <h3>Severity</h3>
 * major - "update, then uninstall" is the normal life of a library bundle that is replaced and later removed (or a
 * WildFly redeploy followed by an undeploy). Its importers keep a wire to a revision the framework has forgotten:
 * getRemovalPendingBundles does not report it, the standard repair {@code refreshBundles(null)} does nothing, the
 * importers stay ACTIVE on a provider without wiring and class loader (classes of the package not loaded yet are
 * gone), until somebody refreshes exactly those importers.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#uninstallInternal}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 5): {@code fa.WiringModel} (seed 1, 200 steps, fragments and
 * Require-Bundle off) against Equinox 3.21 and Felix 7.0.5: step 138, {@code uninstall w0} after {@code update w0}
 * while w2 was wired to the old revision: Equinox/Felix getRemovalPendingBundles [w0, w5], jbosgi [w5]. Minimised
 * with the probe {@code fa.ProbeUpdUninst}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.10 Uninstalling Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "If the uninstalled bundle has one
 * or more revisions with isInUse() bundle wirings then all capabilities provided by the old in use bundle wirings must
 * remain available for existing bundles and future resolves until the bundle is refreshed".<br>
 * FrameworkWiring.getRemovalPendingBundles javadoc: "Returns the bundles that have non-current, in use bundle
 * wirings."; refreshBundles javadoc: "@param bundles The bundles to be refreshed, or {@code null} to refresh the
 * removal pending bundles."<br>
 * BundleWiring.isInUse: "A bundle wiring is in use if it is the current bundle wiring or if some other in use bundle
 * wiring is dependent upon it."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java:465-468}
 * ({@code boolean activeWires = hasActiveWiresWhileUninstalling(); if (activeWires == false)
 * bundleManager.unresolveBundle(this);}) and {@code :486-489} ({@code if (activeWires == false)
 * bundleManager.removeBundle(this, options);}) with {@code :841-844}: {@code hasActiveWiresWhileUninstalling} only
 * looks at {@code getBundleWiring()}, the wiring of the current revision - after an update that is the new,
 * unresolved revision without wiring, so the in-use old revision is unresolved and removed with the bundle.
 * Pre-existing (cc7bb0eb/21ffe22e, 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The "still in use" decision at uninstall considers the current revision only, not the bundle's other (removal
 * pending) revisions.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=UpdatedThenUninstalledExporterIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testUninstalledBundleStaysRemovalPending: The uninstalled exporter whose old revision is still in use must be
 *   removal pending
 * testOldRevisionKeepsItsWiring: The old revision is still in use by the importer, it must keep its wiring
 *   (BundleRevision.getWiring() returned null)
 * testRefreshAllRefreshesImporter: refreshBundles(null) must refresh the importer of the removed exporter
 *   (the importer must no longer be wired to the old revision) expected:&lt;[INSTALLED]&gt; but was:&lt;[ACTIVE]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * After the uninstall the exporter is removal pending, its old revision keeps its in-use wiring, and
 * refreshBundles(null) refreshes the importer (which then stays INSTALLED, nothing exports the package any more).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: getRemovalPendingBundles = [x (UNINSTALLED)], the old wiring is in use, the importer
 * still loads the package's classes; refreshBundles(null) stops and unresolves the importer (STOPPING, STOPPED,
 * UNRESOLVED; INSTALLED afterwards).
 *
 * <h3>Suggested fix</h3>
 * In uninstallInternal decide "in use" over all revisions of the bundle ({@code BundleRevisions.getRevisions()},
 * any wiring with {@code isInUseForUninstall()}), and only unresolve/remove the revisions that are not in use.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class UpdatedThenUninstalledExporterIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUninstalledBundleStaysRemovalPending() throws Exception {
        Bundle exporter = installBundle(getExporter("upd-uninst-pending"));
        Bundle importer = installBundle(getImporter("upd-uninst-pending-importer"));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            exporter.update(toInputStream(getExporter("upd-uninst-pending")));
            assertTrue(frameworkWiring.getRemovalPendingBundles().contains(exporter));
            exporter.uninstall();
            assertTrue("The uninstalled exporter whose old revision is still in use must be removal pending", frameworkWiring.getRemovalPendingBundles().contains(exporter));
        } finally {
            importer.uninstall();
            refreshBundles(null);
        }
    }

    @Test
    public void testOldRevisionKeepsItsWiring() throws Exception {
        Bundle exporter = installBundle(getExporter("upd-uninst-wiring"));
        Bundle importer = installBundle(getImporter("upd-uninst-wiring-importer"));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision oldRevision = exporter.adapt(BundleRevision.class);
            exporter.update(toInputStream(getExporter("upd-uninst-wiring")));
            exporter.uninstall();
            assertSame("The importer is still wired to the old revision", oldRevision,
                    importer.adapt(BundleWiring.class).getRequiredWires(BundleRevision.PACKAGE_NAMESPACE).get(0).getProvider());
            BundleWiring oldWiring = oldRevision.getWiring();
            assertNotNull("The old revision is still in use by the importer, it must keep its wiring (BundleRevision.getWiring() returned null)", oldWiring);
            assertTrue("The old revision's wiring must be in use", oldWiring.isInUse());
        } finally {
            importer.uninstall();
            refreshBundles(null);
        }
    }

    @Test
    public void testRefreshAllRefreshesImporter() throws Exception {
        Bundle exporter = installBundle(getExporter("upd-uninst-refresh"));
        Bundle importer = installBundle(getImporter("upd-uninst-refresh-importer"));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            importer.start();
            exporter.update(toInputStream(getExporter("upd-uninst-refresh")));
            exporter.uninstall();
            refreshBundles(null);
            assertEquals("refreshBundles(null) must refresh the importer of the removed exporter (the importer must no longer be wired to the old revision)", "INSTALLED", stateName(importer.getState()));
        } finally {
            importer.uninstall();
            refreshBundles(null);
        }
    }

    private static String stateName(int state) {
        switch (state) {
        case Bundle.INSTALLED: return "INSTALLED";
        case Bundle.RESOLVED: return "RESOLVED";
        case Bundle.ACTIVE: return "ACTIVE";
        default: return "STATE" + state;
        }
    }

    private JavaArchive getExporter(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addExportPackages(A.class);
                return builder.openStream();
            }
        });
        return archive;
    }

    private JavaArchive getImporter(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addImportPackages(A.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}
