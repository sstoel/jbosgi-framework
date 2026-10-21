/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues;

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
 * A manifest header with an unterminated single quote passes the validation at install. The parser of the metadata
 * fails later, during the resolve, with an unchecked exception.
 *
 * <h2>Severity</h2>
 * minor. As fuzz/10, with a single quote: the error comes from the resolve instead of the install.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManifestSyntaxValidator.assertQuotesTerminated}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of fuzz/10 (fw-minor-B02).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.2.1 and Bundle.installBundle: an invalid manifest fails the install with MANIFEST_ERROR.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManifestSyntaxValidator.java:223-235 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The check knew only the double quote, the parser of the metadata and the validator's own split take a single quote as
 * a quote too.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=UnterminatedSingleQuoteIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The install fails with MANIFEST_ERROR, a terminated single quote is accepted.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class UnterminatedSingleQuoteIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testUnterminatedSingleQuote() throws Exception {
        String location = "unterminated-single-quote";
        try {
            install(location, "lazy;include:='a").uninstall();
            fail("Bundle-ActivationPolicy: lazy;include:='a must fail the install");
        } catch (BundleException ex) {
            Bundle leftOver = getSystemContext().getBundle(location);
            if (leftOver != null)
                leftOver.uninstall();
            assertEquals("The install fails with MANIFEST_ERROR: " + ex, BundleException.MANIFEST_ERROR, ex.getType());
            assertNull("No bundle is installed", leftOver);
        }
    }

    @Test
    public void testTerminatedSingleQuote() throws Exception {
        install("terminated-single-quote", "lazy;include:='a,b'").uninstall();
    }

    private Bundle install(String location, String policy) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, location);
        attributes.putValue(Constants.BUNDLE_ACTIVATIONPOLICY, policy);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return getSystemContext().installBundle(location, new ByteArrayInputStream(baos.toByteArray()));
    }
}
