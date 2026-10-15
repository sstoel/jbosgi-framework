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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * DynamicImport-Package only finds an exporter whose own JAR root contains the requested class or resource as an
 * entry: a package exported through an attached fragment, a class located in a fragment of the exporter, a class in
 * a JAR on the exporter's Bundle-ClassPath and a resource in a Bundle-ClassPath directory are not found
 * (ClassNotFoundException / null), although the same package is wired and loadable with Import-Package.
 *
 * <h3>Severity</h3>
 * major - DynamicImport-Package (often {@code *}) is how frameworks and libraries load classes they do not know at
 * build time (serialization, ORM, scripting, plugin factories); exporters that embed their library JARs with
 * Bundle-ClassPath or contribute packages/classes with fragments (common for wrapped libraries and for patches /
 * localization fragments) are invisible to every dynamic importer.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} (findInResolvedRevisions,
 * getCapabilityCandidate, getPackageCapabilities, loadResourceLocal).
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 3, case 24: dynamic import of a package exported by a fragment gave ClassNotFoundException, Felix 7.0.5 and
 * Equinox 3.21 loaded it from the host), minimised and widened with {@code fa.ProbeMulti}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.4 Overall Search Order (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html),
 * step 7: "if the class or resource is in a package that is imported using DynamicImport-Package, then a dynamic
 * import of the package is now attempted. An exporter must conform to any implied package constraints. If an
 * appropriate exporter is found, a wire is established ..." step 8: "If the dynamic import of the package is
 * established, the request is delegated to the exporting bundle's class loader." - the exporter is selected by its
 * package export (osgi.wiring.package capability), and the request is served by its class loader (whole bundle class
 * path, attached fragments). 3.14.1 Fragments: the host's exports include the packages exported by attached
 * fragments; the fragment's content is part of the host's class path. 3.9.6 Bundle-ClassPath.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java}:
 * {@code :440-465} getCapabilityCandidate accepts a resolved bundle only if {@code brev.getEntry(resName)} (its own
 * JAR) has the class/resource, or {@code getEntry(path + "/" + resName)} for Bundle-ClassPath entries that are not
 * JARs ("cls/" gives "cls//..."); attached fragments and embedded JARs are never looked at. {@code :503-509}
 * getPackageCapabilities uses {@code brev.getCapabilities(...)} of the revision, which does not contain the
 * fragment's exports (those are hosted capabilities of the host wiring). {@code :207} loadResourceLocal returns
 * {@code brev.getEntry(resName)} instead of using the exporter's class loader. Pre-existing, unchanged by the R8
 * branch.
 *
 * <h3>Root cause</h3>
 * The dynamic import picks the exporter by probing the exporter's raw bundle entries and its revision's declared
 * capabilities instead of its wiring's package capabilities, and serves resources from the raw entries instead of
 * the exporter's class loader.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportExporterContentIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testPackageExportedByFragment: fuzz.dyncnt.a.C (package exported by the fragment of dyncnt-host1) must be loaded
 *   through DynamicImport-Package, but: java.lang.ClassNotFoundException: fuzz.dyncnt.a.C from
 *   [Module "jbosgi.dyncnt-importer1:0.0.0.bid5rev0" from FrameworkModuleLoaderImpl]
 * testClassInFragmentOfExporter: fuzz.dyncnt.b.C (in the fragment of the exporter dyncnt-host2) must be loaded
 *   through DynamicImport-Package, but: java.lang.ClassNotFoundException: fuzz.dyncnt.b.C from
 *   [Module "jbosgi.dyncnt-importer2:0.0.0.bid8rev0" from FrameworkModuleLoaderImpl]
 * testClassInEmbeddedJarOfExporter: fuzz.dyncnt.c.C (in lib/x.jar on the Bundle-ClassPath of the exporter
 *   dyncnt-host3) must be loaded through DynamicImport-Package, but: java.lang.ClassNotFoundException:
 *   fuzz.dyncnt.c.C from [Module "jbosgi.dyncnt-importer3:0.0.0.bid10rev0" from FrameworkModuleLoaderImpl]
 * testResourceInClassPathDirectoryOfExporter: fuzz/dyncnt/d/r.txt (in the Bundle-ClassPath directory cls of the
 *   exporter dyncnt-host4) must be found through DynamicImport-Package
 * </pre>
 *
 * <h3>Expected</h3>
 * Each class is loaded by the host/exporter class loader and the resource is found (as with Import-Package of the
 * same package, which works in all four set-ups).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 load the class / find the resource in all four set-ups (Equinox also adds the dynamic
 * package wire to the exporter).
 *
 * <h3>Suggested fix</h3>
 * Select the exporter from the osgi.wiring.package capabilities of the resolved wirings (BundleWiring.getCapabilities,
 * which include fragment-hosted exports), or better resolve the dynamic requirement with the resolver
 * (Resolver.resolveDynamic), without probing entries; then delegate class and resource requests to the exporter's
 * class loader.
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
public class DynamicImportExporterContentIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testPackageExportedByFragment() throws Exception {
        Bundle host = install("dyncnt-host1", null, "fuzz/dyncnt/a/C.class", classBytes("fuzz/dyncnt/a/C"));
        Bundle fragment = install("dyncnt-fragment1", new String[] { Constants.FRAGMENT_HOST, "dyncnt-host1", Constants.EXPORT_PACKAGE, "fuzz.dyncnt.a" });
        Bundle importer = install("dyncnt-importer1", new String[] { Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dyncnt.a" });
        try {
            resolve(host, fragment, importer);
            assertLoaded(importer, "fuzz.dyncnt.a.C", host, "(package exported by the fragment of dyncnt-host1)");
        } finally {
            uninstall(importer, fragment, host);
        }
    }

    @Test
    public void testClassInFragmentOfExporter() throws Exception {
        Bundle host = install("dyncnt-host2", new String[] { Constants.EXPORT_PACKAGE, "fuzz.dyncnt.b" });
        Bundle fragment = install("dyncnt-fragment2", new String[] { Constants.FRAGMENT_HOST, "dyncnt-host2" }, "fuzz/dyncnt/b/C.class", classBytes("fuzz/dyncnt/b/C"));
        Bundle importer = install("dyncnt-importer2", new String[] { Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dyncnt.b" });
        try {
            resolve(host, fragment, importer);
            assertLoaded(importer, "fuzz.dyncnt.b.C", host, "(in the fragment of the exporter dyncnt-host2)");
        } finally {
            uninstall(importer, fragment, host);
        }
    }

    @Test
    public void testClassInEmbeddedJarOfExporter() throws Exception {
        byte[] embedded = jar(null, "fuzz/dyncnt/c/C.class", classBytes("fuzz/dyncnt/c/C"));
        Bundle host = install("dyncnt-host3", new String[] { Constants.EXPORT_PACKAGE, "fuzz.dyncnt.c", Constants.BUNDLE_CLASSPATH, "lib/x.jar" }, "lib/x.jar", embedded);
        Bundle importer = install("dyncnt-importer3", new String[] { Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dyncnt.c" });
        try {
            resolve(host, importer);
            assertLoaded(importer, "fuzz.dyncnt.c.C", host, "(in lib/x.jar on the Bundle-ClassPath of the exporter dyncnt-host3)");
        } finally {
            uninstall(importer, host);
        }
    }

    @Test
    public void testResourceInClassPathDirectoryOfExporter() throws Exception {
        Bundle host = install("dyncnt-host4", new String[] { Constants.EXPORT_PACKAGE, "fuzz.dyncnt.d", Constants.BUNDLE_CLASSPATH, "cls" }, "cls/fuzz/dyncnt/d/r.txt", "host".getBytes("UTF-8"));
        Bundle importer = install("dyncnt-importer4", new String[] { Constants.DYNAMICIMPORT_PACKAGE, "fuzz.dyncnt.d" });
        try {
            resolve(host, importer);
            assertNotNull("the exporter itself finds the resource", host.getResource("fuzz/dyncnt/d/r.txt"));
            assertNotNull("fuzz/dyncnt/d/r.txt (in the Bundle-ClassPath directory cls of the exporter dyncnt-host4) must be found through DynamicImport-Package",
                    importer.getResource("fuzz/dyncnt/d/r.txt"));
        } finally {
            uninstall(importer, host);
        }
    }

    private static void assertLoaded(Bundle importer, String className, Bundle expected, String where) {
        try {
            Class<?> clazz = importer.loadClass(className);
            assertEquals(className + " must be defined by " + expected.getSymbolicName(), expected, FrameworkUtil.getBundle(clazz));
        } catch (ClassNotFoundException ex) {
            fail(className + " " + where + " must be loaded through DynamicImport-Package, but: " + ex);
        }
    }

    private void resolve(Bundle... bundles) throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
    }

    private static void uninstall(Bundle... bundles) throws Exception {
        for (Bundle bundle : bundles)
            bundle.uninstall();
    }

    private Bundle install(String symbolicName, String[] headers, Object... entries) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (headers != null)
            for (int i = 0; i < headers.length; i += 2)
                attributes.putValue(headers[i], headers[i + 1]);
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(jar(manifest, entries)));
    }

    private static byte[] jar(Manifest manifest, Object... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = manifest != null ? new JarOutputStream(baos, manifest) : new JarOutputStream(baos);
        List<Object> list = new ArrayList<Object>(Arrays.asList(entries));
        for (int i = 0; i + 1 < list.size(); i += 2) {
            jar.putNextEntry(new JarEntry((String) list.get(i)));
            jar.write((byte[]) list.get(i + 1));
            jar.closeEntry();
        }
        jar.close();
        return baos.toByteArray();
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
