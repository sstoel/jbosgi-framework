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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

/**
 * BundleContext.getBundle(long) does not find a bundle after Bundle.update() when nothing uses the bundle, nor after a
 * refresh of an updated bundle: the bundle is still installed and listed by getBundles(), but its id is unknown. Aries
 * JMX and the management model of the server look bundles up by id, so they lose the bundle: 10 tests of the JMX TCK
 * fail. An uninstalled bundle whose revision is still in use is found by its id, where Equinox and Felix return null.
 *
 * <h2>Severity</h2>
 * major. A wrong result of a public API in a normal path; an id based client cannot manage or uninstall the bundle.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.getBundleById}, {@code AbstractBundleContext.getBundle(long)}.
 *
 * <h2>Found by</h2>
 * R8 release check, S1 of the soak and TCK-1 of the TCK run (jmx7 FrameworkMBeanTestCase); TCK-3.
 *
 * <h2>Spec reference</h2>
 * BundleContext.getBundle(long): the bundle that has the given identifier, or null when no bundle with that identifier
 * is installed. An updated bundle keeps its identifier (4.4.5).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java getBundleById (84ac5c2e): the bundle
 * was looked up by the resource id of a revision. Only the first revision has the bundle id as its resource id; since
 * dc5d2881 an update removes that revision at once when nothing uses it, a refresh removed it before.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=BundleByIdAfterUpdateIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * getBundle(id) returns the bundle after an update while it is INSTALLED, RESOLVED or ACTIVE and after a refresh, and
 * null after its uninstall, also while an importer still uses its revision.
 *
 * <h2>Regression vs WF41</h2>
 * yes for the update (dc5d2881), no for the refresh.
 */
public class BundleByIdAfterUpdateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUpdateOfInstalledBundle() throws Exception {
        Bundle bundle = installBundle(getBundle("byid-installed", "1.0", null));
        try {
            long id = bundle.getBundleId();
            bundle.update(toInputStream(getBundle("byid-installed", "2.0", null)));
            assertFound(id, bundle, "2.0");
            refreshBundles(Collections.singleton(bundle));
            assertFound(id, bundle, "2.0");
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testUpdateOfResolvedAndActiveBundle() throws Exception {
        Bundle resolved = installBundle(getBundle("byid-resolved", "1.0", null));
        Bundle active = installBundle(getBundle("byid-active", "1.0", null));
        try {
            assertEquals(true, resolveBundles(Arrays.asList(resolved, active)));
            active.start();
            resolved.update(toInputStream(getBundle("byid-resolved", "2.0", null)));
            active.update(toInputStream(getBundle("byid-active", "2.0", null)));
            assertFound(resolved.getBundleId(), resolved, "2.0");
            assertFound(active.getBundleId(), active, "2.0");
            assertEquals(Bundle.ACTIVE, active.getState());

            // A second update and a refresh remove the revision that the bundle had before
            active.update(toInputStream(getBundle("byid-active", "3.0", null)));
            refreshBundles(Arrays.asList(resolved, active));
            assertFound(resolved.getBundleId(), resolved, "2.0");
            assertFound(active.getBundleId(), active, "3.0");
        } finally {
            resolved.uninstall();
            active.uninstall();
        }
    }

    @Test
    public void testUpdateOfBundleInUse() throws Exception {
        Bundle exporter = installBundle(getBundle("byid-exporter", "1.0", null));
        Bundle importer = installBundle(getBundle("byid-importer", "1.0", "org.jboss.test.osgi.byid"));
        try {
            importer.start();
            long id = exporter.getBundleId();
            exporter.update(toInputStream(getBundle("byid-exporter", "2.0", null)));
            assertFound(id, exporter, "2.0");
            refreshBundles(Collections.singleton(exporter));
            assertFound(id, exporter, "2.0");

            // An uninstalled bundle is not found, also while its revision is in use (removal pending)
            importer.stop();
            exporter.uninstall();
            assertNull("An uninstalled bundle is not found by its id", getSystemContext().getBundle(id));
        } finally {
            importer.uninstall();
            if (exporter.getState() != Bundle.UNINSTALLED)
                exporter.uninstall();
        }
    }

    private void assertFound(long id, Bundle bundle, String version) throws Exception {
        BundleContext context = getSystemContext();
        assertSame("getBundle(" + id + ") after the update of " + bundle, bundle, context.getBundle(id));
        assertEquals(Version.parseVersion(version), context.getBundle(id).getVersion());
    }

    private JavaArchive getBundle(final String symbolicName, final String version, final String importPackage) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName + "-" + version);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleVersion(version);
                if (importPackage != null)
                    builder.addImportPackages(importPackage);
                if (symbolicName.equals("byid-exporter"))
                    builder.addExportPackages("org.jboss.test.osgi.byid");
                return builder.openStream();
            }
        });
        return archive;
    }
}
