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
 * An extension bundle whose Fragment-Host names the system bundle by its implementation-specific symbolic name
 * ({@code org.jboss.osgi.framework}, what {@code getBundle(0).getSymbolicName()} returns) instead of the alias
 * {@code system.bundle} is rejected at install with "Invalid Fragment-Host for extension fragment".
 *
 * <h3>Severity</h3>
 * minor. Extension bundles built for this framework by name ({@code Fragment-Host: org.jboss.osgi.framework;
 * extension:=framework}) cannot be installed; only the alias works.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code BundleRevisionValidatorR4.validateBundleRevision} (and
 * {@code FrameworkExtensions.isExtension}), which compare the host name with the literal {@code system.bundle}.
 *
 * <h3>Found by</h3>
 * manifest workstream, round 4 (second opinion on the PASS-code matrix row framework.module#215, previously verified
 * by code reading with the assumption "the implementation BSN of the system bundle is system.bundle itself"; it is
 * {@code org.jboss.osgi.framework}): {@code explore/passcode PassCodeFrameworkTestCase.testExtensionWithSystemBundleName}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.14.1 Fragment-Host / 3.15 Extension Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "The fragment must be the bundle
 * symbolic name of the implementation specific system bundle or the alias system.bundle."; 4.6 The System Bundle:
 * "the name system.bundle must be recognized as an alias to this implementation-defined name."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleRevisionValidatorR4.java:131-132} and {@code :140};
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkExtensions.java:107-112};
 * {@code SystemBundleState.java:79-81} (getSymbolicName returns Constants.JBOSGI_FRAMEWORK_SYMBOLIC_NAME).
 *
 * <h3>Root cause</h3>
 * Only the alias is recognized as the system bundle's name in the extension checks.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ExtensionHostImplementationNameIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testExtensionWithImplementationSymbolicName: Fragment-Host: org.jboss.osgi.framework;extension:=framework names the
 *   system bundle by its implementation symbolic name and must be accepted, but: org.osgi.framework.BundleException:
 *   JBOSGI011241: Invalid Fragment-Host for extension fragment: FragmentBundleRevision[ext-impl-name:0.0.0]
 * </pre>
 *
 * <h3>Expected</h3>
 * The extension installs (and, once the system bundle's capabilities carry the implementation name as well, attaches).
 *
 * <h3>Reference behaviour</h3>
 * Equinox accepts Fragment-Host: org.eclipse.osgi;extension:=framework (its osgi.wiring.host capability carries
 * both names, [org.eclipse.osgi, system.bundle]; see the harness case rb-system).
 *
 * <h3>Suggested fix</h3>
 * Accept {@code getSymbolicName()} of the system bundle as well as {@code system.bundle} in BundleRevisionValidatorR4
 * and FrameworkExtensions.isExtension. Verified: with that change the test passes (JDK 25). For the extension to
 * also resolve, the system bundle's osgi.wiring.host capability must carry the implementation name too, which is the
 * root cause of services/14 (system bundle metadata uses system.bundle/0.0.0); with only this fix it installs but
 * does not attach.
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class ExtensionHostImplementationNameIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testExtensionWithImplementationSymbolicName() throws Exception {
        final String name = getSystemContext().getBundle().getSymbolicName();
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "ext-impl-name");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.FRAGMENT_HOST, name + ";extension:=framework");
                return builder.openStream();
            }
        });
        Bundle extension = null;
        try {
            extension = installBundle(archive);
        } catch (BundleException ex) {
            fail("Fragment-Host: " + name + ";extension:=framework names the system bundle by its implementation symbolic name and must be accepted, but: " + ex);
        }
        // attaching it additionally needs the system bundle's host capability to carry the implementation name (services/14)
        extension.uninstall();
    }
}
