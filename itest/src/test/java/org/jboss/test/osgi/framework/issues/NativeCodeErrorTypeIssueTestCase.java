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
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * When a bundle cannot be resolved because no Bundle-NativeCode clause matches the platform, start() throws a
 * BundleException of type RESOLVE_ERROR instead of NATIVECODE_ERROR.
 *
 * <h3>Severity</h3>
 * cosmetic. Callers cannot tell a native code problem from other resolution failures by the exception type. (Equinox
 * 3.23.0 and Felix 7.0.5 also report RESOLVE_ERROR; the spec sentence is nevertheless unambiguous.)
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code AbstractBundleState.ensureResolved} / {@code handleResolverException} (every
 * resolver failure becomes RESOLVE_ERROR) and {@code NativeCodeImpl.resolveNativeCode}
 * (noNativeCodeClauseSelected has no type).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, case {@code nc-start-nomatch}),
 * while building the compliance matrix entry for framework.module#176.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10 Loading Native Code Libraries
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html): "The framework must report a Bundle
 * Exception with the NATIVECODE_ERROR as error code when the bundle can not be resolved due to a native code
 * problem."; BundleException.NATIVECODE_ERROR: "The bundle could not be resolved due to an error with the
 * Bundle-NativeCode header."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleState.java:571} and {@code :580}
 * ({@code handleResolverException(fireEvent, BundleException.RESOLVE_ERROR, ...)});
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:222}.
 *
 * <h3>Root cause</h3>
 * The osgi.native requirement failure (and the NativeCodeImpl clause selection failure) is not distinguished from
 * other resolver failures.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=NativeCodeErrorTypeIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testNoMatchingClause: A bundle that cannot be resolved because no native code clause matches must fail with
 *   NATIVECODE_ERROR expected:&lt;8&gt; but was:&lt;4&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code BundleException.getType() == BundleException.NATIVECODE_ERROR} (8).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 report RESOLVE_ERROR (4) as well.
 *
 * <h3>Suggested fix</h3>
 * When the unresolved requirement of the bundle is its osgi.native requirement (or NativeCodeImpl selects no
 * clause), throw the start BundleException with NATIVECODE_ERROR. Verified: choosing NATIVECODE_ERROR in
 * UserBundleState.startInternalNow when the last resolver exception concerns the osgi.native requirement makes the
 * test pass, and NativeCodeTestCase still passes (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class NativeCodeErrorTypeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testNoMatchingClause() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "native-error-type");
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libfoo.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, "lib/libfoo.so; osname=NoSuchOS; processor=nosuch");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            fail("The bundle must not start, no native code clause matches");
        } catch (BundleException ex) {
            assertEquals("A bundle that cannot be resolved because no native code clause matches must fail with NATIVECODE_ERROR",
                    BundleException.NATIVECODE_ERROR, ex.getType());
        } finally {
            bundle.uninstall();
        }
    }
}
