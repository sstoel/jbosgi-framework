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

import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

/**
 * FrameworkWiring.findProviders leaves out the capabilities of an uninstalled bundle whose revision is still in use
 * (removal pending), although the capability is still wired and used by other bundles.
 *
 * <h3>Severity</h3>
 * minor - management agents that use findProviders to analyse the current wiring (for example before a refresh) do
 * not see the provider that importers are actually wired to.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkWiringImpl.findProviders}.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of FrameworkWiring.findProviders after uninstalling an exporter without
 * refresh.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.findProviders javadoc (OSGi Core R8, org.osgi.framework.wiring 1.2): "The returned collection
 * contains BundleCapability objects where the revision must be the declaring revision of the capability and the
 * revision must either be the current bundle revision or an in use bundle revision." BundleWiring.isInUse: "A bundle
 * wiring is in use if it is the current bundle wiring or if some other in use bundle wiring is dependent upon it."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkWiringImpl.java:328-330}
 * ({@code if (bundle.getState() == Bundle.UNINSTALLED) continue;}), from commit 4d450c75, which is already on WF41.
 *
 * <h3>Root cause</h3>
 * Uninstalled bundles are skipped as a whole, including their in-use revisions that the loop below would otherwise
 * collect.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FindProvidersRemovalPendingIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testRemovalPendingProviderIsFound: findProviders must return the capability of the uninstalled but still in use
 *   exporter, but returned []
 * </pre>
 *
 * <h3>Expected</h3>
 * The package capability of the uninstalled exporter's in-use revision is returned until the bundles are refreshed.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 returns the capability of the uninstalled, removal pending revision.
 *
 * <h3>Suggested fix</h3>
 * Do not skip UNINSTALLED bundles; for them only collect the revisions whose wiring is in use (the existing loop over
 * {@code BundleRevisions} already does that; the current revision of an uninstalled bundle needs the same in-use
 * check).
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 has the same code; still a javadoc violation on the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class FindProvidersRemovalPendingIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRemovalPendingProviderIsFound() throws Exception {
        Bundle exporter = installBundle(createBundle("pending-exporter", "org.acme.pending", null));
        Bundle importer = installBundle(createBundle("pending-importer", null, "org.acme.pending"));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            BundleRevision exporterRevision = exporter.adapt(BundleRevision.class);

            exporter.uninstall();
            assertTrue("The exporter is removal pending", frameworkWiring.getRemovalPendingBundles().contains(exporter));
            assertTrue("The exporter revision is still in use", exporterRevision.getWiring() != null && exporterRevision.getWiring().isInUse());

            Collection<BundleCapability> caps = frameworkWiring.findProviders(packageRequirement("org.acme.pending"));
            boolean found = false;
            for (BundleCapability cap : caps)
                found |= cap.getRevision() == exporterRevision;
            assertTrue("findProviders must return the capability of the uninstalled but still in use exporter, but returned " + caps, found);
        } finally {
            importer.uninstall();
            if (exporter.getState() != Bundle.UNINSTALLED)
                exporter.uninstall();
            refreshBundles(null);
        }
    }

    private Requirement packageRequirement(final String packageName) {
        return new Requirement() {
            public String getNamespace() {
                return PackageNamespace.PACKAGE_NAMESPACE;
            }

            public Map<String, String> getDirectives() {
                return Collections.singletonMap(Namespace.REQUIREMENT_FILTER_DIRECTIVE, "(" + PackageNamespace.PACKAGE_NAMESPACE + "=" + packageName + ")");
            }

            public Map<String, Object> getAttributes() {
                return Collections.emptyMap();
            }

            public Resource getResource() {
                return null;
            }
        };
    }

    private JavaArchive createBundle(final String name, final String exports, final String imports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (exports != null)
                    builder.addExportPackages(exports);
                if (imports != null)
                    builder.addImportPackages(imports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
