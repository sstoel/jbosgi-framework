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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A wildcard DynamicImport-Package ({@code *} or {@code org.acme.*}) loads classes and resources of packages that no
 * bundle exports: the exporter is matched by the longest common character prefix between the requested path and one
 * of its exported packages, so {@code DynamicImport-Package: *} reads the private package {@code ...internal} of any
 * bundle that exports a package with the same prefix, and {@code org.acme.*} also matches {@code org.acme} itself.
 * The recorded wire names the other (exported) package. The same happens without a wildcard: {@code
 * DynamicImport-Package: org.acme} loads {@code org.acme.impl.C} (a private sub-package) from the exporter of
 * {@code org.acme}.
 *
 * <h3>Severity</h3>
 * major - breaks bundle encapsulation: every bundle with {@code DynamicImport-Package: *} (common in frameworks and
 * generated manifests) can load the private implementation classes and resources of other bundles (e.g.
 * {@code com.foo.internal.*} of a bundle exporting {@code com.foo.api}), and gets them instead of a
 * ClassNotFoundException; the bundle's wiring then reports a package wire for a package it never used.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} (findMatchingPatterns,
 * getCandidateCapability).
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 4, case 55: {@code DynamicImport-Package: pa.*} loaded class {@code pa.C} from a bundle exporting
 * {@code pa} and {@code pa.sub}; Felix 7.0.5 and Equinox 3.21: ClassNotFoundException), widened with
 * {@code fa.ProbeMulti}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.2 Dynamic Import Package
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "Packages may be named explicitly
 * or by using wild-carded expressions such as org.foo.* and *. The wildcard can stand for any suffix, including
 * multiple sub-packages. If a wildcard is used, then the package identified by the prefix must not be included.
 * That is, org.foo.* will include all sub-packages of org.foo but it must not include package org.foo itself."
 * 3.9.4 step 7/8: a dynamic import of "the package" of the requested class or resource is attempted and the request
 * is delegated to an exporter of that package - a bundle that does not export the package is not an exporter.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:286-297}
 * (findMatchingPatterns: {@code pathName.startsWith(patternPath)} with the ".*" stripped - "org/acme" matches
 * "org.acme.*", and "org/acme/impl" matches the plain pattern "org.acme") and {@code :467-490} (getCandidateCapability for a wildcard requirement: any exported package that
 * matches the wildcard and shares at least two leading characters with the resource path is taken as the
 * capability, instead of the capability of the requested package; for a non-wildcard pattern the capability of the
 * pattern's package is taken whatever package was requested). Pre-existing, unchanged by the R8 branch.
 * Related but distinct: fuzz/35 (DynamicImportExporterContentIssueTestCase, legitimate exporters not found because
 * candidates are probed by raw entries).
 *
 * <h3>Root cause</h3>
 * The package of the requested class/resource is never compared with the package of the chosen capability; the
 * wildcard prefix test is done on paths without excluding the prefix package itself.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportWildcardPackageMatchIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testStarDoesNotLoadPrivateClass: fuzz.dynwc1.internal.C is in a package no bundle exports; it must not be loaded
 *   through DynamicImport-Package: *, but it was loaded from dynwc-exporter1
 * testStarDoesNotFindPrivateResource: getResource("fuzz/dynwc2/internal/r.txt") must be null (package not exported),
 *   but was bundle://dynwc.exporter2-3-0-0/fuzz/dynwc2/internal/r.txt
 * testWildcardExcludesPrefixPackage: DynamicImport-Package: fuzz.dynwc3.* must not include fuzz.dynwc3 itself, but
 *   fuzz.dynwc3.C was loaded from dynwc-exporter3
 * testNoWireForOtherPackage: no package wire may be created by a lookup in a non-exported package, but the wiring has
 *   [fuzz.dynwc4.api -&gt; dynwc-exporter4]
 * testExplicitPackageDoesNotMatchSubPackage: DynamicImport-Package: fuzz.dynwc5 names only fuzz.dynwc5; the private
 *   fuzz.dynwc5.impl.C must not be loaded, but it was loaded from dynwc-exporter5
 * </pre>
 *
 * <h3>Expected</h3>
 * ClassNotFoundException / null for the non-exported package and for the prefix package of the wildcard; no package
 * wire.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: ClassNotFoundException, null, no wire in all cases.
 *
 * <h3>Suggested fix</h3>
 * Derive the package name of the requested class/resource, check it against the dynamic patterns with package
 * semantics ("org.foo.*" = names starting with "org.foo."; "*" = any), and look for an exported capability of exactly
 * that package (the dynamic requirement with its filter instantiated for the package, as Resolver.resolveDynamic
 * does).
 * Related: the dynamic-import cluster fuzz/33 (DynamicImportOfRequiredPackageIssueTestCase), fuzz/35
 * (DynamicImportExporterContentIssueTestCase), fuzz/36 (DynamicImportWildcardPackageMatchIssueTestCase) and fuzz/38
 * (DynamicImportResourcePathIssueTestCase) are all in FallbackLoader (findMatchingPatterns :271-297,
 * getCapabilityCandidate :440-490). They are distinct failures, not duplicates, but one rewrite of
 * findMatchingPatterns (package derived with resource/class rules, package-semantics pattern matching, Require-Bundle
 * packages excluded) fixes fuzz/33, fuzz/36 and fuzz/38 together; fuzz/35 needs the exporter selection in
 * getCapabilityCandidate as well.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class DynamicImportWildcardPackageMatchIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testStarDoesNotLoadPrivateClass() throws Exception {
        Bundle exporter = install("dynwc-exporter1", Constants.EXPORT_PACKAGE, "fuzz.dynwc1.api", "fuzz/dynwc1/api/C", "fuzz/dynwc1/internal/C");
        Bundle importer = install("dynwc-importer1", Constants.DYNAMICIMPORT_PACKAGE, "*");
        try {
            resolve(exporter, importer);
            try {
                Class<?> clazz = importer.loadClass("fuzz.dynwc1.internal.C");
                fail("fuzz.dynwc1.internal.C is in a package no bundle exports; it must not be loaded through DynamicImport-Package: *, but it was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testStarDoesNotFindPrivateResource() throws Exception {
        Bundle exporter = install("dynwc-exporter2", Constants.EXPORT_PACKAGE, "fuzz.dynwc2.api", "fuzz/dynwc2/api/C", "fuzz/dynwc2/internal/C");
        Bundle importer = install("dynwc-importer2", Constants.DYNAMICIMPORT_PACKAGE, "*");
        try {
            resolve(exporter, importer);
            URL url = importer.getResource("fuzz/dynwc2/internal/r.txt");
            assertNull("getResource(\"fuzz/dynwc2/internal/r.txt\") must be null (package not exported), but was " + url, url);
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testWildcardExcludesPrefixPackage() throws Exception {
        Bundle exporter = install("dynwc-exporter3", Constants.EXPORT_PACKAGE, "fuzz.dynwc3,fuzz.dynwc3.api", "fuzz/dynwc3/api/C", "fuzz/dynwc3/C");
        Bundle importer = install("dynwc-importer3", Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dynwc3.*");
        try {
            resolve(exporter, importer);
            try {
                Class<?> clazz = importer.loadClass("fuzz.dynwc3.C");
                fail("DynamicImport-Package: fuzz.dynwc3.* must not include fuzz.dynwc3 itself, but fuzz.dynwc3.C was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testNoWireForOtherPackage() throws Exception {
        Bundle exporter = install("dynwc-exporter4", Constants.EXPORT_PACKAGE, "fuzz.dynwc4.api", "fuzz/dynwc4/api/C", "fuzz/dynwc4/internal/C");
        Bundle importer = install("dynwc-importer4", Constants.DYNAMICIMPORT_PACKAGE, "*");
        try {
            resolve(exporter, importer);
            try {
                importer.loadClass("fuzz.dynwc4.internal.C");
            } catch (ClassNotFoundException ex) {
                // expected
            }
            List<String> wires = new ArrayList<String>();
            for (BundleWire wire : importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE))
                wires.add(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE) + " -> " + wire.getProvider().getSymbolicName());
            assertTrue("no package wire may be created by a lookup in a non-exported package, but the wiring has " + wires, wires.isEmpty());
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    @Test
    public void testExplicitPackageDoesNotMatchSubPackage() throws Exception {
        Bundle exporter = install("dynwc-exporter5", Constants.EXPORT_PACKAGE, "fuzz.dynwc5", "fuzz/dynwc5/C", "fuzz/dynwc5/impl/C");
        Bundle importer = install("dynwc-importer5", Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dynwc5");
        try {
            resolve(exporter, importer);
            try {
                Class<?> clazz = importer.loadClass("fuzz.dynwc5.impl.C");
                fail("DynamicImport-Package: fuzz.dynwc5 names only fuzz.dynwc5; the private fuzz.dynwc5.impl.C must not be loaded, but it was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private void resolve(Bundle... bundles) throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
    }

    // Installs a bundle with one header and the given classes (internal names); each class package also gets r.txt
    private Bundle install(String symbolicName, String header, String value, String... classes) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(header, value);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        for (String internalName : classes) {
            jar.putNextEntry(new JarEntry(internalName + ".class"));
            jar.write(classBytes(internalName));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(internalName.substring(0, internalName.lastIndexOf('/')) + "/r.txt"));
            jar.write(symbolicName.getBytes("UTF-8"));
            jar.closeEntry();
        }
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }

    // A minimal class file: public class <internalName> extends java.lang.Object (Java 8 format, no members)
    private static byte[] classBytes(String internalName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(52);
        out.writeShort(5);
        out.writeByte(7);
        out.writeShort(2);
        out.writeByte(1);
        out.writeUTF(internalName);
        out.writeByte(7);
        out.writeShort(4);
        out.writeByte(1);
        out.writeUTF("java/lang/Object");
        out.writeShort(0x0021);
        out.writeShort(1);
        out.writeShort(3);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.close();
        return baos.toByteArray();
    }
}
