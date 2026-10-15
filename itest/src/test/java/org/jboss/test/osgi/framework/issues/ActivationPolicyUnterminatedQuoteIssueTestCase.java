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
 * A Bundle-ActivationPolicy whose include/exclude directive has an unterminated quote passes the manifest syntax
 * validation, and then fails the install with a StringIndexOutOfBoundsException after the bundle was added to the
 * framework: installBundle throws a BundleException of type UNSPECIFIED and the half-installed bundle stays
 * installed.
 *
 * <h3>Severity</h3>
 * minor - malformed header only, but the framework state is left inconsistent (see also fuzz/07, the same missing
 * rollback reached from a different failure).
 *
 * <h3>Component</h3>
 * jbosgi-metadata {@code org.jboss.osgi.metadata.internal.ActivationPolicyMDValueCreator#getPackageList};
 * jbosgi-framework core {@code BundleManifestSyntaxValidator} (Bundle-ActivationPolicy check),
 * {@code UserBundleRevisionFactory#create} ({@code userBundle.initLazyActivation()} after
 * {@code installBundleRevision}).
 *
 * <h3>Found by</h3>
 * fuzz workstream, Jazzer manifest install fuzzer (oracle "bundle count changed after failed install"); crash input
 * {@code Bundle-ActivationPolicy: lazy;ifclude:=#org.acme.a"acme.a";exclude:=";exclude:="b"}, minimized to
 * {@code Bundle-ActivationPolicy: lazy;include:="}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.12 Bundle Validity: "Any syntactic error ... causes a bundle to fail to install"; 1.3.2 quoted-string
 * syntax. BundleContext.installBundle (10.1.7.19): "Postconditions, when an exception is thrown: Bundle is not
 * installed." Commit 5ceeef0b: unrecognized Bundle-ActivationPolicy values fail the install with a MANIFEST_ERROR.
 *
 * <h3>Code location</h3>
 * jbosgi-metadata {@code src/main/java/org/jboss/osgi/metadata/internal/ActivationPolicyMDValueCreator.java:68-71}:
 * for the directive {@code include:="} the value is {@code "}, it starts and ends with a quote, and
 * {@code directive.substring(1, directive.length() - 1)} is {@code substring(1, 0)}.
 * The header is first parsed in jbosgi-framework
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevisionFactory.java:98}
 * ({@code userBundle.initLazyActivation()}), after {@code installBundleRevision} at :97 added the revision to the
 * environment; {@code handleCreateException} (:114-123) does not remove it. BundleManifestSyntaxValidator checks only
 * the policy name ({@code lazy}) of this header.
 *
 * <h3>Root cause</h3>
 * Unchecked quote stripping in the activation policy parser, the header is not validated before the bundle is added,
 * and the install has no rollback (fuzz/07).
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ActivationPolicyUnterminatedQuoteIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testInstallFailsWithManifestError: The install must fail with MANIFEST_ERROR, but: BundleException: JBOSGI011231: Cannot
 *   create bundle revision from: [activation-policy-quote-type:0.0.0,...] caused by java.lang.StringIndexOutOfBoundsException:
 *   Range [1, 0) out of bounds for length 1 expected:&lt;3&gt; but was:&lt;0&gt;
 * testNoBundleLeftAfterFailedInstall: installBundle threw BundleException: JBOSGI011231: ..., but a bundle is still
 *   installed at location activation-policy-quote-leak expected null, but was:&lt;activation-policy-quote-leak:0.0.0&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * installBundle fails with a BundleException of type MANIFEST_ERROR before the bundle is added, and no bundle is left
 * installed for the location.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 is lenient and installs the bundle (the include list is empty); it does not leave a failed install
 * behind.
 *
 * <h3>Suggested fix</h3>
 * Validate the include/exclude directives of Bundle-ActivationPolicy in BundleManifestSyntaxValidator (with the same
 * quote aware tokenizer as the other headers), guard the quote stripping in getPackageList, and roll back the
 * environment on any install failure (fuzz/07).
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41 (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class ActivationPolicyUnterminatedQuoteIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testInstallFailsWithManifestError() throws Exception {
        String location = "activation-policy-quote-type";
        try {
            install(location).uninstall();
            fail("Bundle-ActivationPolicy: lazy;include:=\" must fail the install");
        } catch (BundleException ex) {
            Bundle leftOver = getSystemContext().getBundle(location);
            if (leftOver != null)
                leftOver.uninstall();
            assertEquals("The install must fail with MANIFEST_ERROR, but: " + ex + " caused by " + ex.getCause(), BundleException.MANIFEST_ERROR,
                    ex.getType());
        }
    }

    @Test
    public void testNoBundleLeftAfterFailedInstall() throws Exception {
        String location = "activation-policy-quote-leak";
        try {
            install(location).uninstall();
        } catch (BundleException ex) {
            Bundle leftOver = getSystemContext().getBundle(location);
            if (leftOver != null)
                leftOver.uninstall();
            assertNull("installBundle threw " + ex + ", but a bundle is still installed at location " + location, leftOver);
        }
    }

    private Bundle install(String location) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, location);
        attributes.putValue(Constants.BUNDLE_ACTIVATIONPOLICY, "lazy;include:=\"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle(location, new ByteArrayInputStream(baos.toByteArray()));
    }
}
