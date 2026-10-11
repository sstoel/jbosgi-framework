package org.jboss.test.osgi.framework.bundle;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Dictionary;
import java.util.Hashtable;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.service.resolver.Resolver;

/**
 * Test the OSGi Core R7 additions to the framework API.
 *
 * @since 11-Oct-2026
 */
public class CoreR7TestCase extends OSGiFrameworkTest {

    @Test
    public void testFrameworkVersion() throws Exception {
        BundleContext context = getSystemContext();
        assertNotNull(context.getProperty(Constants.FRAMEWORK_STORAGE));

        // The versions of the packages that changed in R7 and did not change again in R8
        BundleRevision sysrev = context.getBundle(0).adapt(BundleRevision.class);
        assertEquals("1.1.0", getPackageVersion(sysrev, "org.osgi.dto"));
        assertEquals("1.3.0", getPackageVersion(sysrev, "org.osgi.framework.wiring.dto"));
    }

    @Test
    public void testServiceReferenceProperties() throws Exception {
        BundleContext context = getSystemContext();
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("Some.Key", "value");
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new Runnable() {
            public void run() {
            }
        }, props);
        try {
            ServiceReference<Runnable> sref = sreg.getReference();
            Dictionary<String, Object> copy = sref.getProperties();
            assertEquals(sref.getPropertyKeys().length, copy.size());
            assertEquals(sref.getProperty(Constants.SERVICE_ID), copy.get(Constants.SERVICE_ID));
            assertEquals(Constants.SCOPE_SINGLETON, copy.get(Constants.SERVICE_SCOPE));

            // Keys are case insensitive and case preserving
            assertEquals("value", copy.get("some.key"));
            assertEquals("value", copy.get("SOME.KEY"));
            boolean found = false;
            for (String key : java.util.Collections.list(copy.keys()))
                found |= "Some.Key".equals(key);
            assertTrue("Case preserved", found);

            // The copy is owned by the caller
            copy.put("some.key", "changed");
            copy.put("other", "added");
            copy.remove(Constants.SERVICE_ID);
            assertEquals("changed", copy.get("Some.Key"));
            assertEquals("value", sref.getProperty("Some.Key"));
            assertEquals("value", sref.getProperties().get("Some.Key"));
            assertFalse(java.util.Arrays.asList(sref.getPropertyKeys()).contains("other"));
            assertNotNull(sref.getProperty(Constants.SERVICE_ID));

            // A later change of the service properties is not reflected in the copy
            props.put("Some.Key", "modified");
            sreg.setProperties(props);
            assertEquals("changed", copy.get("Some.Key"));
            assertEquals("modified", sref.getProperties().get("Some.Key"));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testExecutionEnvironmentCapability() throws Exception {
        // Only the system bundle provides capabilities in the osgi.ee namespace
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "provide-ee");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.PROVIDE_CAPABILITY, "osgi.ee; osgi.ee=TestEE");
                return builder.openStream();
            }
        });
        try {
            installBundle(archive).uninstall();
            fail("BundleException expected");
        } catch (BundleException ex) {
            assertEquals(BundleException.MANIFEST_ERROR, ex.getType());
        }
    }

    @Test
    public void testExecutionEnvironmentVersionRanges() throws Exception {
        // The 1.x versions of JavaSE are provided separately from the versions since Java 9
        assertTrue(resolvesWithExecutionEnvironment("ee-below-two", "(&(osgi.ee=JavaSE)(version>=1.3)(!(version>=2.0)))"));
        assertTrue(resolvesWithExecutionEnvironment("ee-seventeen", "(&(osgi.ee=JavaSE)(version=17))"));
        assertTrue(resolvesWithExecutionEnvironment("ee-eight", "(&(osgi.ee=JavaSE)(version=1.8))"));
        assertFalse(resolvesWithExecutionEnvironment("ee-only-one-three", "(&(osgi.ee=JavaSE)(version>=1.3)(!(version>=1.4)))"));
        assertFalse(resolvesWithExecutionEnvironment("ee-future", "(&(osgi.ee=JavaSE)(version>=9999))"));
    }

    @Test
    public void testExecutionEnvironmentOfRunningPlatform() throws Exception {
        // The versions are not limited to the Java versions that the framework knows
        int feature = Runtime.version().feature();
        assertTrue(resolvesWithExecutionEnvironment("ee-running", "(&(osgi.ee=JavaSE)(version=" + feature + "))"));
        assertFalse(resolvesWithExecutionEnvironment("ee-next", "(&(osgi.ee=JavaSE)(version=" + (feature + 1) + "))"));

        String environments = getSystemContext().getProperty(Constants.FRAMEWORK_EXECUTIONENVIRONMENT);
        assertTrue(environments, java.util.Arrays.asList(environments.split(",\\s*")).contains("JavaSE-" + feature));
    }

    private boolean resolvesWithExecutionEnvironment(String name, final String filter) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.REQUIRE_CAPABILITY, "osgi.ee; filter:=\"" + filter + "\"");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            return resolveBundles(java.util.Arrays.asList(bundle));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testResolverService() throws Exception {
        BundleContext context = getSystemContext();
        ServiceReference<Resolver> sref = context.getServiceReference(Resolver.class);
        assertNotNull("Resolver service registered", sref);
        assertEquals(context.getBundle(0), sref.getBundle());
        Resolver resolver = context.getService(sref);
        try {
            assertNotNull(resolver);
        } finally {
            context.ungetService(sref);
        }
    }

    private String getPackageVersion(BundleRevision brev, String packageName) {
        for (BundleCapability cap : brev.getDeclaredCapabilities(org.osgi.framework.namespace.PackageNamespace.PACKAGE_NAMESPACE)) {
            if (packageName.equals(cap.getAttributes().get(org.osgi.framework.namespace.PackageNamespace.PACKAGE_NAMESPACE)))
                return String.valueOf(cap.getAttributes().get(Constants.VERSION_ATTRIBUTE));
        }
        throw new AssertionError("Package not exported: " + packageName);
    }
}
