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
import java.net.URL;
import java.util.Arrays;
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
 * Require-Bundle (without visibility:=reexport) gives the requiring bundle access to every package of the required
 * bundle, not only to its exported packages: private classes and resources are loadable, and a private package of
 * the required bundle shadows the requiring bundle's own package of the same name.
 *
 * <h3>Severity</h3>
 * major - breaks bundle encapsulation for every Require-Bundle user: implementation packages that a bundle keeps
 * private (e.g. {@code *.impl}, {@code *.internal}, embedded libraries) become visible to all bundles that require
 * it, and - because required bundles are searched before the bundle's own class path - a requiring bundle that has
 * a package with the same name as a private package of the required bundle loads the wrong classes (its own
 * classes are shadowed; LinkageError/ClassCastException or silently wrong behaviour).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ModuleManagerImpl} (module dependency of a
 * Require-Bundle wire).
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 3, case 1 and seed 3 case 52: {@code loadClass} through Require-Bundle returned a class of a non-exported
 * package where Felix 7.0.5 and Equinox 3.21 throw ClassNotFoundException), minimised with {@code fa.ProbeMulti}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.13.1 Require-Bundle (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "The framework must take all exported packages from a required bundle, including any packages exported by
 * attached fragments, and wire these packages to the requiring bundle." 3.2.1.29: "The Require-Bundle header
 * specifies that all exported packages from another bundle must be imported, effectively requiring the public
 * interface of another bundle." 3.9.4 step 4: "If the class or resource is in a package that is imported from one
 * or more other bundles using Require-Bundle, the request is delegated to the class loaders of the other bundles";
 * a package that the required bundle does not export is not imported, so step 5 (the bundle's own class path)
 * applies.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:419}
 * ({@code holder.setImportFilter(PathFilters.not(importedPathsFilter));}) - the Require-Bundle module dependency
 * imports every path except the Import-Package paths, and the required module exports all its local paths
 * ({@code :294} local dependency {@code exportFilter = acceptAll}); only the visibility:=reexport branch
 * ({@code :422-435}) restricts the dependency to the required bundle's package capabilities. Since 6242cbdd /
 * a114c253 (2012), present on WF41.
 *
 * <h3>Root cause</h3>
 * The import filter of a non-reexport Require-Bundle dependency is not limited to the exported packages of the
 * required bundle.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RequireBundlePrivatePackageVisibleIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testPrivateClassNotVisible: fuzz.rbpriv.C is in a package the required bundle does not export; loadClass must
 *   throw ClassNotFoundException, but it was loaded from rbpriv-required1
 * testPrivateResourceNotVisible: getResource("fuzz/rbpriv/r.txt") must be null (package not exported by the required
 *   bundle), but was bundle://rbpriv.required2-1-0-0/fuzz/rbpriv/r.txt
 * testNonExportedSubPackageNotVisible: fuzz.rbpub.impl.C is in a package the required bundle does not export (it
 *   exports fuzz.rbpub only); loadClass must throw ClassNotFoundException, but it was loaded from rbpriv-required3
 * testOwnPackageNotShadowed: the requiring bundle's own fuzz.rbpriv.C must be loaded (the required bundle does not
 *   export fuzz.rbpriv) expected:&lt;rbpriv-require[r]4&gt; but was:&lt;rbpriv-require[d]4&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * ClassNotFoundException / null for packages the required bundle does not export, and the requiring bundle's own
 * class for its own package.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: ClassNotFoundException, null, and the requiring bundle's own class. With
 * visibility:=reexport jbosgi already behaves correctly (private packages invisible).
 *
 * <h3>Suggested fix</h3>
 * Build the same exported-paths filter for every Require-Bundle wire as the reexport branch does (paths of the
 * required bundle's osgi.wiring.package capabilities, minus the requirer's Import-Package paths) and use it as the
 * import filter; keep the export filter for the reexport case only.
 * Related: fuzz/37 (RequireBundleIgnoredWithImportIssueTestCase). Both failures are in the same bundle-wire loop of
 * ModuleManagerImpl (:411-435): here the non-reexport Require-Bundle dependency imports every path (too much visible,
 * :419); in fuzz/37 the Require-Bundle wire is skipped when the provider is also an Import-Package exporter (too little
 * visible, :413-414). Distinct failures, one fix site: restrict the Require-Bundle dependency to the provider's exported
 * packages AND merge it with the Import-Package paths.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class RequireBundlePrivatePackageVisibleIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testPrivateClassNotVisible() throws Exception {
        Bundle required = install("rbpriv-required1", null, null, "fuzz/rbpriv/C");
        Bundle requirer = install("rbpriv-requirer1", Constants.REQUIRE_BUNDLE, "rbpriv-required1", null);
        try {
            resolve(required, requirer);
            try {
                Class<?> clazz = requirer.loadClass("fuzz.rbpriv.C");
                fail("fuzz.rbpriv.C is in a package the required bundle does not export; loadClass must throw ClassNotFoundException, but it was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    @Test
    public void testPrivateResourceNotVisible() throws Exception {
        Bundle required = install("rbpriv-required2", null, null, "fuzz/rbpriv/C");
        Bundle requirer = install("rbpriv-requirer2", Constants.REQUIRE_BUNDLE, "rbpriv-required2", null);
        try {
            resolve(required, requirer);
            URL url = requirer.getResource("fuzz/rbpriv/r.txt");
            assertNull("getResource(\"fuzz/rbpriv/r.txt\") must be null (package not exported by the required bundle), but was " + url, url);
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    @Test
    public void testNonExportedSubPackageNotVisible() throws Exception {
        Bundle required = install("rbpriv-required3", Constants.EXPORT_PACKAGE, "fuzz.rbpub", "fuzz/rbpub/impl/C", "fuzz/rbpub/C");
        Bundle requirer = install("rbpriv-requirer3", Constants.REQUIRE_BUNDLE, "rbpriv-required3", null);
        try {
            resolve(required, requirer);
            assertEquals("the exported fuzz.rbpub.C is visible", required, FrameworkUtil.getBundle(requirer.loadClass("fuzz.rbpub.C")));
            try {
                Class<?> clazz = requirer.loadClass("fuzz.rbpub.impl.C");
                fail("fuzz.rbpub.impl.C is in a package the required bundle does not export (it exports fuzz.rbpub only); loadClass must throw ClassNotFoundException, but it was loaded from "
                        + FrameworkUtil.getBundle(clazz).getSymbolicName());
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    @Test
    public void testOwnPackageNotShadowed() throws Exception {
        Bundle required = install("rbpriv-required4", null, null, "fuzz/rbpriv/C");
        Bundle requirer = install("rbpriv-requirer4", Constants.REQUIRE_BUNDLE, "rbpriv-required4", "fuzz/rbpriv/C");
        try {
            resolve(required, requirer);
            Bundle loader = FrameworkUtil.getBundle(requirer.loadClass("fuzz.rbpriv.C"));
            assertEquals("the requiring bundle's own fuzz.rbpriv.C must be loaded (the required bundle does not export fuzz.rbpriv)",
                    requirer.getSymbolicName(), loader.getSymbolicName());
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    private void resolve(Bundle... bundles) throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
    }

    // Installs a bundle with an optional header and up to two classes (internal names); each class package also gets r.txt
    private Bundle install(String symbolicName, String header, String value, String class1, String... more) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        if (header != null)
            attributes.putValue(header, value);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        String[] classes = new String[1 + more.length];
        classes[0] = class1;
        System.arraycopy(more, 0, classes, 1, more.length);
        for (String internalName : classes) {
            if (internalName == null)
                continue;
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
