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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.jboss.test.osgi.framework.launch.support.VersionedType;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A framework extension bundle that imports a package it also exports (the default output of bnd for an exported
 * package) never resolves: its Import-Package is only matched against the system bundle and the extensions that are
 * already attached, not against its own export.
 *
 * <h3>Severity</h3>
 * major - extension bundles are new in this branch (OSGi R7/R8 support); bnd/maven-bundle-plugin add an import for
 * every exported package by default, so typical extension bundles that export an API stay INSTALLED and their
 * packages are never available.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkExtensions} ({@code resolve} /
 * {@code getWires}).
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of extension bundle scenarios (extension with
 * {@code Export-Package: r} and {@code Import-Package: r}: RESOLVED on Equinox 3.21, INSTALLED on jbosgi).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.15.1 and 3.15.2 (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.extensionbundles):
 * "Extension bundles are permitted to specify an Export-Package header. Any exported packages specified by an
 * extension bundle must be hosted (exported) by the System Bundle when the extension bundle is resolved. Extension
 * bundles are permitted to specify Import-Package and Require-Capability headers to declare dependencies on packages
 * and capabilities." "Extension bundle requirements may only be wired to packages and capabilities provided by the
 * system bundle or another extension bundle that is also attached to the system bundle." 3.8.1 Importing and
 * Exporting the Same Package: the framework must be able to choose the bundle's own export for the import.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkExtensions.java:196-208}
 * builds the provider list from the system bundle revision and the already attached extensions only, and
 * {@code :280-302} ({@code getWires}) returns false for any mandatory requirement without a match in that list.
 * Introduced with the class in commit 8d863429 "Implement OSGi Core R7".
 *
 * <h3>Root cause</h3>
 * The extension's own capabilities are not among the providers while its requirements are matched (it only becomes
 * part of the system bundle in {@code attach}, after all requirements were satisfied), so an import of its own export
 * can never be satisfied.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ExtensionImportsOwnExportIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testExtensionImportingItsOwnExportResolves: An extension that imports the package it exports must resolve,
 *   but resolveBundles returned false and the state is INSTALLED expected:&lt;true&gt; but was:&lt;false&gt;
 * testImporterOfExtensionPackageResolves: A bundle that imports the package of the extension must resolve
 *   expected:&lt;true&gt; but was:&lt;false&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The extension resolves (its import is satisfied by its own export, which the system bundle hosts once attached),
 * the system bundle exports the package and a normal bundle that imports it resolves and loads the class from the
 * framework.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: the extension is RESOLVED, an importer of the package is wired to the system bundle and loads the
 * class.
 *
 * <h3>Suggested fix</h3>
 * In {@code FrameworkExtensions.resolve}, add the candidate extension itself to the providers used for its own
 * requirements (its package capabilities, which become system bundle exports when attached); alternatively drop an
 * Import-Package requirement that matches one of the extension's own exports, as the resolver does for a substituted
 * export.
 *
 * <h3>Regression vs WF41</h3>
 * no (new feature) - WF41 did not support framework extensions at all; the defect is in the extension support added
 * by this branch.
 *
 * @since 13-Oct-2026
 */
public class ExtensionImportsOwnExportIssueTestCase extends AbstractFrameworkLaunchTest {

    private static final String TYPE_NAME = VersionedType.class.getName();
    private static final String PACKAGE_NAME = VersionedType.class.getPackage().getName();

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testExtensionImportingItsOwnExportResolves() throws Exception {
        startFramework();
        Bundle extension = installExtension();
        boolean resolved = resolve(extension);
        assertEquals("An extension that imports the package it exports must resolve, but resolveBundles returned " + resolved
                + " and the state is " + (extension.getState() == Bundle.INSTALLED ? "INSTALLED" : "" + extension.getState()), true, resolved);
        assertEquals("The extension is RESOLVED", Bundle.RESOLVED, extension.getState());
    }

    @Test
    public void testImporterOfExtensionPackageResolves() throws Exception {
        startFramework();
        Bundle extension = installExtension();
        Bundle importer = install("importer", Constants.IMPORT_PACKAGE, PACKAGE_NAME, null);
        assertEquals("A bundle that imports the package of the extension must resolve", true, framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(extension, importer)));
        assertSame("The importer loads the class of the extension from the framework", framework.loadClass(TYPE_NAME), importer.loadClass(TYPE_NAME));
        assertTrue(extension.getState() == Bundle.RESOLVED);
    }

    private Bundle installExtension() throws Exception {
        return install("extension", Constants.FRAGMENT_HOST, "system.bundle; extension:=framework", VersionedType.class,
                Constants.EXPORT_PACKAGE, PACKAGE_NAME + ";version=1.0",
                Constants.IMPORT_PACKAGE, PACKAGE_NAME + ";version=\"[1.0,2)\"");
    }

    private void startFramework() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
    }

    private boolean resolve(Bundle bundle) {
        return framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
    }

    private Bundle install(String name, String key, String value, Class<?> content, String... moreHeaders) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "own.export." + name);
        attributes.putValue(key, value);
        for (int i = 0; i < moreHeaders.length; i += 2)
            attributes.putValue(moreHeaders[i], moreHeaders[i + 1]);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        if (content != null) {
            jos.putNextEntry(new JarEntry(content.getName().replace('.', '/') + ".class"));
            jos.write(getClassBytes(content));
            jos.closeEntry();
        }
        jos.close();
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(baos.toByteArray()));
    }

    private byte[] getClassBytes(Class<?> clazz) throws IOException {
        InputStream input = clazz.getResourceAsStream(clazz.getSimpleName() + ".class");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int len = input.read(buffer); len > 0; len = input.read(buffer))
                baos.write(buffer, 0, len);
            return baos.toByteArray();
        } finally {
            input.close();
        }
    }
}
