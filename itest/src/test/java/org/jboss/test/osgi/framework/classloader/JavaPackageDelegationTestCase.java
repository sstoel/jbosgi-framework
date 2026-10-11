package org.jboss.test.osgi.framework.classloader;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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

import static org.junit.Assert.assertSame;
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

/**
 * Test that classes in java.* packages are only loaded from the parent class loader.
 *
 * @since 11-Oct-2026
 */
public class JavaPackageDelegationTestCase extends OSGiFrameworkTest {

    @Test
    public void testJavaPackageNotLoadedFromBundle() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "java-package");
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "java/lang/SomeClass.class");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            assertSame(Math.class, bundle.loadClass("java.lang.Math"));

            // The class file in the bundle is not considered
            try {
                bundle.loadClass("java.lang.SomeClass");
                fail("ClassNotFoundException expected");
            } catch (ClassNotFoundException ex) {
                // expected
            }
        } finally {
            bundle.uninstall();
        }
    }
}
