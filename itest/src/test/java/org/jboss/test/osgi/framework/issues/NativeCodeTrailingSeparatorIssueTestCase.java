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
 * A Bundle-NativeCode header with a trailing ';' or ',' fails the install with a MANIFEST_ERROR.
 *
 * <h2>Severity</h2>
 * major. Bundles that installed and resolved before the manifest validation of the minor round now fail to install
 * (JBOSGI011298). Every other header tolerates the trailing separator.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManifestSyntaxValidator.validateNativeCode}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 11 (fw-minor-B01/C01).
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManifestSyntaxValidator.java:185-215 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The header parser of the other headers skips an empty last parameter and an empty last clause; the native code
 * validation treats them as a clause part without a path.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=NativeCodeTrailingSeparatorIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The bundles install, resolve and start.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by dc5d2881.
 */
public class NativeCodeTrailingSeparatorIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testTrailingSemicolonAfterParameters() throws Exception {
        assertStarts("native-trailing-semicolon", "lib/libfoo.so; osname=\"" + property(Constants.FRAMEWORK_OS_NAME) + "\";");
    }

    @Test
    public void testTrailingSemicolonAfterPath() throws Exception {
        assertStarts("native-trailing-semicolon-path", "lib/libfoo.so;");
    }

    @Test
    public void testTrailingComma() throws Exception {
        assertStarts("native-trailing-comma", "lib/libfoo.so; processor=\"" + property(Constants.FRAMEWORK_PROCESSOR) + "\",");
    }

    private void assertStarts(String name, String nativeCode) throws Exception {
        Bundle bundle;
        try {
            bundle = installBundle(getNativeCodeArchive(name, nativeCode));
        } catch (BundleException ex) {
            fail("A trailing separator is tolerated, but 'Bundle-NativeCode: " + nativeCode + "' did not install: " + ex);
            return;
        }
        try {
            bundle.start();
            assertEquals(Bundle.ACTIVE, bundle.getState());
        } finally {
            bundle.uninstall();
        }
    }

    private String property(String key) throws Exception {
        return getSystemContext().getProperty(key);
    }

    private JavaArchive getNativeCodeArchive(String name, final String nativeCode) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libfoo.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                return builder.openStream();
            }
        });
        return archive;
    }
}
