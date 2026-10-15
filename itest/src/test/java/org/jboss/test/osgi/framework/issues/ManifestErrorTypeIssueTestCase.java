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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * Several manifest errors make installBundle fail with a BundleException of type UNSPECIFIED (0) instead of
 * MANIFEST_ERROR (or NATIVECODE_ERROR for an invalid native code selection filter).
 *
 * <h3>Severity</h3>
 * minor. Callers (management tools, the WildFly deployer, the TCK) that tell a broken manifest from other
 * failures by {@code BundleException.getType()} misclassify these. Commit 5ceeef0b ("report invalid metadata as a
 * manifest error") converted the errors of the metadata builder and of the new syntax validator, but not those of
 * BundleRevisionValidatorR4 nor the resource builder.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core: {@code BundleRevisionValidatorR4} (messages from {@code FrameworkMessages} that do not
 * set a type), {@code BundleStateRevision.createResource} (wraps ResourceBuilderException without a type),
 * {@code UserBundleRevisionFactory.handleCreateException} (wraps any other exception as
 * cannotCreateBundleRevisionFromDeployment, type UNSPECIFIED).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox differential harness ({@code explore/manifest}: imp-twice, specv-*, exp-bsn-attr,
 * fh-ext-other, cap-filter-bad, s-empty-clause, ty-pc-15, v-bmv-0, nc-09 and others report type 0, Equinox 3).
 *
 * <h3>Spec reference</h3>
 * BundleContext.installBundle javadoc (osgi.core 8.0.0): "BundleException - If the installation failed.
 * BundleException types thrown by this method include: READ_ERROR, DUPLICATE_BUNDLE_ERROR, MANIFEST_ERROR, and
 * REJECTED_BY_HOOK."; BundleException.MANIFEST_ERROR: "The bundle manifest was in error."<br>
 * OSGi Core R8, 3.12 Bundle Validity (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html)
 * lists "Multiple imports of a given package", "Specification-version and version specified together ... but with
 * different values", "Any syntactic error", "Bundle-ManifestVersion value not equal to 2" as manifest errors;
 * 3.6.5: "The export statement must not specify an explicit bundle symbolic name nor bundle version";
 * 3.10: "If a selection-filter attribute contains an invalid filter, then the bundle must fail to install with a
 * Bundle Exception of type NATIVECODE_ERROR."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleRevisionValidatorR4.java:66,75,81,96,100,104,114,132}
 * (MESSAGES.unsupportedBundleManifestVersion, duplicatePackageImport,
 * packageVersionAndSpecificationVersionMissmatch, packageCannotSpecifyBundleSymbolicName/Version,
 * invalidFragmentHostForExtensionFragment: {@code FrameworkMessages.java:162-190} declare them without a type);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleStateRevision.java:120-121}
 * ({@code new BundleException(ex.getMessage(), ex)}, since ed1d06b6 2012);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleRevisionFactory.java:113-121}.
 *
 * <h3>Root cause</h3>
 * The BundleExceptions are created without a type; ResourceBuilderException (invalid filter, empty clause,
 * invalid typed value) and ResourceValidationException (invalid selection-filter) are wrapped as UNSPECIFIED.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=ManifestErrorTypeIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testDuplicateImport: Import-Package: me.p,me.p must be rejected with type 3, but the type was 0:
 *   JBOSGI011234: Duplicate import of package 'me.p' ...
 * testVersionAndSpecificationVersionDiffer: ... type was 0: JBOSGI011237: Version and specification version ...
 * testExportWithBundleSymbolicName: ... type was 0: JBOSGI011238: Package 'me.p' cannot specify explicit ...
 * testExtensionOfOtherHost: ... type was 0: JBOSGI011241: Invalid Fragment-Host for extension fragment ...
 * testBundleManifestVersionZero: ... type was 0: JBOSGI011232: Unsupported bundle manifest version 0 ...
 * testInvalidRequirementFilter: ... type was 0: JBOSGI010908: Cannot initialize resource from: XCapabilityRequirement...
 * testEmptyClause: ... type was 0: JBOSGI010908: Cannot initialize resource from ...
 * testVersionAttributeWithWhiteSpace: ... type was 0: JBOSGI010908: Cannot initialize resource from ...
 * testInvalidNativeCodeSelectionFilter: ... must be rejected with type 8, but the type was 0: JBOSGI011231: Cannot
 *   create bundle revision from ...
 * </pre>
 *
 * <h3>Expected</h3>
 * Type MANIFEST_ERROR (3) for all, NATIVECODE_ERROR (8) for the invalid selection filter.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 reports MANIFEST_ERROR for all of them (also for the selection filter, where the spec asks for
 * NATIVECODE_ERROR).
 *
 * <h3>Suggested fix</h3>
 * Give the FrameworkMessages used by BundleRevisionValidatorR4 the type MANIFEST_ERROR (as was done for
 * notAllowdToExportJavaPackage by wrapping it); in BundleStateRevision wrap ResourceBuilderException with
 * MANIFEST_ERROR; map an invalid selection-filter to NATIVECODE_ERROR. Verified: rethrowing an UNSPECIFIED
 * BundleException of validateBundleRevision as MANIFEST_ERROR, wrapping ResourceBuilderException as MANIFEST_ERROR
 * and checking the selection-filters before the osgi.native requirement is built (NATIVECODE_ERROR) makes all nine
 * test methods pass, and ManifestValidationTestCase, NativeCodeTestCase, NativeNamespaceTestCase, CoreR7TestCase,
 * CoreR8TestCase, FragmentTestCase and FrameworkExtensionTestCase still pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 reports type 0 for even more manifest errors; the branch fixed part of them).
 */
public class ManifestErrorTypeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testDuplicateImport() throws Exception {
        assertRejected("me-dup-import", BundleException.MANIFEST_ERROR, "Import-Package", "me.p,me.p");
    }

    @Test
    public void testVersionAndSpecificationVersionDiffer() throws Exception {
        assertRejected("me-specv", BundleException.MANIFEST_ERROR, "Import-Package", "me.p;specification-version=1;version=2");
    }

    @Test
    public void testExportWithBundleSymbolicName() throws Exception {
        assertRejected("me-exp-bsn", BundleException.MANIFEST_ERROR, "Export-Package", "me.p;bundle-symbolic-name=x");
    }

    @Test
    public void testExtensionOfOtherHost() throws Exception {
        assertRejected("me-ext-host", BundleException.MANIFEST_ERROR, "Fragment-Host", "me.other;extension:=framework");
    }

    @Test
    public void testBundleManifestVersionZero() throws Exception {
        assertRejected("me-bmv0", BundleException.MANIFEST_ERROR, "Bundle-ManifestVersion", "0");
    }

    @Test
    public void testInvalidRequirementFilter() throws Exception {
        assertRejected("me-filter", BundleException.MANIFEST_ERROR, "Require-Capability", "me.ns;filter:=\"(a=b\"");
    }

    @Test
    public void testEmptyClause() throws Exception {
        assertRejected("me-empty-clause", BundleException.MANIFEST_ERROR, "Export-Package", "me.p,,me.q");
    }

    @Test
    public void testVersionAttributeWithWhiteSpace() throws Exception {
        // 3.3.4: attr:Version="    23      " is an error
        assertRejected("me-version-ws", BundleException.MANIFEST_ERROR, "Provide-Capability", "me.ns;v:Version=\" 1.2 \"");
    }

    @Test
    public void testInvalidNativeCodeSelectionFilter() throws Exception {
        assertRejected("me-selection-filter", BundleException.NATIVECODE_ERROR, "Bundle-NativeCode", "lib/libfoo.so;selection-filter=\"(a=b\"");
    }

    private void assertRejected(String name, int type, String header, String value) throws Exception {
        Bundle bundle;
        try {
            bundle = installBundle(getArchive(name, header, value));
        } catch (BundleException ex) {
            assertEquals(header + ": " + value + " must be rejected with type " + type + ", but the type was " + ex.getType() + ": " + ex.getMessage(),
                    type, ex.getType());
            return;
        }
        bundle.uninstall();
        fail(header + ": " + value + " must be rejected with type " + type + ", but the bundle was installed");
    }

    private JavaArchive getArchive(String name, final String header, final String value) {
        // A plain manifest, the OSGiManifestBuilder could validate it
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Bundle-ManifestVersion", "2");
        attributes.putValue("Bundle-SymbolicName", name);
        attributes.putValue(header, value);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            manifest.write(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libfoo.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes.toByteArray());
            }
        });
        return archive;
    }
}
