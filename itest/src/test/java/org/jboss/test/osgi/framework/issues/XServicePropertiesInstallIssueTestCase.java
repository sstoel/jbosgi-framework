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

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

/**
 * Installing a jar described by META-INF/jbosgi-xservice.properties: invalid values make installBundle throw a raw
 * IllegalArgumentException/NumberFormatException instead of a BundleException, and the properties are never used
 * when the jar has a MANIFEST.MF without OSGi headers (the jar is installed as an anonymous R3 bundle instead).
 *
 * <h3>Severity</h3>
 * minor. BundleContext.installBundle callers (management tools, provisioning, tests) get an undeclared runtime
 * exception for a broken jbosgi-xservice.properties. The fallback to the properties, documented in
 * DeploymentProviderImpl ("No valid OSGi manifest. Fallback to jbosgi-xservice.properties"), only works for jars
 * without any MANIFEST.MF; every jar built by the jar tool or Maven has one, so the properties are silently ignored
 * and the jar becomes a bundle without symbolic name, exports or imports. (The WildFly subsystem has its own
 * xservice path, OSGiXServiceParseProcessor, which registers such jars as modules and is not affected.)
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.DeploymentProviderImpl.createDeployment(String,
 * VirtualFile)}; jbosgi-metadata {@code OSGiMetaDataBuilder.validateMetadata} (a manifest that has only
 * Manifest-Version passes as an R3 bundle manifest because getBundleVersion() defaults to 0.0.0, whereas
 * OSGiManifestBuilder.getBundleManifestVersion(Manifest) returns -1 "non OSGi manifest" for it).
 *
 * <h3>Found by</h3>
 * manifest workstream, round 3 (WildFly integration metadata paths): the manifest catalogue run through the
 * properties path ({@code explore/manifest/props_variant.py}, report {@code out-props/report-jbosgi.txt}: 564 of
 * 601 cases behave as with a manifest, the differences are these exceptions and the documented default
 * Bundle-ManifestVersion 2 of the properties).
 *
 * <h3>Spec reference</h3>
 * BundleContext.installBundle javadoc (osgi.core 8.0.0): "Throws: BundleException - If the installation failed.
 * BundleException types thrown by this method include: ... MANIFEST_ERROR ...; SecurityException ...;
 * IllegalStateException - If this BundleContext is no longer valid." No other exception is declared.<br>
 * OSGi Core R8, 3.12 Bundle Validity (framework.module.html): an improperly formatted version causes the install to
 * fail (with a BundleException, as installBundle declares). The jbosgi-xservice.properties fallback itself is
 * jbosgi behaviour (DeploymentProviderImpl.java:82).
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/DeploymentProviderImpl.java:66-111}: the metadata of
 * the properties is used without validation ({@code metadata.getBundleVersion()} at :90 throws), and the fallback at
 * :82-93 is only reached when BundleInfo.createBundleInfo fails, which it does not for a Manifest-Version-only
 * manifest; jbosgi-metadata {@code OSGiMetaDataBuilder.java:159-177} (getBundleManifestVersion(OSGiMetaData)).
 * Unchanged since 7dfb6fdc (2012).
 *
 * <h3>Root cause</h3>
 * No validation and no exception translation for the properties path; the "valid OSGi manifest" test of the
 * metadata builder treats a manifest without OSGi headers as an R3 bundle manifest.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=XServicePropertiesInstallIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testInvalidBundleVersionInProperties: installBundle must throw a BundleException (MANIFEST_ERROR) for the
 *   invalid jbosgi-xservice.properties (Bundle-SymbolicName=xservice.version Bundle-Version=1.a), but it threw
 *   java.lang.IllegalArgumentException: invalid version "1.a": non-numeric "a"
 * testInvalidManifestVersionInProperties: ... but it threw java.lang.NumberFormatException: For input string: "abc"
 * testDuplicateDirectiveInProperties: ... but it threw java.lang.IllegalArgumentException: Duplicate directive: singleton
 * testPropertiesUsedWhenManifestHasNoBundleHeaders: A jar whose manifest has no OSGi header falls back to
 *   META-INF/jbosgi-xservice.properties, so its symbolic name is xservice.manifest
 *   expected:&lt;xservice.manifest&gt; but was:&lt;null&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * BundleException of type MANIFEST_ERROR for the three invalid property files; the fourth bundle is installed from
 * its properties (symbolic name xservice.manifest).
 *
 * <h3>Reference behaviour</h3>
 * Not applicable (jbosgi-specific metadata source). Equinox/Felix reject an invalid Bundle-Version in a manifest with
 * a BundleException.
 *
 * <h3>Suggested fix</h3>
 * In createDeployment try jbosgi-xservice.properties first when the jar has no manifest or a manifest without OSGi
 * headers ({@code OSGiManifestBuilder.getBundleManifestVersion(manifest) < 0}), and build the deployment from the
 * properties inside a try/catch that runs OSGiMetaDataBuilder.validateMetadata and turns a RuntimeException into a
 * BundleException(MANIFEST_ERROR). Verified: with that change the four test methods pass and the whole framework
 * itest suite shows no new failures (JDK 25; only other issue test classes fail).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class XServicePropertiesInstallIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testInvalidBundleVersionInProperties() throws Exception {
        assertManifestError("xservice-version", "Bundle-SymbolicName=xservice.version\nBundle-Version=1.a\n");
    }

    @Test
    public void testInvalidManifestVersionInProperties() throws Exception {
        assertManifestError("xservice-bmv", "Bundle-ManifestVersion=abc\nBundle-SymbolicName=xservice.bmv\n");
    }

    @Test
    public void testDuplicateDirectiveInProperties() throws Exception {
        assertManifestError("xservice-dup", "Bundle-SymbolicName=xservice.dup;singleton:=true;singleton:=true\n");
    }

    @Test
    public void testPropertiesUsedWhenManifestHasNoBundleHeaders() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "xservice-manifest");
        archive.add(new StringAsset("Manifest-Version: 1.0\n\n"), "META-INF/MANIFEST.MF");
        archive.add(new StringAsset("Bundle-SymbolicName=xservice.manifest\nBundle-Version=1.2.3\n"), "META-INF/jbosgi-xservice.properties");
        Bundle bundle = installBundle(archive);
        try {
            assertEquals("A jar whose manifest has no OSGi header falls back to META-INF/jbosgi-xservice.properties, so its symbolic name is xservice.manifest",
                    "xservice.manifest", bundle.getSymbolicName());
        } finally {
            bundle.uninstall();
        }
    }

    private void assertManifestError(String name, String properties) throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new StringAsset(properties), "META-INF/jbosgi-xservice.properties");
        Bundle bundle;
        try {
            bundle = installBundle(archive);
        } catch (BundleException ex) {
            assertEquals("The invalid jbosgi-xservice.properties (" + properties.replace('\n', ' ').trim() + ") must be rejected with a MANIFEST_ERROR, but the type was "
                    + ex.getType(), BundleException.MANIFEST_ERROR, ex.getType());
            return;
        } catch (RuntimeException ex) {
            fail("installBundle must throw a BundleException (MANIFEST_ERROR) for the invalid jbosgi-xservice.properties (" + properties.replace('\n', ' ').trim()
                    + "), but it threw " + ex);
            return;
        }
        bundle.uninstall();
        fail("The invalid jbosgi-xservice.properties (" + properties.replace('\n', ' ').trim() + ") must be rejected, but the bundle was installed");
    }
}
