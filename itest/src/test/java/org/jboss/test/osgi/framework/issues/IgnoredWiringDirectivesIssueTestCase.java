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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Namespace;

/**
 * Directives that the osgi.wiring.* namespaces say must be ignored on Import-Package, Require-Bundle and
 * Fragment-Host ({@code effective}, {@code cardinality}), and a {@code resolution} directive on
 * DynamicImport-Package, are copied into the bundle's requirements and change how the bundle resolves.
 *
 * <h3>Severity</h3>
 * minor. {@code Import-Package: p;effective:=active} or {@code Require-Bundle: b;effective:=active} silently drop
 * the dependency (the bundle resolves without it and fails later with NoClassDefFoundError);
 * {@code Fragment-Host: h;effective:=active} prevents the fragment from ever attaching;
 * {@code Import-Package: p;cardinality:=multiple} makes the bundle unresolvable as soon as two bundles export p;
 * {@code DynamicImport-Package: p;resolution:=optional} turns the dynamic import into an optional static import
 * that is wired at resolve time, and {@code resolution:=mandatory} makes the bundle unresolvable when p is absent.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractResourceBuilder.loadFrom(OSGiMetaData)}, which
 * puts all directives of every manifest clause into the requirement ({@code req.getDirectives().putAll(...)}).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code ign-imp-effective},
 * {@code ign-rb-effective}, {@code ign-fh-effective}, {@code ign-imp-cardinality}, {@code ign-dyn-resolution},
 * {@code ign-dyn-resolution-mand}, {@code dyn-09}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 8.1 (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html): "All
 * directives and attributes specified on the OSGi manifest headers that are translated to requirements and
 * capabilities are visible in these capabilities and requirements, except when they are specifically noted to
 * be ignored in the definition of a namespace."<br>
 * 8.3: "DynamicImport-Package clauses have a resolution directive set to dynamic ... The following directives must
 * be ignored when specified on the Import-Package, or Export-Package, or DynamicImport-Package manifest headers:
 * effective cardinality".<br>
 * 8.4: "The following directives must be ignored when specified on the Require-Bundle or Bundle-SymbolicName
 * manifest headers: uses effective cardinality".<br>
 * 8.5: "The following directives must be ignored when specified on the Fragment-Host or Bundle-SymbolicName
 * manifest headers: uses effective cardinality".<br>
 * 3.9.2 Dynamic Import Package (framework.module.html): "No directives are architected by the Framework for
 * DynamicImport-Package."
 *
 * <h3>Also shown (merged from services/17, same root cause)</h3>
 * The directives of the Bundle-SymbolicName clause (uses, effective) are copied into the osgi.wiring.bundle and
 * osgi.wiring.host capabilities, although Core R8 8.4/8.5 say they "must be ignored when specified on the Require-Bundle or
 * Bundle-SymbolicName manifest headers" (BundleNamespace/HostNamespace javadoc). Test methods:
 * testBundleCapabilityDirectives, testHostCapabilityDirectives.
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResourceBuilder.java:209}
 * (Fragment-Host), {@code :219} (Require-Bundle), {@code :238} (Export-Package), {@code :256} (Import-Package),
 * {@code :264-271} (DynamicImport-Package: resolution=dynamic is set first and then overwritten by the clause's
 * directives); the same copying puts uses/effective on the bundle, host and identity capabilities
 * ({@code :180-200}).
 *
 * <h3>Root cause</h3>
 * loadFrom copies the directive map of each clause unfiltered.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=IgnoredWiringDirectivesIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testImportPackageEffective: Import-Package ign.nosuch;effective:=active is a mandatory import of a package that
 *   nobody exports, the bundle must not resolve
 * testRequireBundleEffective: Require-Bundle ign.nosuch;effective:=active is a mandatory requirement on a bundle
 *   that is not installed, the bundle must not resolve
 * testFragmentHostEffective: The fragment with Fragment-Host ign.host;effective:=active must attach to its host
 *   expected:&lt;4&gt; but was:&lt;2&gt;
 * testImportPackageCardinality: Import-Package ign.p;cardinality:=multiple must resolve against one of the two
 *   exporters expected:&lt;4&gt; but was:&lt;2&gt;
 * testDynamicImportResolutionOptional: The requirement of DynamicImport-Package ign.dyn;resolution:=optional must
 *   have resolution:=dynamic expected:&lt;[dynamic]&gt; but was:&lt;[optional]&gt;
 * testDynamicImportResolutionMandatory: The bundle with DynamicImport-Package ign.nosuch;resolution:=mandatory must
 *   resolve, a dynamic import is not needed for resolving
 * </pre>
 *
 * <h3>Expected</h3>
 * The ignored directives have no effect: the first two bundles do not resolve, the fragment attaches, the
 * importer resolves with one package wire, and the dynamic imports keep resolution:=dynamic (no wire at resolve
 * time, no resolution failure).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 behaves as expected in all six cases (differential harness, JDK 25). Felix 7.0.5 shares the
 * effective/cardinality defects but keeps dynamic imports dynamic.
 *
 * <h3>Suggested fix</h3>
 * In loadFrom drop {@code effective} and {@code cardinality} (and {@code uses} for the bundle/host capabilities)
 * from the directives copied for Import-Package, Export-Package, DynamicImport-Package, Require-Bundle,
 * Fragment-Host and Bundle-SymbolicName, and put {@code resolution:=dynamic} after copying the
 * DynamicImport-Package directives (or ignore its directives). Verified: removing effective and cardinality
 * after the putAll calls for Fragment-Host, Require-Bundle, Export-Package, Import-Package and
 * DynamicImport-Package and re-putting resolution:=dynamic for the latter makes all six test methods pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class IgnoredWiringDirectivesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testImportPackageEffective() throws Exception {
        Bundle bundle = install("ign-imp", "Import-Package", "ign.nosuch;effective:=active");
        try {
            assertFalse("Import-Package ign.nosuch;effective:=active is a mandatory import of a package that nobody exports, the bundle must not resolve",
                    resolve(bundle));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testRequireBundleEffective() throws Exception {
        Bundle bundle = install("ign-rb", "Require-Bundle", "ign.nosuch;effective:=active");
        try {
            assertFalse("Require-Bundle ign.nosuch;effective:=active is a mandatory requirement on a bundle that is not installed, the bundle must not resolve",
                    resolve(bundle));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFragmentHostEffective() throws Exception {
        Bundle host = install("ign.host", null, null);
        Bundle fragment = install("ign-frag", "Fragment-Host", "ign.host;effective:=active");
        try {
            resolve(host, fragment);
            assertEquals("The fragment with Fragment-Host ign.host;effective:=active must attach to its host", Bundle.RESOLVED, fragment.getState());
        } finally {
            fragment.uninstall();
            host.uninstall();
        }
    }

    @Test
    public void testImportPackageCardinality() throws Exception {
        Bundle exporterA = install("ign-exp-a", "Export-Package", "ign.p");
        Bundle exporterB = install("ign-exp-b", "Export-Package", "ign.p");
        Bundle importer = install("ign-imp-card", "Import-Package", "ign.p;cardinality:=multiple");
        try {
            resolve(exporterA, exporterB, importer);
            assertEquals("Import-Package ign.p;cardinality:=multiple must resolve against one of the two exporters", Bundle.RESOLVED, importer.getState());
            assertEquals("One wire for the import", 1, importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).size());
        } finally {
            importer.uninstall();
            exporterB.uninstall();
            exporterA.uninstall();
        }
    }

    @Test
    public void testDynamicImportResolutionOptional() throws Exception {
        Bundle exporter = install("ign-dyn-exp", "Export-Package", "ign.dyn");
        Bundle importer = install("ign-dyn-imp", "DynamicImport-Package", "ign.dyn;resolution:=optional");
        try {
            List<BundleRequirement> reqs = importer.adapt(BundleRevision.class).getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE);
            assertEquals("The requirement of DynamicImport-Package ign.dyn;resolution:=optional must have resolution:=dynamic",
                    PackageNamespace.RESOLUTION_DYNAMIC, reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_RESOLUTION_DIRECTIVE));
            assertTrue(resolve(exporter, importer));
            assertTrue("A dynamic import must not be wired when the bundle is resolved",
                    importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).isEmpty());
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testDynamicImportResolutionMandatory() throws Exception {
        Bundle importer = install("ign-dyn-mand", "DynamicImport-Package", "ign.nosuch;resolution:=mandatory");
        try {
            assertTrue("The bundle with DynamicImport-Package ign.nosuch;resolution:=mandatory must resolve, a dynamic import is not needed for resolving",
                    resolve(importer));
        } finally {
            importer.uninstall();
        }
    }

    private boolean resolve(Bundle... bundles) throws Exception {
        FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
        return fwiring.resolveBundles(new ArrayList<Bundle>(Arrays.asList(bundles)));
    }

    private Bundle install(final String name, final String header, final String value) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (header != null)
                    builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return installBundle(archive);
    }

    // Merged from services/17 (withdrawn, same root cause): the uses/effective directives of Bundle-SymbolicName are
    // copied into the osgi.wiring.bundle and osgi.wiring.host capabilities.


    private static final String BSN_DIRECTIVES = "bsn-directives;uses:=\"org.osgi.framework\";effective:=active";

    @Test
    public void testBundleCapabilityDirectives() throws Exception {
        Bundle bundle = installBundle(getBsnDirectivesArchive());
        try {
            Map<String, String> dirs = bundle.adapt(BundleRevision.class).getDeclaredCapabilities(BundleNamespace.BUNDLE_NAMESPACE).get(0).getDirectives();
            assertFalse("the osgi.wiring.bundle capability must not have a uses or effective directive, but has " + dirs,
                    dirs.containsKey("uses") || dirs.containsKey("effective"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testHostCapabilityDirectives() throws Exception {
        Bundle bundle = installBundle(getBsnDirectivesArchive());
        try {
            Map<String, String> dirs = bundle.adapt(BundleRevision.class).getDeclaredCapabilities(HostNamespace.HOST_NAMESPACE).get(0).getDirectives();
            assertFalse("the osgi.wiring.host capability must not have a uses or effective directive, but has " + dirs,
                    dirs.containsKey("uses") || dirs.containsKey("effective"));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive getBsnDirectivesArchive() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "bsn-directives");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(BSN_DIRECTIVES);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive;
    }
}
