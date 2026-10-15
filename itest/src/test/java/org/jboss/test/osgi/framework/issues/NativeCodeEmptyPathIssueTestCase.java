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

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A Bundle-NativeCode header with an empty library path is accepted at install, and resolving the bundle then
 * throws StringIndexOutOfBoundsException out of FrameworkWiring.resolveBundles.
 *
 * <h3>Severity</h3>
 * minor - malformed header only, but an unchecked exception escapes the lifecycle API on every resolve attempt.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code BundleManifestSyntaxValidator} (no Bundle-NativeCode validation) and
 * {@code ModuleManagerImpl#addNativeResourceLoader}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, Jazzer manifest install fuzzer (deep mode). Crash input
 * {@code Bundle-NativeCode: \0\0\0\0\0\0\0;osname=Linux;osname=MacOSX;processor=x86;processor=aarch64;...}, minimized to
 * {@code Bundle-NativeCode: ;osname=<os>;processor=<processor>} (also {@code "";osname=...}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10: {@code Bundle-NativeCode ::= nativecode ( ',' nativecode )* ( ',' optional ) ?},
 * {@code nativecode ::= path ( ';' path )* ( ';' parameter )*} - a clause needs a path. "a bundle can be installed
 * without an exception if the header is properly formatted according to its syntax"; 3.12: "Any syntactic error ...
 * causes a bundle to fail to install". FrameworkWiring.resolveBundles (10.4.8.5) declares only IllegalArgumentException for bundles of another framework.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:532}
 * (substring on the library path, which is empty: {@code Range [0, -1) out of bounds for length 0}), reached from
 * {@code ModuleManagerImpl.createHostModule(:328)} &lt;- {@code FrameworkResolverImpl.applyResolverResults(:273)}.
 * {@code BundleManifestSyntaxValidator.PARAMETERIZED_HEADERS} deliberately leaves out Bundle-NativeCode, and the
 * jbosgi-metadata parser turns the leading {@code ;} into an empty path.
 *
 * <h3>Root cause</h3>
 * The empty path is not rejected at install; when the clause is selected, the native resource loader computes the
 * library name with substring arithmetic that assumes a non-empty path.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NativeCodeEmptyPathIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testInstallRejected: A Bundle-NativeCode clause without a path must fail the install
 * testResolveDoesNotThrow: resolveBundles must not throw, but threw: java.lang.StringIndexOutOfBoundsException:
 *   Range [0, -1) out of bounds for length 0
 * </pre>
 *
 * <h3>Expected</h3>
 * installBundle fails with a BundleException (MANIFEST_ERROR or NATIVECODE_ERROR).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 rejects the install: {@code BundleException: Invalid manifest header Bundle-NativeCode:
 * ";osname=MacOSX;processor=aarch64"} (type MANIFEST_ERROR).
 *
 * <h3>Suggested fix</h3>
 * Validate Bundle-NativeCode in BundleManifestSyntaxValidator: every clause except the trailing {@code *} must have
 * at least one non-empty path; osversion values must be version ranges and selection-filter values valid filters
 * (NATIVECODE_ERROR, as 3.10 requires for an invalid selection-filter).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing; the R8 branch added syntax validation for the other headers only.
 *
 * @since 13-Oct-2026
 */
public class NativeCodeEmptyPathIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testInstallRejected() throws Exception {
        Bundle bundle;
        try {
            bundle = install("native-empty-path-install");
        } catch (BundleException ex) {
            return;
        }
        try {
            fail("A Bundle-NativeCode clause without a path must fail the install");
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testResolveDoesNotThrow() throws Exception {
        Bundle bundle;
        try {
            bundle = install("native-empty-path-resolve");
        } catch (BundleException ex) {
            // Rejected at install, as expected
            return;
        }
        try {
            getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
        } catch (RuntimeException ex) {
            fail("resolveBundles must not throw, but threw: " + ex);
        } finally {
            bundle.uninstall();
        }
    }

    private Bundle install(String symbolicName) throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(Constants.BUNDLE_NATIVECODE, ";osname=\"" + osname + "\";processor=\"" + processor + "\"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        jar.putNextEntry(new JarEntry("lib/a.so"));
        jar.write(new byte[] { 0, 1, 2, 3 });
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle("native-empty-path:" + symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
