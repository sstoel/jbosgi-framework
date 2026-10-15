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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A dynamic import only establishes a wire when a class is loaded successfully: a resource lookup that is served
 * through DynamicImport-Package, or a class that the exporter does not have, leaves no wire. The importer is then
 * missing from the exporter's dependency closure (refresh/uninstall of the exporter does not refresh it) and the
 * package can later be wired to another exporter.
 *
 * <h3>Severity</h3>
 * minor - the Wiring API, getDependencyClosure and refreshBundles do not see these dependencies; class space can change
 * between loads of the same package.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} ({@code loadClassLocal},
 * {@code loadResourceLocal}).
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of FrameworkWiring.getDependencyClosure after dynamic imports.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.2 Dynamic Import Package
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.dynamicimportpackage):
 * "If a wiring attempt is successful (taking any uses constraints into account), the search is forwarded to the
 * exporter's class loader where class loading continues. The wiring must not subsequently be modified, even if the
 * class cannot be loaded. This implies that once a package is dynamically resolved, subsequent attempts to load
 * classes or resources from that package are treated as normal imports." 3.9.4 Overall Search Order applies to
 * "a class or resource". FrameworkWiring.getDependencyClosure javadoc: "The graph is expanded by adding any bundle
 * that is either wired to a package that is currently exported by a bundle in the graph or requires a bundle in the
 * graph."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:129-185}: the wire is
 * created only after {@code moduleClassLoader.loadClass(className)} succeeded (a ClassNotFoundException returns
 * before); {@code :191-223} ({@code loadResourceLocal}) never creates a wire. Pre-existing.
 *
 * <h3>Root cause</h3>
 * The wire is treated as a by-product of a successful class load instead of the result of the dynamic resolution.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportWireNotEstablishedIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testResourceEstablishesWire: A resource loaded through DynamicImport-Package must establish a package wire
 *   expected:&lt;1&gt; but was:&lt;0&gt;
 * testMissingClassEstablishesWire: The dynamic wire must be established even if the class cannot be loaded
 *   expected:&lt;1&gt; but was:&lt;0&gt;
 * testDependencyClosureContainsDynamicImporter: The dependency closure of the exporter must contain the bundle that
 *   dynamically imported a resource from it, but is [dynwire-exporter:0.0.0]
 * </pre>
 *
 * <h3>Expected</h3>
 * After the dynamic import resolved to an exporter, the importer's wiring has a package wire to that exporter whether
 * a class or a resource was requested and whether or not it was found.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: a resource lookup and a lookup of a missing class through DynamicImport-Package both create the wire;
 * getDependencyClosure of the exporter contains both importers.
 *
 * <h3>Suggested fix</h3>
 * Create the wire in {@code findRevisionDynamically} as soon as an exporter has been selected (for class and resource
 * lookups) and add the package to the imported paths so later lookups use it; this also follows naturally from using
 * {@code Resolver.resolveDynamic}.
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41.
 *
 * @since 13-Oct-2026
 */
public class DynamicImportWireNotEstablishedIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE_A = ObjectA.class.getPackage().getName();
    private static final String RESOURCE = PACKAGE_A.replace('.', '/') + "/resource.txt";

    @Test
    public void testResourceEstablishesWire() throws Exception {
        List<Bundle> bundles = installBundles();
        try {
            Bundle importer = bundles.get(1);
            assertNotNull("The resource is found through the dynamic import", importer.getResource(RESOURCE));
            assertEquals("A resource loaded through DynamicImport-Package must establish a package wire", 1, getPackageWires(importer).size());
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testMissingClassEstablishesWire() throws Exception {
        List<Bundle> bundles = installBundles();
        try {
            Bundle importer = bundles.get(1);
            try {
                importer.loadClass(PACKAGE_A + ".DoesNotExist");
                fail("ClassNotFoundException expected");
            } catch (ClassNotFoundException ex) {
                // expected
            }
            assertEquals("The dynamic wire must be established even if the class cannot be loaded", 1, getPackageWires(importer).size());
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testDependencyClosureContainsDynamicImporter() throws Exception {
        List<Bundle> bundles = installBundles();
        try {
            Bundle exporter = bundles.get(0);
            Bundle importer = bundles.get(1);
            assertNotNull(importer.getResource(RESOURCE));
            Collection<Bundle> closure = getFramework().adapt(FrameworkWiring.class).getDependencyClosure(Arrays.asList(exporter));
            assertTrue("The dependency closure of the exporter must contain the bundle that dynamically imported a resource from it, but is " + closure, closure.contains(importer));
        } finally {
            uninstall(bundles);
        }
    }

    private List<BundleWire> getPackageWires(Bundle importer) {
        return importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
    }

    private List<Bundle> installBundles() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        bundles.add(installBundle(createBundle("dynwire-exporter", PACKAGE_A, null)));
        bundles.add(installBundle(createBundle("dynwire-importer", null, PACKAGE_A)));
        assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
        return bundles;
    }

    private void uninstall(List<Bundle> bundles) throws Exception {
        for (Bundle bundle : bundles)
            bundle.uninstall();
    }

    private JavaArchive createBundle(final String name, final String exports, final String dynamicImports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (exports != null) {
            archive.addClasses(ObjectA.class);
            archive.add(new StringAsset("resource"), RESOURCE);
        }
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (exports != null)
                    builder.addExportPackages(exports);
                if (dynamicImports != null)
                    builder.addDynamicImportPackages(dynamicImports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
