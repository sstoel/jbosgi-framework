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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * For resource lookups the dynamic import fallback computes the package of a resource as if its path were a class
 * name (everything before the last '.'): a resource in a package that the bundle itself exports is dynamically
 * imported from another exporter (getResources returns the other bundle's resources too, getResource returns another
 * bundle's resource that the own package does not have), and a resource without a '.' in its name (e.g.
 * {@code LICENSE}) is never found through DynamicImport-Package.
 *
 * <h3>Severity</h3>
 * minor - affects resource lookups only (classes are correct): getResources results contain foreign resources for
 * packages the bundle owns (configuration/service files merged from other bundles), a missing resource of the own
 * package is silently taken from another bundle, and dynamically imported packages lose their extension-less
 * resources.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader#findMatchingPatterns}, with
 * jbosgi-vfs api {@code org.jboss.osgi.vfs.VFSUtils#getPathFromClassName}.
 *
 * <h3>Found by</h3>
 * R8 verification, delivered test; committed for the R8 release check.
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 15, clean mode, case 1: {@code getResources("pa/r.txt")} of a bundle exporting {@code pa} with
 * {@code DynamicImport-Package: pa} returned [own, other] where Felix 7.0.5 and Equinox 3.21 returned [own]),
 * minimised with {@code fa.ProbeMulti}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.4 Overall Search Order (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html),
 * which applies to classes and resources alike: step 6 "If the class or resource is in a package that is exported by
 * the bundle or the package is imported by the bundle (using Import-Package or Require-Bundle), then the search ends
 * and the class or resource is not found." step 7 "Otherwise, if the class or resource is in a package that is
 * imported using DynamicImport-Package, then a dynamic import of the package is now attempted." The package of a
 * resource is its path up to the last '/'.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:276}
 * ({@code String pathName = VFSUtils.getPathFromClassName(resName);}) - {@code loadResourceLocal} (:191) passes a
 * resource path such as {@code pa/r.txt}; getPathFromClassName (jbosgi-vfs
 * {@code api/src/main/java/org/jboss/osgi/vfs/VFSUtils.java:117-120}) cuts at the last '.', giving {@code pa/r}
 * (not equal to the exported {@code pa}, so the "exported package" exclusion at :277-282 does not apply) or
 * {@code ""} for {@code pa/LICENSE} (no pattern but "*" matches). Pre-existing, unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * A resource path is interpreted with class-name rules when its package is derived.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=DynamicImportResourcePathIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetResourcesOfExportedPackage: getResources("fuzz/dynres1/r.txt") of a bundle that exports fuzz.dynres1
 *   expected:&lt;[own]&gt; but was:&lt;[own, other]&gt;
 * testMissingResourceOfExportedPackage: getResource("fuzz/dynres2/x.txt") must be null (fuzz.dynres2 is exported by
 *   the bundle itself, the search ends at step 6), but was bundle://dynres.other2-1-0-0/fuzz/dynres2/x.txt
 * testResourceWithoutDotThroughDynamicImport: fuzz/dynres3/LICENSE must be found through DynamicImport-Package
 *   fuzz.dynres3
 * </pre>
 *
 * <h3>Expected</h3>
 * [own]; null; the exporter's LICENSE.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: [own], null, LICENSE found.
 *
 * <h3>Suggested fix</h3>
 * In findMatchingPatterns derive the path with {@code resName.substring(0, resName.lastIndexOf('/'))} for resource
 * names (loadResourceLocal) and keep getPathFromClassName for class names (loadClassLocal).
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
public class DynamicImportResourcePathIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testGetResourcesOfExportedPackage() throws Exception {
        Bundle other = install("dynres-other1", "fuzz.dynres1", null, "fuzz/dynres1/r.txt", "other");
        Bundle own = install("dynres-own1", "fuzz.dynres1", "*", "fuzz/dynres1/r.txt", "own");
        try {
            resolve(other, own);
            List<String> contents = new ArrayList<String>();
            Enumeration<URL> urls = own.getResources("fuzz/dynres1/r.txt");
            while (urls != null && urls.hasMoreElements())
                contents.add(read(urls.nextElement()));
            assertEquals("getResources(\"fuzz/dynres1/r.txt\") of a bundle that exports fuzz.dynres1", Arrays.asList("own"), contents);
        } finally {
            own.uninstall();
            other.uninstall();
        }
    }

    @Test
    public void testMissingResourceOfExportedPackage() throws Exception {
        Bundle other = install("dynres-other2", "fuzz.dynres2", null, "fuzz/dynres2/x.txt", "other");
        Bundle own = install("dynres-own2", "fuzz.dynres2", "*", "fuzz/dynres2/r.txt", "own");
        try {
            resolve(other, own);
            URL url = own.getResource("fuzz/dynres2/x.txt");
            assertNull("getResource(\"fuzz/dynres2/x.txt\") must be null (fuzz.dynres2 is exported by the bundle itself, the search ends at step 6), but was " + url, url);
        } finally {
            own.uninstall();
            other.uninstall();
        }
    }

    @Test
    public void testResourceWithoutDotThroughDynamicImport() throws Exception {
        Bundle exporter = install("dynres-exporter3", "fuzz.dynres3", null, "fuzz/dynres3/LICENSE", "license");
        Bundle importer = install("dynres-importer3", null, "fuzz.dynres3", null, null);
        try {
            resolve(exporter, importer);
            assertNotNull("fuzz/dynres3/LICENSE must be found through DynamicImport-Package fuzz.dynres3", importer.getResource("fuzz/dynres3/LICENSE"));
        } finally {
            importer.uninstall();
            exporter.uninstall();
        }
    }

    private void resolve(Bundle... bundles) throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
    }

    private Bundle install(String symbolicName, String exportPackage, String dynamicImport, String entry, String content) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (exportPackage != null)
            attributes.putValue(Constants.EXPORT_PACKAGE, exportPackage);
        if (dynamicImport != null)
            attributes.putValue(Constants.DYNAMICIMPORT_PACKAGE, dynamicImport);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        if (entry != null) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(content.getBytes("UTF-8"));
            jar.closeEntry();
        }
        jar.close();
        return getSystemContext().installBundle(symbolicName, new ByteArrayInputStream(baos.toByteArray()));
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
}
