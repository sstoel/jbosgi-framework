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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
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
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.framework.namespace.PackageNamespace;

/**
 * DynamicImport-Package is applied to packages the bundle gets through Require-Bundle: a class or resource that the
 * required bundle does not have is loaded from another exporter of the same package (a dynamic package wire to that
 * exporter is added next to the Require-Bundle wire), and getResources returns the required bundle's resources
 * twice.
 *
 * <h3>Severity</h3>
 * major - Require-Bundle combined with {@code DynamicImport-Package: *} is common (Eclipse-style bundles, bundles
 * generated with a catch-all dynamic import). The bundle ends up with a split class space for the required package:
 * {@code fuzz.dynreq.C} from the required bundle and {@code fuzz.dynreq.D} from an unrelated exporter (package
 * private access between them fails, wrong versions are mixed, a missing class is "found" instead of a
 * ClassNotFoundException). getResources duplicates break every "load all resources of this name" consumer
 * (ServiceLoader-style registries, configuration merging) for packages provided by Require-Bundle.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} (findMatchingPatterns,
 * loadClassLocal, loadResourceLocal).
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 1, case 10: bundle with {@code Require-Bundle} and {@code DynamicImport-Package: pa} returned
 * getResources [l10b1:pa, l10b0:pa] vs Equinox [l10b0:pa, l10b1:pa]), minimised with {@code fa.ProbeMulti} against
 * Felix 7.0.5 and Equinox 3.21.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.4 Overall Search Order (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * step 4 "If the class or resource is in a package that is imported from one or more other bundles using
 * Require-Bundle, the request is delegated to the class loaders of the other bundles ... If the class or resource
 * is not found, then the search continues with the next step." step 5 "Search the bundle's embedded class path"
 * step 6 "If the class or resource is in a package that is exported by the bundle or the package is imported by the
 * bundle (using Import-Package or Require-Bundle), then the search ends and the class or resource is not found."
 * step 7 "Otherwise, if the class or resource is in a package that is imported using DynamicImport-Package, then a
 * dynamic import of the package is now attempted."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:271-283}
 * ({@code findMatchingPatterns}: "Dynamic imports may not be used when the package is exported" - only the bundle's
 * own exports are excluded; packages of the required bundles are not), used by {@code loadClassLocal} (:129, which
 * then adds the dynamic wire, :155-170) and {@code loadResourceLocal} (:191). jboss-modules consults the fallback
 * loader after the Require-Bundle dependency, so for getResources its result is appended to the one of the
 * required bundle. Import-Package is handled correctly (the module's import path filter stops the search). Code from
 * the original FallbackLoader, unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The dynamic import fallback does not apply step 6 for packages imported with Require-Bundle (the packages
 * exported by the required bundles, including re-exported ones).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportOfRequiredPackageIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testClassOfRequiredPackageNotLoadedDynamically: fuzz.dynreq.D is not in the required bundle; the search must end
 *   (3.9.4 step 6) with ClassNotFoundException, but it was loaded from dynreq-other1
 * testNoDynamicWireForRequiredPackage: no osgi.wiring.package wire may be added for fuzz.dynreq, which the bundle
 *   gets through Require-Bundle, but the wiring has [package fuzz.dynreq -&gt; dynreq-other2]
 * testResourceOfRequiredPackageNotFoundDynamically: getResource("fuzz/dynreq/d.txt") must be null (search ends at
 *   step 6), but was bundle://dynreq.other3-10-0-0/fuzz/dynreq/d.txt
 * testGetResourcesOfRequiredPackageNoDuplicates: getResources("fuzz/dynreq/r.txt") expected:&lt;[required]&gt; but
 *   was:&lt;[required, required]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * ClassNotFoundException for fuzz.dynreq.D, getResource of d.txt null, no package wire for fuzz.dynreq, and
 * getResources returning the required bundle's r.txt once.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: ClassNotFoundException, null, no package wire, [required] - for all four.
 *
 * <h3>Suggested fix</h3>
 * In FallbackLoader.findMatchingPatterns also return no pattern when the path is a package of an osgi.wiring.bundle
 * wire provider (its exported packages, transitively through visibility:=reexport), i.e. when the package is
 * imported by the bundle "using Import-Package or Require-Bundle".
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
public class DynamicImportOfRequiredPackageIssueTestCase extends OSGiFrameworkTest {

    private static final String PKG = "fuzz.dynreq";
    private static final String PATH = "fuzz/dynreq";

    @Test
    public void testClassOfRequiredPackageNotLoadedDynamically() throws Exception {
        Bundle[] bundles = installBundles("1");
        try {
            Bundle requirer = bundles[2];
            assertEquals("fuzz.dynreq.C comes from the required bundle", bundles[1], FrameworkUtil.getBundle(requirer.loadClass(PKG + ".C")));
            try {
                Class<?> clazz = requirer.loadClass(PKG + ".D");
                fail("fuzz.dynreq.D is not in the required bundle; the search must end (3.9.4 step 6) with ClassNotFoundException, but it was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testNoDynamicWireForRequiredPackage() throws Exception {
        Bundle[] bundles = installBundles("2");
        try {
            Bundle requirer = bundles[2];
            try {
                requirer.loadClass(PKG + ".D");
            } catch (ClassNotFoundException ex) {
                // expected
            }
            List<String> packageWires = new ArrayList<String>();
            for (BundleWire wire : requirer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE))
                packageWires.add("package " + wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE) + " -> " + wire.getProvider().getSymbolicName());
            assertTrue("no osgi.wiring.package wire may be added for fuzz.dynreq, which the bundle gets through Require-Bundle, but the wiring has " + packageWires,
                    packageWires.isEmpty());
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testResourceOfRequiredPackageNotFoundDynamically() throws Exception {
        Bundle[] bundles = installBundles("3");
        try {
            URL url = bundles[2].getResource(PATH + "/d.txt");
            assertNull("getResource(\"" + PATH + "/d.txt\") must be null (search ends at step 6), but was " + url, url);
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testGetResourcesOfRequiredPackageNoDuplicates() throws Exception {
        Bundle[] bundles = installBundles("4");
        try {
            List<String> contents = new ArrayList<String>();
            Enumeration<URL> urls = bundles[2].getResources(PATH + "/r.txt");
            while (urls != null && urls.hasMoreElements())
                contents.add(read(urls.nextElement()));
            assertEquals("getResources(\"" + PATH + "/r.txt\")", Arrays.asList("required"), contents);
        } finally {
            uninstall(bundles);
        }
    }

    // [0] another exporter of fuzz.dynreq (class D, d.txt), [1] the required bundle (class C, r.txt), [2] the requirer
    private Bundle[] installBundles(String suffix) throws Exception {
        Bundle other = install("dynreq-other" + suffix, "Export-Package", PKG, PATH + "/D.class", classBytes(PATH + "/D"), PATH + "/d.txt", "other".getBytes("UTF-8"));
        Bundle required = install("dynreq-required" + suffix, "Export-Package", PKG, PATH + "/C.class", classBytes(PATH + "/C"), PATH + "/r.txt", "required".getBytes("UTF-8"));
        Bundle requirer = install("dynreq-requirer" + suffix, "Require-Bundle", "dynreq-required" + suffix, null, null, null, null);
        Bundle[] bundles = new Bundle[] { other, required, requirer };
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("all bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
        return bundles;
    }

    private Bundle install(String symbolicName, String header, String value, String entry1, byte[] content1, String entry2, byte[] content2) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(header, value);
        if (header.equals(Constants.REQUIRE_BUNDLE))
            attributes.putValue(Constants.DYNAMICIMPORT_PACKAGE, "*");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        if (entry1 != null) {
            jar.putNextEntry(new JarEntry(entry1));
            jar.write(content1);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(entry2));
            jar.write(content2);
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

    private static String read(URL url) throws IOException {
        InputStream in = url.openStream();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int len;
            while ((len = in.read(buffer)) > 0)
                baos.write(buffer, 0, len);
            return new String(baos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void uninstall(Bundle[] bundles) throws Exception {
        for (Bundle bundle : bundles)
            bundle.uninstall();
    }
}
