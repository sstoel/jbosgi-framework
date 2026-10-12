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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.condition.Condition;

/**
 * Test the OSGi Core R8 additions to the framework API.
 *
 * @since 11-Oct-2026
 */
public class CoreR8TestCase extends OSGiFrameworkTest {

    @Test
    public void testDefaultSystemPackages() throws Exception {
        // The system bundle only exports what the running Java platform and the framework provide
        BundleRevision sysrev = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        Set<String> packages = new HashSet<String>();
        for (BundleCapability cap : sysrev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE))
            packages.add((String) cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));

        for (String name : new String[] { "java.lang", "javax.transaction.xa", "javax.xml.parsers", "org.w3c.dom", "org.osgi.framework", "org.osgi.util.xml" })
            assertTrue("Exported: " + name, packages.contains(name));
        for (String name : new String[] { "jakarta.xml.bind", "jakarta.xml.ws", "javax.transaction", "javax.rmi", "org.jcp.xml.dsig.internal", "org.osgi.util.promise" })
            assertFalse("Not exported: " + name, packages.contains(name));

        assertEquals("1.1.1", getPackageVersion(sysrev, "org.osgi.dto"));
    }

    @Test
    public void testFrameworkVersion() throws Exception {
        BundleContext context = getSystemContext();
        assertEquals("1.10", context.getProperty(Constants.FRAMEWORK_VERSION));

        // The versions of the packages that are new or changed in R8
        BundleRevision sysrev = context.getBundle(0).adapt(BundleRevision.class);
        assertEquals("1.10.0", getPackageVersion(sysrev, "org.osgi.framework"));
        assertEquals("1.0.0", getPackageVersion(sysrev, "org.osgi.framework.connect"));
        assertEquals("1.2.0", getPackageVersion(sysrev, "org.osgi.framework.namespace"));
        assertEquals("1.0.1", getPackageVersion(sysrev, "org.osgi.resource"));
        assertEquals("1.0.0", getPackageVersion(sysrev, "org.osgi.service.condition"));
        assertEquals("1.5.0", getPackageVersion(sysrev, "org.osgi.service.log"));
        assertEquals("1.0.0", getPackageVersion(sysrev, "org.osgi.service.log.admin"));
        assertEquals("1.1.1", getPackageVersion(sysrev, "org.osgi.service.resolver"));
        assertEquals("1.5.3", getPackageVersion(sysrev, "org.osgi.util.tracker"));
    }

    @Test
    public void testSystemPackageUses() throws Exception {
        // The packages of the OSGi API declare the packages that they use, like the API bundle does
        BundleRevision sysrev = getSystemContext().getBundle(0).adapt(BundleRevision.class);
        assertEquals("org.osgi.framework,org.osgi.resource", getPackageUses(sysrev, "org.osgi.framework.wiring"));
        assertEquals("org.osgi.framework", getPackageUses(sysrev, "org.osgi.util.tracker"));
        assertEquals("org.osgi.service.log", getPackageUses(sysrev, "org.osgi.service.log.admin"));
        assertNull(getPackageUses(sysrev, "org.osgi.framework"));
    }

    @Test
    public void testServiceReferenceAdapt() throws Exception {
        BundleContext context = getSystemContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new Runnable() {
            public void run() {
            }
        }, null);
        ServiceReference<Runnable> sref = sreg.getReference();
        try {
            assertSame(context.getBundle(), sref.getBundle());

            ServiceReferenceDTO dto = sref.adapt(ServiceReferenceDTO.class);
            assertNotNull("DTO for a registered service", dto);
            assertEquals(sref.getProperty(Constants.SERVICE_ID), dto.id);
            assertEquals(0, dto.bundle);
            assertEquals(sref.getPropertyKeys().length, dto.properties.size());
            assertEquals(0, dto.usingBundles.length);

            assertNull("Cannot adapt to an unknown type", sref.adapt(String.class));
        } finally {
            sreg.unregister();
        }
        assertNull("No DTO for an unregistered service", sref.adapt(ServiceReferenceDTO.class));
    }

    @Test
    public void testTrueCondition() throws Exception {
        BundleContext context = getSystemContext();
        String filter = "(" + Condition.CONDITION_ID + "=" + Condition.CONDITION_ID_TRUE + ")";
        Collection<ServiceReference<Condition>> srefs = context.getServiceReferences(Condition.class, filter);
        assertEquals("One true condition", 1, srefs.size());

        ServiceReference<Condition> sref = srefs.iterator().next();
        assertSame("Registered by the system bundle", context.getBundle(0), sref.getBundle());
        assertSame(Condition.INSTANCE, context.getService(sref));
        context.ungetService(sref);
    }

    @Test
    public void testBundleWithOwnLogServiceAPI() throws Exception {
        // A bundle that carries the Log Service 1.4 API, like the Log Service implementations for R7 do.
        // It cannot use the org.osgi.service.log package of the system bundle, so it must not get
        // the org.osgi.service.log.admin package from the system bundle either
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "own-log-api");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addExportPackages("org.osgi.service.log;version=\"1.4\"");
                builder.addExportPackages("org.osgi.service.log.admin;version=\"1.0\";uses:=\"org.osgi.service.log\"");
                builder.addImportPackages("org.osgi.service.log;version=\"[1.4,1.5)\"");
                builder.addImportPackages("org.osgi.service.log.admin;version=\"[1.0,1.1)\"");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            assertTrue("Bundle resolved", resolveBundles(Arrays.asList(bundle)));
            for (BundleWire wire : bundle.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
                Object packageName = wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE);
                assertFalse("Not wired to the system bundle: " + packageName, wire.getProvider().getBundle().getBundleId() == 0);
            }
        } finally {
            bundle.uninstall();
        }
    }

    private String getPackageUses(BundleRevision brev, String packageName) {
        for (BundleCapability cap : brev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (packageName.equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                return cap.getDirectives().get(PackageNamespace.CAPABILITY_USES_DIRECTIVE);
        }
        throw new AssertionError("Package not exported: " + packageName);
    }

    private String getPackageVersion(BundleRevision brev, String packageName) {
        for (BundleCapability cap : brev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (packageName.equals(cap.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                return String.valueOf(cap.getAttributes().get(Constants.VERSION_ATTRIBUTE));
        }
        throw new AssertionError("Package not exported: " + packageName);
    }
}
