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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * FrameworkWiring.refreshBundles does not remove the revisions of a bundle that was uninstalled while it was in use:
 * the revision keeps its wiring (reachable through the Bundle Wiring API) after the refresh.
 *
 * <h3>Severity</h3>
 * major. The old revision with its wiring and class loader stays in the framework after the refresh that should
 * release it; in WildFly the bundle also stays removal pending and its deployment stays deployed
 * (StaleUninstalledExporterAfterRefreshIssueTestCase in the umbrella testsuite, and the failing
 * BundleUninstallTestCase.testDependentJarRedeploy "BundleWiring null").
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl#refreshBundlesInternal}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 7.5.1 Refreshing (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.wiring.html): "The
 * refresh must then unresolve all the bundles in the considered collection. Unresolving will cause all the removal
 * pending Bundle Wirings to become no longer in use because there are no longer any bundles requiring them. This will
 * make the Bundle Wirings available for garbage collection because they are then no longer reachable from the
 * framework API." BundleRevision.getWiring javadoc: "Returns the bundle wiring which is using this bundle revision ...
 * or null if no bundle wiring is using this bundle revision."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java} ({@code refreshBundlesInternal}):
 * an uninstalled bundle of the dependency closure is passed to {@code bundleManager.uninstallBundle}, and
 * {@code UserBundleState.uninstallInternal} returns at once for a bundle that is already UNINSTALLED, so its revisions
 * are never removed. Pre-existing (Thomas Diesler 2012/2013, same on WF41).
 *
 * <h3>Root cause</h3>
 * The refresh relies on the uninstall of the bundle to remove its revisions, which does nothing for a bundle that is
 * uninstalled already.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=UninstalledRevisionNotRemovedByRefreshIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: After the refresh the revision of the uninstalled exporter must have no wiring, but it has
 *   Wiring[HostBundleRevision[uninstalled-exporter:0.0.0]]
 * </pre>
 *
 * <h3>Expected</h3>
 * After the refresh the revision of the uninstalled bundle has no wiring and the bundle is not removal pending.
 *
 * <h3>Resolution</h3>
 * The refresh unresolves the uninstalled bundles of the dependency closure and removes their revisions after the bundles
 * that depend on them are refreshed.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 */
public class UninstalledRevisionNotRemovedByRefreshIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUninstalledRevisionRemovedByRefresh() throws Exception {
        FrameworkWiring wiring = getFramework().adapt(FrameworkWiring.class);
        Bundle exporter = installBundle(bundle("uninstalled-exporter", "uninstalled.exporter.api", null));
        Bundle importer = installBundle(bundle("uninstalled-importer", null, "uninstalled.exporter.api"));
        try {
            assertTrue("Precondition: the bundles resolve", wiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision revision = exporter.adapt(BundleRevision.class);

            exporter.uninstall();
            assertTrue("Precondition: the exporter is removal pending", wiring.getRemovalPendingBundles().contains(exporter));

            wiring.refreshBundles(null, this);
            assertFrameworkEvent(FrameworkEvent.PACKAGES_REFRESHED, getSystemContext().getBundle(0), null);

            assertFalse("After the refresh the exporter must not be removal pending", wiring.getRemovalPendingBundles().contains(exporter));
            assertNull("After the refresh the revision of the uninstalled exporter must have no wiring, but it has " + revision.getWiring(),
                    revision.getWiring());
        } finally {
            importer.uninstall();
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
