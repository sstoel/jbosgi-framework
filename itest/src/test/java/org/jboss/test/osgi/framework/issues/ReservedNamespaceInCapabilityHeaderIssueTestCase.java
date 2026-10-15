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
import static org.junit.Assert.fail;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * The manifest checks that a bundle does not use reserved namespaces in Provide-Capability/Require-Capability are
 * incomplete: osgi.ee and osgi.native are rejected, but osgi.wiring.package, osgi.wiring.bundle and
 * osgi.wiring.host are accepted in both headers, and Bundle-SymbolicName with a mandatory attribute that is not
 * defined is accepted.
 *
 * <h3>Severity</h3>
 * minor. {@code Provide-Capability: osgi.wiring.package;osgi.wiring.package=foo} creates a package capability
 * (with version 0.0.0 but no bundle-symbolic-name/bundle-version) that Import-Package clauses can be wired to,
 * although the bundle exports nothing for class loading; {@code Require-Capability: osgi.wiring.bundle} creates a
 * Require-Bundle-like wire without the Require-Bundle semantics. {@code Bundle-SymbolicName: b;mandatory:=a}
 * without attribute a makes the bundle unrequirable instead of failing the install.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code BundleRevisionValidatorR4} (reserved namespaces) and
 * {@code BundleManifestSyntaxValidator} (mandatory attributes, new in this branch, commit 5ceeef0b).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code cap-pc-pkg},
 * {@code cap-pc-bundle}, {@code cap-pc-host}, {@code cap-rc-pkg}, {@code cap-rc-bundle}, {@code cap-rc-host},
 * {@code mand-bsn-missing}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html), 8.3: "The
 * requirements and capabilities in the osgi.wiring.package namespace must never be used in the Require-Capability
 * and Provide-Capability headers."; 8.4: "The requirements and capabilities in the osgi.wiring.bundle namespace
 * must not be used in the Require-Capability and Provide-Capability headers."; 8.5: the same for osgi.wiring.host.
 * (8.2/8.7 say the same for Provide-Capability in osgi.ee/osgi.native, which jbosgi enforces with
 * MANIFEST_ERROR.)<br>
 * 3.12 Bundle Validity (framework.module.html): "Export-Package, Bundle-SymbolicName, or Fragment-Host with a
 * mandatory attribute that is not defined" causes a bundle to fail to install.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleRevisionValidatorR4.java:117-123} (only
 * osgi.native and osgi.ee are checked, only for Provide-Capability);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManifestSyntaxValidator.java:113-128} (the
 * mandatory check only covers Export-Package; the Bundle-SymbolicName loop at :88-96 does not check it).
 *
 * <h3>Root cause</h3>
 * The checks list only part of the reserved namespaces and of the headers named in 3.12.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ReservedNamespaceInCapabilityHeaderIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testProvidePackageCapability: Provide-Capability: osgi.wiring.package;osgi.wiring.package=res.p must fail to
 *   install with a MANIFEST_ERROR, but the bundle was installed
 * testRequireBundleCapability: Require-Capability: osgi.wiring.bundle;filter:="(osgi.wiring.bundle=res.b)" must
 *   fail to install with a MANIFEST_ERROR, but the bundle was installed
 * testProvideHostCapability: Provide-Capability: osgi.wiring.host;osgi.wiring.host=res.h ... was installed
 * testSymbolicNameWithUndefinedMandatoryAttribute: Bundle-SymbolicName: res-mandatory;mandatory:=a ... was installed
 * </pre>
 *
 * <h3>Expected</h3>
 * Each install fails with a BundleException of type MANIFEST_ERROR.
 *
 * <h3>Reference behaviour</h3>
 * Felix 7.0.5 rejects all four manifests. Equinox 3.23.0 accepts them (it only rejects osgi.ee, osgi.native and
 * osgi.identity capabilities), so the reference implementations differ; the spec text is unambiguous.
 *
 * <h3>Suggested fix</h3>
 * In BundleRevisionValidatorR4 reject the three osgi.wiring.* namespaces in Provide-Capability and
 * Require-Capability, and osgi.identity in Provide-Capability; in BundleManifestSyntaxValidator apply the
 * Export-Package mandatory check to the Bundle-SymbolicName clause as well. Verified: with these two checks the
 * four test methods pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 accepts them too).
 */
public class ReservedNamespaceInCapabilityHeaderIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testProvidePackageCapability() throws Exception {
        assertRejected("res-pc-pkg", Constants.PROVIDE_CAPABILITY, "osgi.wiring.package;osgi.wiring.package=res.p");
    }

    @Test
    public void testRequireBundleCapability() throws Exception {
        assertRejected("res-rc-bundle", Constants.REQUIRE_CAPABILITY, "osgi.wiring.bundle;filter:=\"(osgi.wiring.bundle=res.b)\"");
    }

    @Test
    public void testProvideHostCapability() throws Exception {
        assertRejected("res-pc-host", Constants.PROVIDE_CAPABILITY, "osgi.wiring.host;osgi.wiring.host=res.h");
    }

    @Test
    public void testSymbolicNameWithUndefinedMandatoryAttribute() throws Exception {
        assertRejected("res-mandatory", Constants.BUNDLE_SYMBOLICNAME, "res-mandatory;mandatory:=a");
    }

    private void assertRejected(String name, String header, String value) throws Exception {
        Bundle bundle;
        try {
            bundle = installBundle(getArchive(name, header, value));
        } catch (BundleException ex) {
            assertEquals(header + ": " + value + " must fail to install with a MANIFEST_ERROR, but the type was " + ex.getType(),
                    BundleException.MANIFEST_ERROR, ex.getType());
            return;
        }
        bundle.uninstall();
        fail(header + ": " + value + " must fail to install with a MANIFEST_ERROR, but the bundle was installed");
    }

    private JavaArchive getArchive(final String name, final String header, final String value) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                if (Constants.BUNDLE_SYMBOLICNAME.equals(header)) {
                    builder.addBundleSymbolicName(value);
                } else {
                    builder.addBundleSymbolicName(name);
                    builder.addManifestHeader(header, value);
                }
                return builder.openStream();
            }
        });
        return archive;
    }
}
