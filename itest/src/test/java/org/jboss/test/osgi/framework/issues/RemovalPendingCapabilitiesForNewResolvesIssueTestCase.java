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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Capabilities of a removal pending (updated or uninstalled, still in use) bundle revision are not available to future
 * resolves: a new importer cannot resolve against the old revision, and when the new revision exports the same
 * package the unresolved new revision is chosen (and resolved) instead of the resolved old one.
 *
 * <h3>Severity</h3>
 * major - between an update/uninstall of a library bundle and the refresh (which in WildFly and with most management
 * agents is not immediate, and never happens if nobody calls refreshBundles) bundles installed or started afterwards
 * fail to resolve although the package they import is still exported and in use (uninstall, or an update that drops
 * the package), or they are wired to the new revision while the existing importers stay on the old one: the same
 * package is then served by two class loaders (ClassCastException / LinkageError between old and new importers,
 * "uses" constraints between them can no longer hold), which is what the specification's "remain available for
 * future resolves" avoids.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractEnvironment#findProviders} (used by the jbosgi
 * framework resolver for every static resolve).
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 5): seeded stateful wiring differential {@code fa.WiringModel} (6
 * bundles with 3 content variants each; install/update/uninstall/refresh(subset|null)/resolve/start/stop/load; after
 * every step getRemovalPendingBundles, BundleRevisions, wiring isCurrent/isInUse, required/provided wires of every
 * revision with both-sides consistency checks, capabilities) against Equinox 3.21 and Felix 7.0.5. Seed 2 step 93:
 * after an update of an exporter that importers still used, the re-resolved importer was wired to the new revision
 * (Equinox and Felix: old revision). Minimised with the probe {@code fa.ProbePending} (update with the same export,
 * with a higher package version, without the export, and uninstall; resolve of a new importer).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.4.9 Updating Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "If the old bundle revision has an
 * isInUse() bundle wiring then all capabilities provided by the old bundle wiring must remain available for existing
 * bundles and future resolves until the bundle is refreshed, see Refreshing, or the Framework is restarted."<br>
 * 4.4.10 Uninstalling Bundles: "If the uninstalled bundle has one or more revisions with isInUse() bundle wirings then
 * all capabilities provided by the old in use bundle wirings must remain available for existing bundles and future
 * resolves until the bundle is refreshed".<br>
 * 3.8 (preferences): "A resolved exporter must be preferred over an unresolved exporter."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:232-236}
 * ({@code // Do not allow new wires to unresolved resources ... if (!wiringSupport.isEffective()) continue;}) - the
 * update and uninstall paths ({@code UserBundleState.java:289} and {@code :474},
 * {@code getWiringSupport().makeUneffective()}) mark the old revision non-effective, so its capabilities are dropped
 * from every later findProviders although the revision is still resolved and in use. Pre-existing (1714bf9 / 43d16c8,
 * 2013); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * "Not effective" (removal pending) is treated like "unresolved": the check meant to keep unresolved revisions from
 * being wired also excludes resolved, in use, removal pending revisions.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RemovalPendingCapabilitiesForNewResolvesIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testNewImporterAfterUninstall: A new importer must resolve against the uninstalled, still in use exporter
 * testNewImporterAfterUpdateWithoutExport: A new importer must resolve against the old (removal pending) revision,
 *   whose export is still in use
 * testResolvedOldRevisionPreferred: The new importer must be wired to the resolved old revision (resolved exporter
 *   preferred, capabilities remain available until refresh), not to the unresolved new revision
 *   expected same:&lt;HostBundleRevision[removal-pending-new-prefer:0.0.0]&gt; was not:&lt;HostBundleRevision[
 *   removal-pending-new-prefer:0.0.0]&gt; (the old and the new revision print the same)
 * </pre>
 *
 * <h3>Expected</h3>
 * The new importer resolves and is wired to the old (removal pending) revision in all three cases, until the exporter
 * is refreshed.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: the new importer resolves to the old revision in all cases (also when the new revision
 * exports a higher package version).
 *
 * <h3>Suggested fix</h3>
 * In findProviders only skip capabilities of revisions that are neither effective nor resolved: keep the capabilities
 * of revisions whose (non-current) wiring is still in use; the preference comparator then puts them first because
 * they are wired.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class RemovalPendingCapabilitiesForNewResolvesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testNewImporterAfterUninstall() throws Exception {
        Bundle exporter = installBundle(getExporter("removal-pending-new-uninstall", true));
        Bundle importer = installBundle(getImporter("removal-pending-new-uninstall-i1"));
        Bundle newImporter = null;
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            exporter.uninstall();
            assertTrue(frameworkWiring.getRemovalPendingBundles().contains(exporter));
            newImporter = installBundle(getImporter("removal-pending-new-uninstall-i2"));
            assertTrue("A new importer must resolve against the uninstalled, still in use exporter", frameworkWiring.resolveBundles(Arrays.asList(newImporter)));
        } finally {
            if (newImporter != null)
                newImporter.uninstall();
            importer.uninstall();
            refreshBundles(null);
        }
    }

    @Test
    public void testNewImporterAfterUpdateWithoutExport() throws Exception {
        Bundle exporter = installBundle(getExporter("removal-pending-new-update", true));
        Bundle importer = installBundle(getImporter("removal-pending-new-update-i1"));
        Bundle newImporter = null;
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            exporter.update(toInputStream(getExporter("removal-pending-new-update", false)));
            newImporter = installBundle(getImporter("removal-pending-new-update-i2"));
            assertTrue("A new importer must resolve against the old (removal pending) revision, whose export is still in use", frameworkWiring.resolveBundles(Arrays.asList(newImporter)));
        } finally {
            if (newImporter != null)
                newImporter.uninstall();
            importer.uninstall();
            exporter.uninstall();
            refreshBundles(null);
        }
    }

    @Test
    public void testResolvedOldRevisionPreferred() throws Exception {
        Bundle exporter = installBundle(getExporter("removal-pending-new-prefer", true));
        Bundle importer = installBundle(getImporter("removal-pending-new-prefer-i1"));
        Bundle newImporter = null;
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision oldRevision = exporter.adapt(BundleRevision.class);
            exporter.update(toInputStream(getExporter("removal-pending-new-prefer", true)));
            newImporter = installBundle(getImporter("removal-pending-new-prefer-i2"));
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(newImporter)));
            List<BundleWire> wires = newImporter.adapt(BundleWiring.class).getRequiredWires(BundleRevision.PACKAGE_NAMESPACE);
            assertEquals(1, wires.size());
            assertSame("The new importer must be wired to the resolved old revision (resolved exporter preferred, capabilities remain available until refresh), not to the unresolved new revision",
                    oldRevision, wires.get(0).getProvider());
        } finally {
            if (newImporter != null)
                newImporter.uninstall();
            importer.uninstall();
            exporter.uninstall();
            refreshBundles(null);
        }
    }

    private JavaArchive getExporter(final String name, final boolean export) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (export)
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
