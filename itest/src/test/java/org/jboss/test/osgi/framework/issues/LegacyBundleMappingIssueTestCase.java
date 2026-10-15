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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Namespace;

/**
 * Release 3 bundles (no Bundle-ManifestVersion) are not mapped as the spec requires: an exported package is not
 * also imported, the export has no uses directive, and the bundle gets osgi.wiring.bundle and osgi.wiring.host
 * capabilities (named {@code anonymous-bundle-symbolic-name}) that only Bundle-ManifestVersion 2 bundles may have.
 *
 * <h3>Severity</h3>
 * minor (legacy bundles). Without the implicit import an R3 bundle always uses its own copy of the package and
 * cannot be substituted, which breaks class space consistency between R3 bundles exporting the same package (the
 * R3 semantics this mapping preserves). Any bundle can {@code Require-Bundle: anonymous-bundle-symbolic-name}.
 *
 * <h3>Component</h3>
 * jbosgi-framework {@code BundleRevisionValidatorR3} / {@code UserBundleRevisionFactory} (no R3 mapping step) and
 * jbosgi-resolver {@code AbstractResourceBuilder.loadFrom} (bundle/host capabilities for every manifest version).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code v-r3},
 * {@code v-r3-r4hdr}, {@code bsnver-r3}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.6.7 Interpretation of Legacy Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "Bundles that are not marked with a
 * Bundle-ManifestVersion that equals 2 or more must treat the headers according the definitions in the Release 3.
 * ... Export-Package - An export definition must change the specification-version attribute to the version
 * attribute. The export definition must be appended with the uses directive. The uses directive must contain all
 * imported and exported packages for the given bundle. Additionally, if there is no import definition for this
 * package, then an import definition for this package with the package version must be added."<br>
 * 8.4 osgi.wiring.bundle Namespace (framework.namespaces.html): "A bundle must only provide this capability when
 * the Bundle-ManifestVersion &gt;= 2."; 8.5 osgi.wiring.host: "A bundle must only provide this capability when the
 * fragment-attachment directive allows fragments to attach and the Bundle-ManifestVersion &gt;= 2."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleRevisionValidatorR3.java:39-43}
 * (only checks the manifest version, nothing maps the headers); jbosgi-resolver
 * {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResourceBuilder.java:187-201} (bundle and host
 * capability added regardless of Bundle-ManifestVersion, with {@code ANONYMOUS_BUNDLE_SYMBOLIC_NAME}). Pre-existing.
 *
 * <h3>Root cause</h3>
 * The R3 to R4 mapping of 3.6.7 is not implemented; loadFrom does not look at the manifest version.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=LegacyBundleMappingIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testExportImpliesImport: The R3 export of leg.p must add an import of leg.p, but the package requirements are
 *   [(osgi.wiring.package=leg.q)]
 * testExportHasUsesDirective: The R3 export of leg.p must have a uses directive with leg.p and leg.q, but it was null
 * testNoBundleAndHostCapability: An R3 bundle must not provide osgi.wiring.bundle capabilities
 *   expected:&lt;[]&gt; but was:&lt;[[anonymous-bundle-symbolic-name:0.0.0]...]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * A requirement {@code (&(osgi.wiring.package=leg.p)(version>=1.2.0))}, {@code uses:="leg.p,leg.q"} on the export,
 * and no osgi.wiring.bundle / osgi.wiring.host capability.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 adds the import and provides no bundle/host capability (no uses directive either); Felix 7.0.5
 * adds the import and a uses directive and provides no bundle/host capability.
 *
 * <h3>Suggested fix</h3>
 * When Bundle-ManifestVersion is 1, add the implicit package requirements and the uses directive while building
 * the resource (AbstractResourceBuilder.loadFrom or a metadata pre-processing step), and add the bundle and host
 * capabilities only for manifest version 2. Verified: doing this in AbstractResourceBuilder.loadFrom (uses with all
 * imported and exported packages on every export, an import with the export's version for every package that is
 * not imported, no bundle/host capability when Bundle-ManifestVersion &lt; 2) makes the three test methods pass
 * (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class LegacyBundleMappingIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testExportImpliesImport() throws Exception {
        Bundle bundle = installBundle(getLegacyArchive("legacy-import"));
        try {
            List<String> filters = new ArrayList<String>();
            for (BundleRequirement req : bundle.adapt(BundleRevision.class).getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE))
                filters.add(req.getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE));
            boolean found = false;
            for (String filter : filters)
                found |= filter != null && filter.contains("osgi.wiring.package=leg.p");
            assertTrue("The R3 export of leg.p must add an import of leg.p, but the package requirements are " + filters, found);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testExportHasUsesDirective() throws Exception {
        Bundle bundle = installBundle(getLegacyArchive("legacy-uses"));
        try {
            List<BundleCapability> caps = bundle.adapt(BundleRevision.class).getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE);
            assertEquals("One export", 1, caps.size());
            String uses = caps.get(0).getDirectives().get(Namespace.CAPABILITY_USES_DIRECTIVE);
            assertTrue("The R3 export of leg.p must have a uses directive with leg.p and leg.q, but it was " + uses,
                    uses != null && uses.contains("leg.p") && uses.contains("leg.q"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testNoBundleAndHostCapability() throws Exception {
        Bundle bundle = installBundle(getLegacyArchive("legacy-caps"));
        try {
            BundleRevision rev = bundle.adapt(BundleRevision.class);
            assertEquals("An R3 bundle must not provide osgi.wiring.bundle capabilities", new ArrayList<Object>(),
                    new ArrayList<Object>(rev.getDeclaredCapabilities(BundleNamespace.BUNDLE_NAMESPACE)));
            assertEquals("An R3 bundle must not provide osgi.wiring.host capabilities", new ArrayList<Object>(),
                    new ArrayList<Object>(rev.getDeclaredCapabilities(HostNamespace.HOST_NAMESPACE)));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getLegacyArchive(String name) {
        // A Release 3 manifest: no Bundle-ManifestVersion, no Bundle-SymbolicName
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Bundle-Name", name);
        attributes.putValue("Export-Package", "leg.p;specification-version=1.2");
        attributes.putValue("Import-Package", "leg.q");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            manifest.write(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes.toByteArray());
            }
        });
        return archive;
    }
}
