package org.jboss.test.osgi.framework.bundle;
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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * Test the manifest errors that must fail the install of a bundle, and the Bundle methods
 * getDataFile, compareTo and getSignerCertificates.
 *
 * @since 12-Oct-2026
 */
public class ManifestValidationTestCase extends OSGiFrameworkTest {

    @Test
    public void testValidManifest() throws Exception {
        Bundle bundle = install("valid",
                Constants.BUNDLE_VERSION, "1.2.3.qualifier_1-a",
                Constants.EXPORT_PACKAGE, "org.acme.a;org.acme.b;version=\"1.0\";attr=x;mandatory:=\"attr,version\";uses:=\"org.acme.c,org.acme.d\",org.acme.c",
                Constants.IMPORT_PACKAGE, "org.acme.x;version=\"[1.0,2)\";resolution:=optional,org.acme.y;resolution:=mandatory;bundle-version=\"1.0\"",
                Constants.DYNAMICIMPORT_PACKAGE, "org.acme.dyn.*;version=\"1.0\",*",
                Constants.REQUIRE_BUNDLE, "acme.one;bundle-version=\"[1,2)\";visibility:=reexport;resolution:=optional,acme.two;visibility:=private",
                Constants.PROVIDE_CAPABILITY, "acme.cap;acme.cap=one;names:List<String>=\"a,b\";version:Version=\"1.0\";effective:=active,acme.cap;acme.cap=two",
                Constants.REQUIRE_CAPABILITY, "acme.cap;filter:=\"(&(acme.cap=one)(version>=1.0))\";resolution:=optional;cardinality:=multiple",
                Constants.BUNDLE_ACTIVATIONPOLICY, "lazy;include:=\"org.acme.a,org.acme.b\"",
                Constants.BUNDLE_NATIVECODE, "lib/a.so;osname=Linux;osname=MacOSX;processor=x86;processor=x86-64,*");
        bundle.uninstall();

        install("valid-singleton;singleton:=true;fragment-attachment:=resolve-time").uninstall();
        install("valid_name-1.with.dots;singleton:=false;fragment-attachment:=never").uninstall();
        install("valid-fragment", Constants.FRAGMENT_HOST, "acme.host;bundle-version=\"[1.0,2.0)\"").uninstall();

        // Single quotes are accepted like double quotes
        install("valid-quotes", Constants.IMPORT_PACKAGE, "org.acme.x; version='[1.0,2.0)';resolution:='optional',org.acme.y;version=\"[1.0,2.0)\"").uninstall();
    }

    @Test
    public void testDuplicateAttribute() throws Exception {
        assertManifestError("dup-att", Constants.IMPORT_PACKAGE, "org.acme.x;version=\"1.0\";version=\"2.0\"");
        assertManifestError("dup-att", Constants.EXPORT_PACKAGE, "org.acme.x;attr=a;attr=b");
        assertManifestError("dup-att", Constants.REQUIRE_BUNDLE, "acme.one;bundle-version=\"1.0\";bundle-version=\"1.0\"");
        assertManifestError("dup-att", Constants.PROVIDE_CAPABILITY, "acme.cap;name=a;name:String=b");
        assertManifestError("dup-att", Constants.FRAGMENT_HOST, "acme.host;bundle-version=\"1.0\";bundle-version=\"2.0\"");
    }

    @Test
    public void testDuplicateDirective() throws Exception {
        assertManifestError("dup-dir", Constants.IMPORT_PACKAGE, "org.acme.x;resolution:=optional;resolution:=optional");
        assertManifestError("dup-dir", Constants.EXPORT_PACKAGE, "org.acme.x;uses:=\"org.acme.a\";uses:=\"org.acme.b\"");
        assertManifestError("dup-dir", Constants.REQUIRE_CAPABILITY, "acme.cap;filter:=\"(a=b)\";filter:=\"(a=c)\"");
        assertManifestError("dup-dir;singleton:=true;singleton:=true");
        assertManifestError("dup-dir", Constants.BUNDLE_ACTIVATIONPOLICY, "lazy;include:=\"a\";include:=\"b\"");
    }

    @Test
    public void testUndefinedMandatoryAttribute() throws Exception {
        assertManifestError("mandatory", Constants.EXPORT_PACKAGE, "org.acme.x;mandatory:=attr");
        assertManifestError("mandatory", Constants.EXPORT_PACKAGE, "org.acme.x;attr=a;mandatory:=\"attr,other\"");
        assertManifestError("mandatory", Constants.EXPORT_PACKAGE, "org.acme.x;mandatory:=version");
    }

    @Test
    public void testSyntaxErrors() throws Exception {
        assertManifestError("syntax", Constants.BUNDLE_VERSION, "1.x");
        assertManifestError("syntax", Constants.BUNDLE_VERSION, "1.0.0.bad qualifier");
        assertManifestError("syntax", Constants.EXPORT_PACKAGE, "org.acme.x;version=\"one\"");
        assertManifestError("syntax", Constants.IMPORT_PACKAGE, "org.acme.x;version=\"[1.0,\"");
        assertManifestError("syntax", Constants.IMPORT_PACKAGE, "org.acme.x;bundle-version=\"(1.0\"");
        assertManifestError("syntax", Constants.REQUIRE_BUNDLE, "acme.one;bundle-version=\"1.0.0.0.0\"");
        assertManifestError("syntax", Constants.FRAGMENT_HOST, "acme.host;bundle-version=\"x\"");
        assertManifestError("syntax", Constants.DYNAMICIMPORT_PACKAGE, "org.acme.*;version=\"x\"");
        assertManifestError("not a symbolic name");
        assertManifestError("acme/name");
        assertManifestError("acme..name");
        assertManifestError(".acme");
    }

    @Test
    public void testUnrecognizedDirectiveValue() throws Exception {
        assertManifestError("directive", Constants.IMPORT_PACKAGE, "org.acme.x;resolution:=sometimes");
        assertManifestError("directive", Constants.REQUIRE_BUNDLE, "acme.one;resolution:=sometimes");
        assertManifestError("directive", Constants.REQUIRE_BUNDLE, "acme.one;visibility:=public");
        assertManifestError("directive", Constants.REQUIRE_CAPABILITY, "acme.cap;resolution:=sometimes");
        assertManifestError("directive", Constants.REQUIRE_CAPABILITY, "acme.cap;cardinality:=many");
        assertManifestError("directive;singleton:=maybe");
        assertManifestError("directive;fragment-attachment:=sometimes");
        assertManifestError("directive", Constants.BUNDLE_ACTIVATIONPOLICY, "eager");
    }

    @Test
    public void testDuplicateRequireBundle() throws Exception {
        assertManifestError("dup-req", Constants.REQUIRE_BUNDLE, "acme.one,acme.two,acme.one;bundle-version=\"1.0\"");
    }

    @Test
    public void testBundleDataFileCompareAndSigners() throws Exception {
        Bundle bundle = install("bundle-api");
        Bundle fragment = install("bundle-api-fragment", Constants.FRAGMENT_HOST, "bundle-api");
        Bundle system = getSystemContext().getBundle(0);
        try {
            // The data file is in the storage area of the bundle, a fragment has none
            File dataFile = bundle.getDataFile("data.txt");
            assertNotNull("Data file", dataFile);
            assertEquals("data.txt", dataFile.getName());
            assertEquals(bundle.getDataFile(""), dataFile.getParentFile());
            assertNotNull("System bundle data file", system.getDataFile("data.txt"));
            assertNull("No data file for a fragment", fragment.getDataFile("data.txt"));

            // Bundles are ordered by their id
            assertTrue(system.compareTo(bundle) < 0);
            assertTrue(fragment.compareTo(bundle) > 0);
            assertEquals(0, bundle.compareTo(bundle));
            assertEquals(Arrays.asList(system, bundle, fragment), new ArrayList<Bundle>(new TreeSet<Bundle>(Arrays.asList(fragment, bundle, system))));

            // A bundle that is not signed has no signers
            assertTrue(bundle.getSignerCertificates(Bundle.SIGNERS_ALL).isEmpty());
            assertTrue(bundle.getSignerCertificates(Bundle.SIGNERS_TRUSTED).isEmpty());
            assertTrue(system.getSignerCertificates(Bundle.SIGNERS_ALL).isEmpty());
            try {
                bundle.getSignerCertificates(0);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ex) {
                // expected
            }
        } finally {
            fragment.uninstall();
            bundle.uninstall();
        }
        try {
            bundle.getDataFile("data.txt");
            fail("IllegalStateException expected");
        } catch (IllegalStateException ex) {
            // expected
        }
    }

    private void assertManifestError(String symbolicName, String... headers) throws Exception {
        String message = symbolicName + " " + java.util.Arrays.asList(headers);
        try {
            install(symbolicName, headers).uninstall();
            fail("BundleException expected: " + message);
        } catch (BundleException ex) {
            assertEquals(message + ": " + ex, BundleException.MANIFEST_ERROR, ex.getType());
        }
    }

    private Bundle install(String symbolicName, String... headers) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        for (int i = 0; i < headers.length; i += 2)
            attributes.putValue(headers[i], headers[i + 1]);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        InputStream input = new ByteArrayInputStream(baos.toByteArray());
        return getSystemContext().installBundle("manifest-validation:" + symbolicName, input);
    }
}
