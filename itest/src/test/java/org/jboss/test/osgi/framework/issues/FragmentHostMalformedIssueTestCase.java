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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * A malformed Fragment-Host header makes BundleContext.installBundle throw an unchecked exception
 * (IllegalArgumentException, StringIndexOutOfBoundsException) instead of a BundleException, or is accepted
 * without a host name.
 *
 * <h3>Severity</h3>
 * minor - only malformed manifests are affected, but the caller (e.g. a deployer that handles BundleException)
 * gets an undeclared RuntimeException; the stream/VFS clean up depends on that exception path.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin#installBundleRevision}
 * and {@code BundleManifestSyntaxValidator}; parser in jbosgi-metadata
 * {@code org.jboss.osgi.metadata.internal.ManifestParser}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, Jazzer coverage-guided manifest install fuzzer ({@code InstallFuzzer}); minimized crash inputs:
 * {@code Fragment-Host: } (empty), {@code Fragment-Host: acme.host;x=1;b},
 * {@code Fragment-Host: acme.host;x:List<String=a}; plus {@code Fragment-Host: ;bundle-version=1.0} from the Equinox
 * differential (accepted by jbosgi, MANIFEST_ERROR on Equinox).
 *
 * <h3>Spec reference</h3>
 * BundleContext.installBundle(String, InputStream) javadoc (OSGi Core R8, 10.1.7.19): "Throws BundleException - If
 * the installation failed. BundleException types thrown by this method include: ... BundleException.MANIFEST_ERROR";
 * no unchecked exception other than SecurityException / IllegalStateException (invalid context) is declared.
 * OSGi Core R8, 3.12 Bundle Validity: "The following (non-exhaustive) list of errors causes a bundle to fail to
 * install: ... Any syntactic error (for example, improperly formatted version or bundle symbolic name, unrecognized
 * directive value, etc.)." 3.14.1 Fragment-Host: {@code Fragment-Host ::= bundle-description},
 * {@code bundle-description ::= symbolic-name (';' parameter)*}.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:547}
 * ({@code if (metadata.getFragmentHost() == null)}) parses the header with jbosgi-metadata's ManifestParser, which
 * throws {@code IllegalArgumentException("Invalid null header")} (ManifestParser.java:81),
 * {@code IllegalArgumentException("Path [b] should appear before attributes ...")} (:152) and
 * {@code StringIndexOutOfBoundsException} for {@code List<} without {@code >} (:134,
 * {@code type.substring(index + 1, type.indexOf('>'))}); the catch block at :552 rethrows the RuntimeException
 * as is. {@code BundleManifestSyntaxValidator} (commit 5ceeef0b, which promised a MANIFEST_ERROR for syntax errors)
 * runs just before, but its own lenient tokenizer accepts these values and it does not check that a Fragment-Host
 * clause names a host. The other headers do not show the problem because they are parsed inside
 * {@code OSGiMetaDataBuilder.validateMetadata} / the resource builder, which wrap the parser exception.
 *
 * <h3>Root cause</h3>
 * The Fragment-Host header is parsed for the first time outside of any code that translates parser
 * RuntimeExceptions into a BundleException, and the syntax validator does not reject what the parser rejects.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FragmentHostMalformedIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testEmptyFragmentHost: Fragment-Host: '' must fail with a BundleException, but installBundle threw
 *   java.lang.IllegalArgumentException: Invalid null header
 * testPathAfterAttribute: Fragment-Host: 'acme.host;x=1;b' must fail with a BundleException, but installBundle threw
 *   java.lang.IllegalArgumentException: Path [b] should appear before attributes and directives in clause: acme.host;x=1;b
 * testUnterminatedListType: Fragment-Host: 'acme.host;x:List&lt;String=a' must fail with a BundleException, but installBundle
 *   threw java.lang.StringIndexOutOfBoundsException: Range [5, -1) out of bounds for length 11
 * testNoHostName: Fragment-Host: ';bundle-version=1.0' must fail with a BundleException, but the bundle was installed
 * </pre>
 *
 * <h3>Expected</h3>
 * installBundle fails with a BundleException of type MANIFEST_ERROR for each of these headers.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: empty value, {@code ;bundle-version=1.0} and {@code acme.host;x:List<String=a} are rejected with a
 * BundleException of type MANIFEST_ERROR ("Invalid manifest header Fragment-Host"); {@code acme.host;x=1;b} is
 * accepted (Equinox ignores the stray path). No RuntimeException escapes on Equinox.
 *
 * <h3>Suggested fix</h3>
 * Parse the Fragment-Host header inside the try block that converts RuntimeException into
 * {@code new BundleException(..., MANIFEST_ERROR, ex)} (e.g. call {@code metadata.getFragmentHost()} in
 * {@code OSGiMetaDataBuilder.validateMetadata}, or catch RuntimeException around it in installBundleRevision), and
 * let BundleManifestSyntaxValidator require a symbolic name for Fragment-Host (and Require-Bundle). Fix the
 * {@code indexOf('>')} handling in ManifestParser.
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 has the same {@code getFragmentHost()} call; the R8 branch's syntax validation does not cover it.
 *
 * @since 13-Oct-2026
 */
public class FragmentHostMalformedIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testEmptyFragmentHost() throws Exception {
        assertManifestError("fh-empty", "");
    }

    @Test
    public void testPathAfterAttribute() throws Exception {
        assertManifestError("fh-path-after-attribute", "acme.host;x=1;b");
    }

    @Test
    public void testUnterminatedListType() throws Exception {
        assertManifestError("fh-list-type", "acme.host;x:List<String=a");
    }

    @Test
    public void testNoHostName() throws Exception {
        assertManifestError("fh-no-host", ";bundle-version=1.0");
    }

    private void assertManifestError(String symbolicName, String fragmentHost) throws Exception {
        Bundle bundle;
        try {
            bundle = install(symbolicName, fragmentHost);
        } catch (BundleException ex) {
            assertEquals("Fragment-Host: '" + fragmentHost + "' must fail with MANIFEST_ERROR, but: " + ex, BundleException.MANIFEST_ERROR, ex.getType());
            return;
        } catch (RuntimeException ex) {
            fail("Fragment-Host: '" + fragmentHost + "' must fail with a BundleException, but installBundle threw " + ex);
            return;
        }
        bundle.uninstall();
        fail("Fragment-Host: '" + fragmentHost + "' must fail with a BundleException, but the bundle was installed");
    }

    private Bundle install(String symbolicName, String fragmentHost) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(Constants.FRAGMENT_HOST, fragmentHost);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle("fragment-host-malformed:" + symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
