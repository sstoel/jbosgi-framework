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
 * When a bundle imports a package from a bundle that it also requires (Import-Package plus Require-Bundle of the
 * same bundle), the Require-Bundle wire is ignored for class loading: only the imported package is visible, all
 * other packages exported by the required bundle give ClassNotFoundException / null, although the bundle resolves
 * with both wires.
 *
 * <h3>Severity</h3>
 * major - the combination is common (a tool-generated Import-Package for some packages plus a hand-written
 * Require-Bundle, or Eclipse-style manifests); the bundle resolves, its wiring shows the osgi.wiring.bundle wire, and
 * it then fails at run time with ClassNotFoundException for any other package of the required bundle.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ModuleManagerImpl} (module dependencies of
 * the bundle wires).
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 5): seeded class/resource loading differential {@code fa.LoadDiff}
 * (seed 5, case 15: {@code getResources} of a package of the required bundle returned [] where Felix 7.0.5 and
 * Equinox 3.21 returned the required bundle's resource), minimised with {@code fa.ProbeMulti}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.13.1 Require-Bundle (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "The framework must take all exported packages from a required bundle, including any packages exported by
 * attached fragments, and wire these packages to the requiring bundle." and "Requiring bundles must get wired to all
 * exported packages of all their required bundles". 3.9.4 step 4: "If the class or resource is in a package that
 * is imported from one or more other bundles using Require-Bundle, the request is delegated to the class loaders of
 * the other bundles". Nothing makes an Import-Package wire to the same bundle cancel the Require-Bundle wire (the
 * imported package itself is served by step 3).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:411-414}
 * ({@code for (BundleWire wire : bundleWires) { ... if (packageExporters.contains(exporter)) continue; }}): the
 * Require-Bundle wire is skipped when the provider is already a package exporter for this bundle, so the module
 * dependency for that provider keeps the Import-Package filter (only the imported paths). Introduced by fe6c331d
 * (2011, "[TCK] Ignore Require-Bundle wire target is already wired for Import-Package"), present on WF41.
 *
 * <h3>Root cause</h3>
 * One module dependency (holder) per provider bundle; when the provider is both an Import-Package exporter and a
 * required bundle, the Require-Bundle part is dropped instead of being merged into the dependency's filter.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RequireBundleIgnoredWithImportIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testOtherExportedClassVisible: fuzz.rbimp1.c.C is exported by the required bundle rbimp-required1 and must be
 *   loadable through Require-Bundle, but: java.lang.ClassNotFoundException: fuzz.rbimp1.c.C from
 *   [Module "jbosgi.rbimp-requirer1:0.0.0.bid2rev0" from FrameworkModuleLoaderImpl]
 * testOtherExportedResourceVisible: fuzz/rbimp2/c/r.txt is in a package exported by the required bundle and must be
 *   found through Require-Bundle
 * </pre>
 *
 * <h3>Expected</h3>
 * fuzz.rbimpN.c.C and its r.txt are loaded from the required bundle (the imported package fuzz.rbimpN.a keeps
 * working through its Import-Package wire).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 load both classes and the resource from the required bundle.
 *
 * <h3>Suggested fix</h3>
 * Do not skip the Require-Bundle wire: give the provider's module dependency an import filter that accepts the
 * imported paths plus all exported paths of the required bundle (or create a separate dependency for the
 * Require-Bundle part); check the TCK case that motivated fe6c331d still passes.
 * Related: fuzz/34 (RequireBundlePrivatePackageVisibleIssueTestCase). Both failures are in the same bundle-wire loop
 * of ModuleManagerImpl (:411-435): here the Require-Bundle wire is skipped when the provider is also an Import-Package
 * exporter (too little visible, :413-414); in fuzz/34 the non-reexport Require-Bundle dependency imports every path
 * (too much visible, :419). Distinct failures, one fix site: restrict the Require-Bundle dependency to the provider's
 * exported packages AND merge it with the Import-Package paths.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class RequireBundleIgnoredWithImportIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testOtherExportedClassVisible() throws Exception {
        Bundle required = install("rbimp-required1", Constants.EXPORT_PACKAGE, "fuzz.rbimp1.a,fuzz.rbimp1.c", "fuzz/rbimp1/a/C", "fuzz/rbimp1/c/C");
        Bundle requirer = install("rbimp-requirer1", Constants.IMPORT_PACKAGE, "fuzz.rbimp1.a", Constants.REQUIRE_BUNDLE, "rbimp-required1");
        try {
            resolve(required, requirer);
            assertEquals("the imported package works", required, FrameworkUtil.getBundle(requirer.loadClass("fuzz.rbimp1.a.C")));
            try {
                Class<?> clazz = requirer.loadClass("fuzz.rbimp1.c.C");
                assertEquals("fuzz.rbimp1.c.C must come from the required bundle", required, FrameworkUtil.getBundle(clazz));
            } catch (ClassNotFoundException ex) {
                fail("fuzz.rbimp1.c.C is exported by the required bundle rbimp-required1 and must be loadable through Require-Bundle, but: " + ex);
            }
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    @Test
    public void testOtherExportedResourceVisible() throws Exception {
        Bundle required = install("rbimp-required2", Constants.EXPORT_PACKAGE, "fuzz.rbimp2.a,fuzz.rbimp2.c", "fuzz/rbimp2/a/C", "fuzz/rbimp2/c/C");
        Bundle requirer = install("rbimp-requirer2", Constants.IMPORT_PACKAGE, "fuzz.rbimp2.a", Constants.REQUIRE_BUNDLE, "rbimp-required2");
        try {
            resolve(required, requirer);
            assertNotNull("fuzz/rbimp2/c/r.txt is in a package exported by the required bundle and must be found through Require-Bundle",
                    requirer.getResource("fuzz/rbimp2/c/r.txt"));
        } finally {
            requirer.uninstall();
            required.uninstall();
        }
    }

    private void resolve(Bundle... bundles) throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue("bundles resolve", frameworkWiring.resolveBundles(Arrays.asList(bundles)));
    }

    // headers: name/value pairs, then class internal names; each class package also gets r.txt
    private Bundle install(String symbolicName, String... args) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int i = 0;
        while (i + 1 < args.length && !args[i].contains("/")) {
            attributes.putValue(args[i], args[i + 1]);
            i += 2;
        }
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        for (; i < args.length; i++) {
            String internalName = args[i];
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
