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

import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;

/**
 * BundleRevision.getDeclaredRequirements of a bundle that is not the system bundle returns a list that can be
 * modified.
 *
 * <h2>Severity</h2>
 * cosmetic. As services/15, for the revisions of the installed bundles.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code HostBundleRevision.getDeclaredRequirements}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of services/15 (fw-small-11).
 *
 * <h2>Spec reference</h2>
 * BundleRevision.getDeclaredRequirements: "An unmodifiable list".
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/HostBundleRevision.java:142-147 (8c3905ef).
 *
 * <h2>Root cause</h2>
 * The override of the host revision returned its own ArrayList.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=HostDeclaredRequirementsModifiableIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * clear() throws UnsupportedOperationException.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class HostDeclaredRequirementsModifiableIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testDeclaredRequirementsOfHostUnmodifiable() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "host-declared-requirements");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(null);
            try {
                reqs.clear();
            } catch (UnsupportedOperationException ex) {
                return;
            }
            fail("getDeclaredRequirements must return an unmodifiable list, but clear() succeeded");
        } finally {
            bundle.uninstall();
        }
    }
}
