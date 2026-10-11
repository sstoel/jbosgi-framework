package org.jboss.test.osgi.framework.launch;
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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.net.ssl.SSLContext;

import org.jboss.osgi.framework.Constants;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.launch.Framework;

/**
 * Test which packages of the Java platform a bundle sees without importing them.
 *
 * @since 11-Oct-2026
 */
public class StrictBootDelegationTestCase extends AbstractFrameworkLaunchTest {

    private static final String JAVAX_TYPE = SSLContext.class.getName();

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && framework.getState() == Bundle.ACTIVE) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testDefault() throws Exception {
        // The packages of the java.base module are visible to every bundle
        startFramework(null, null);
        assertSame(SSLContext.class, install("no-import", null).loadClass(JAVAX_TYPE));
    }

    @Test
    public void testStrictBootDelegation() throws Exception {
        startFramework("true", null);

        // Only java.* packages are visible without an import
        Bundle bundle = install("no-import", null);
        assertSame(String.class, bundle.loadClass("java.lang.String"));
        try {
            bundle.loadClass(JAVAX_TYPE);
            fail("ClassNotFoundException expected");
        } catch (ClassNotFoundException ex) {
            // expected
        }

        // The package is exported by the system bundle
        assertSame(SSLContext.class, install("with-import", "javax.net.ssl").loadClass(JAVAX_TYPE));
    }

    @Test
    public void testStrictBootDelegationWithConfiguredPackage() throws Exception {
        startFramework("true", "javax.net.*");
        assertSame(SSLContext.class, install("no-import", null).loadClass(JAVAX_TYPE));
        assertNull(framework.getBundleContext().getProperty("does.not.exist"));
    }

    private void startFramework(String strict, String bootDelegation) throws Exception {
        Map<String, String> initprops = getFrameworkInitProperties(true);
        if (strict != null)
            initprops.put(Constants.PROPERTY_STRICT_BOOT_DELEGATION, strict);
        if (bootDelegation != null)
            initprops.put(org.osgi.framework.Constants.FRAMEWORK_BOOTDELEGATION, bootDelegation);
        framework = newFramework(initprops);
        framework.start();
    }

    private Bundle install(String name, String importPackage) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(org.osgi.framework.Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(org.osgi.framework.Constants.BUNDLE_SYMBOLICNAME, name.replace('-', '.'));
        if (importPackage != null)
            attributes.putValue(org.osgi.framework.Constants.IMPORT_PACKAGE, importPackage);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(baos.toByteArray()));
    }
}
