package org.jboss.test.osgi.framework.classloader;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Test that classes in java.* packages are only loaded from the parent class loader.
 *
 * @since 11-Oct-2026
 */
public class JavaPackageDelegationTestCase extends OSGiFrameworkTest {

    @Test
    public void testJavaPackageNotLoadedFromBundle() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "java-package");
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "java/lang/SomeClass.class");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            assertSame(Math.class, bundle.loadClass("java.lang.Math"));

            // The class file in the bundle is not considered
            try {
                bundle.loadClass("java.lang.SomeClass");
                fail("ClassNotFoundException expected");
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testImportJavaPackage() throws Exception {
        Bundle bundle = installBundle(getArchive("java-package-import", Constants.IMPORT_PACKAGE, "java.util, java.lang"));
        try {
            bundle.start();
            assertTrue(bundle.getHeaders().get(Constants.IMPORT_PACKAGE).contains("java.util"));

            // The imports are wired to the system bundle
            BundleRevision sysrev = getSystemContext().getBundle(0).adapt(BundleRevision.class);
            List<BundleWire> wires = bundle.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
            assertEquals(2, wires.size());
            for (BundleWire wire : wires) {
                assertSame(sysrev, wire.getProvider());
                String packageName = (String) wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
                assertTrue(packageName, Arrays.asList("java.util", "java.lang").contains(packageName));
            }

            // The classes are loaded from the parent class loader
            assertSame(List.class, bundle.loadClass("java.util.List"));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testImportMissingJavaPackage() throws Exception {
        Bundle bundle = installBundle(getArchive("java-package-missing", Constants.IMPORT_PACKAGE, "java.does.not.exist"));
        try {
            bundle.start();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertEquals(BundleException.RESOLVE_ERROR, ex.getType());
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testExportJavaPackage() throws Exception {
        try {
            installBundle(getArchive("java-package-export", Constants.EXPORT_PACKAGE, "java.lang")).uninstall();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertEquals(BundleException.MANIFEST_ERROR, ex.getType());
        }
    }

    @Test
    public void testSystemBundleExportsJavaPackages() throws Exception {
        // The launching property lists the packages of the running Java platform
        String systemPackages = getSystemContext().getProperty(Constants.FRAMEWORK_SYSTEMPACKAGES);
        assertNotNull(systemPackages);
        List<String> packages = Arrays.asList(systemPackages.split(","));
        assertTrue(packages.contains("java.lang"));
        assertTrue(packages.contains("java.util"));
        for (String packageName : packages) {
            if (packageName.startsWith("java.")) {
                boolean available = false;
                for (Module module : ModuleLayer.boot().modules())
                    available |= module.getPackages().contains(packageName);
                assertTrue("Package available: " + packageName, available);
            }
        }

        boolean found = false;
        BundleRevision sysrev = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        for (BundleCapability cap : sysrev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE))
            found |= "java.util.concurrent".equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
        assertTrue("java.util.concurrent exported", found);
    }

    private JavaArchive getArchive(String name, final String header, final String value) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return archive;
    }
}
