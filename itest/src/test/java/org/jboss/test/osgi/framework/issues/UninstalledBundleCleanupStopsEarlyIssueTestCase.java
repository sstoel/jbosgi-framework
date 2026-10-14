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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * When a bundle is uninstalled, the framework removes the other uninstalled bundles that are not in use any more, but
 * it stops at the first uninstalled bundle that is still in use: the bundles after it in the (hash) set stay in the
 * framework with their revision and wiring.
 *
 * <h3>Severity</h3>
 * major. A single uninstalled bundle that stays in use (for example an undeployed framework extension, which stays
 * attached until the framework is launched again, or any bundle uninstalled while in use) makes the removal of the
 * other uninstalled bundles a matter of hash order: their revisions, wirings and class loaders stay in memory, in
 * WildFly their deployments stay deployed. Made the umbrella BundleUninstallTestCase (testDependentJarUninstall,
 * testDependentJarRedeploy "BundleWiring null") fail depending on the tests that ran before it.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState#uninstallInternal}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.10 Uninstalling Bundles (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * an uninstalled bundle's old revisions stay available only while they are in use ("must remain available for
 * existing bundles and future resolves until the bundle is refreshed"); BundleRevision.getWiring javadoc: "Returns
 * the bundle wiring which is using this bundle revision ... or null if no bundle wiring is using this bundle
 * revision."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java} ({@code uninstallInternal}, "Remove
 * other uninstalled bundles that now also are not in use any more"): the {@code break} after
 * {@code bundleInUse = true} leaves the loop over all uninstalled bundles instead of skipping only the one in use.
 * Pre-existing (Thomas Diesler 2013, same on WF41).
 *
 * <h3>Root cause</h3>
 * {@code break} instead of skipping the bundle that is in use.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=UninstalledBundleCleanupStopsEarlyIssueTestCase} (in jbosgi-framework). The order of the
 * uninstalled bundles is a hash order; with 20 uninstalled bundles in use the test fails with a probability of 20/21.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The uninstalled exporter is not in use any more and must be removed, but its revision still
 *   has the wiring Wiring[HostBundleRevision[cleanup-exporter:0.0.0]]
 * </pre>
 *
 * <h3>Expected</h3>
 * Every uninstalled bundle that is not in use any more is removed, whatever other uninstalled bundles are in use.
 *
 * <h3>Resolution</h3>
 * Only the bundle that is in use is skipped.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class UninstalledBundleCleanupStopsEarlyIssueTestCase extends OSGiFrameworkTest {

    static final int IN_USE = 20;

    @Test
    public void testUninstalledBundleRemovedWhenOthersInUse() throws Exception {
        FrameworkWiring wiring = getFramework().adapt(FrameworkWiring.class);
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            // Uninstalled exporters that stay in use by their importers
            for (int i = 0; i < IN_USE; i++) {
                Bundle exporter = installBundle(bundle("inuse-exporter-" + i, "inuse.api" + i, null));
                Bundle importer = installBundle(bundle("inuse-importer-" + i, null, "inuse.api" + i));
                assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));
                exporter.uninstall();
                bundles.add(importer);
            }

            // An uninstalled exporter whose only importer is uninstalled next
            Bundle exporter = installBundle(bundle("cleanup-exporter", "cleanup.api", null));
            Bundle importer = installBundle(bundle("cleanup-importer", null, "cleanup.api"));
            assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision revision = exporter.adapt(BundleRevision.class);
            exporter.uninstall();
            assertTrue("Precondition: the exporter is still in use", revision.getWiring() != null && revision.getWiring().isInUse());

            importer.uninstall();
            assertNull("The uninstalled exporter is not in use any more and must be removed, but its revision still has the wiring " + revision.getWiring(),
                    revision.getWiring());
        } finally {
            for (Bundle bundle : bundles)
                bundle.uninstall();
        }
    }

    private static JavaArchive bundle(final String name, final String exportPackage, final String importPackage) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addAsResource(new StringAsset(name), (exportPackage != null ? exportPackage.replace('.', '/') + "/" : name + "/") + "marker.txt");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(name);
                builder.addBundleManifestVersion(2);
                if (exportPackage != null)
                    builder.addExportPackages(exportPackage);
                if (importPackage != null)
                    builder.addImportPackages(importPackage);
                return builder.openStream();
            }
        });
        return archive;
    }
}
