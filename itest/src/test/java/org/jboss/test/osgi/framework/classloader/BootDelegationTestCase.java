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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.accessibility.AccessibleTextSequence;
import javax.security.auth.x500.X500Principal;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.osgi.testing.OSGiTestHelper;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Test boot delegation
 *
 * @author thomas.diesler@jboss.com
 * @since 28-Jan-2011
 */
public class BootDelegationTestCase extends OSGiTest {

    private static ClassLoader bootClassLoader = new BootClassLoader();
    private static class BootClassLoader extends ClassLoader {
        protected BootClassLoader() {
            super(null);
        }
    }

    private Class<?> vmClass;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        vmClass = bootClassLoader.loadClass("javax.security.auth.x500.X500Principal");
    }

    @Test
    public void testNoWildcard() throws Exception {
        doTestBootDelegation("javax.security.auth.x500", true, "javax.security.auth.x500.X500Principal");
    }

    @Test
    public void testWildcardOne() throws Exception {
        doTestBootDelegation("javax.security.auth.*", true, "javax.security.auth.x500.X500Principal");
    }

    @Test
    public void testWildcardTwo() throws Exception {
        doTestBootDelegation("javax.security.*", true, "javax.security.auth.x500.X500Principal");
    }

    @Test
    public void testWildcardAll() throws Exception {
        doTestBootDelegation("*", true, "javax.security.auth.x500.X500Principal");
    }

    @Test
    public void testNullBootDelegation() throws Exception {
        doTestBootDelegation(null, false, "javax.accessibility.AccessibleTextSequence");
    }

    @Test
    public void testJunkBootDelegation() throws Exception {
        doTestBootDelegation("junk.*", false, "javax.accessibility.AccessibleTextSequence");
    }

    private void doTestBootDelegation(String bootDelegation, boolean fromBoot, final String clazz) throws Exception {
        Map<String, String> configuration = new HashMap<String, String>();
        configuration.put("org.osgi.framework.storage", "target/osgi-store");
        configuration.put("org.osgi.framework.storage.clean", "onFirstInit");
        if (bootDelegation != null)
            configuration.put(Constants.FRAMEWORK_BOOTDELEGATION, bootDelegation);

        FrameworkFactory factory = ServiceLoader.loadService(FrameworkFactory.class);
        Framework framework = factory.newFramework(configuration);
        framework.start();
        try
        {
            BundleContext sysContext = framework.getBundleContext();
            InputStream inputStream = OSGiTestHelper.toInputStream(getTestArchive());
            Bundle testBundle = sysContext.installBundle("http://bootdelegation", inputStream);
            Class<?> testClass = null;
            ClassLoader classLoader = null;
            try {
                testClass = testBundle.loadClass(clazz);
                assertNotNull("Test class not null", testClass);
                classLoader = testClass.getClassLoader();
            } catch (ClassNotFoundException e) {
                fail("Unexpected ClassNotFoundException");
            }
            if (fromBoot) {
                assertEquals("Unexpected class", vmClass.getClassLoader(), classLoader);
            }
            else {
                assertFalse("Unexpected class", vmClass.equals(testClass));
                Bundle provider = ((BundleReference) classLoader).getBundle();
                assertEquals(testBundle, provider);
            }

        }
        finally
        {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private JavaArchive getTestArchive() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "bootdelegation");
        archive.addClasses(X500Principal.class);
        archive.addClasses(AccessibleTextSequence.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive;
    }
}
