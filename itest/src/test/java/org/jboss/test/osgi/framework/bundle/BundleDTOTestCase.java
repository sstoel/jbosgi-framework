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
import java.util.Hashtable;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.framework.dto.FrameworkDTO;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.startlevel.dto.BundleStartLevelDTO;
import org.osgi.framework.startlevel.dto.FrameworkStartLevelDTO;
import org.osgi.framework.wiring.dto.BundleRevisionDTO;
import org.osgi.framework.wiring.dto.BundleWireDTO;
import org.osgi.framework.wiring.dto.BundleWiringDTO;
import org.osgi.framework.wiring.dto.FrameworkWiringDTO;
import org.osgi.resource.dto.CapabilityDTO;
import org.osgi.resource.dto.RequirementDTO;
import org.osgi.resource.dto.WireDTO;

/**
 * Test the DTOs that a bundle can be adapted to.
 *
 * @since 11-Oct-2026
 */
public class BundleDTOTestCase extends OSGiFrameworkTest {

    @Test
    public void testBundleDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-bundle"));
        try {
            BundleDTO dto = bundle.adapt(BundleDTO.class);
            assertNotNull(dto);
            assertEquals(bundle.getBundleId(), dto.id);
            assertEquals(bundle.getSymbolicName(), dto.symbolicName);
            assertEquals(bundle.getVersion(), Version.parseVersion(dto.version));
            assertEquals(bundle.getLastModified(), dto.lastModified);
            assertEquals(Bundle.INSTALLED, dto.state);

            // A DTO is a snapshot
            bundle.start();
            assertEquals(Bundle.INSTALLED, dto.state);
            assertEquals(Bundle.ACTIVE, bundle.adapt(BundleDTO.class).state);
        } finally {
            bundle.uninstall();
        }
        assertEquals(Bundle.UNINSTALLED, bundle.adapt(BundleDTO.class).state);
    }

    @Test
    public void testBundleStartLevelDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-startlevel"));
        try {
            bundle.start();
            BundleStartLevel startLevel = bundle.adapt(BundleStartLevel.class);
            BundleStartLevelDTO dto = bundle.adapt(BundleStartLevelDTO.class);
            assertNotNull(dto);
            assertEquals(bundle.getBundleId(), dto.bundle);
            assertEquals(startLevel.getStartLevel(), dto.startLevel);
            assertEquals(startLevel.isPersistentlyStarted(), dto.persistentlyStarted);
            assertEquals(startLevel.isActivationPolicyUsed(), dto.activationPolicyUsed);
        } finally {
            bundle.uninstall();
        }
        assertNull(bundle.adapt(BundleStartLevelDTO.class));
    }

    @Test
    public void testBundleRevisionDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-revision"));
        try {
            BundleRevisionDTO dto = bundle.adapt(BundleRevisionDTO.class);
            assertNotNull(dto);
            assertEquals(bundle.getBundleId(), dto.bundle);
            assertEquals(bundle.getSymbolicName(), dto.symbolicName);
            assertEquals(bundle.getVersion(), Version.parseVersion(dto.version));

            // The declared capabilities and requirements refer to the revision
            assertFalse(dto.capabilities.isEmpty());
            for (CapabilityDTO cap : dto.capabilities)
                assertEquals(dto.id, cap.resource);

            boolean foundImport = false;
            for (RequirementDTO req : dto.requirements) {
                assertEquals(dto.id, req.resource);
                foundImport |= PackageNamespace.PACKAGE_NAMESPACE.equals(req.namespace);
            }
            assertTrue("Package requirement found", foundImport);

            BundleRevisionDTO[] dtos = bundle.adapt(BundleRevisionDTO[].class);
            assertNotNull(dtos);
            assertEquals(1, dtos.length);
        } finally {
            bundle.uninstall();
        }
        assertNull(bundle.adapt(BundleRevisionDTO.class));
        assertNull(bundle.adapt(BundleRevisionDTO[].class));
    }

    @Test
    public void testBundleWiringDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-wiring"));
        try {
            assertNull("No wiring for an unresolved bundle", bundle.adapt(BundleWiringDTO.class));

            bundle.start();
            BundleWiringDTO dto = bundle.adapt(BundleWiringDTO.class);
            assertNotNull(dto);
            assertEquals(bundle.getBundleId(), dto.bundle);

            BundleWiringDTO.NodeDTO root = null;
            for (BundleWiringDTO.NodeDTO node : dto.nodes) {
                if (node.id == dto.root)
                    root = node;
            }
            assertNotNull("Root node found", root);
            assertTrue(root.current);
            assertTrue(root.inUse);
            assertEquals(bundle.getBundleId(), getRevision(dto, root.resource).bundle);

            // The bundle imports a package from the system bundle
            boolean foundSystemWire = false;
            for (WireDTO wire : root.requiredWires) {
                assertEquals(root.resource, wire.requirer);
                assertEquals(root.id, ((BundleWireDTO) wire).requirerWiring);
                foundSystemWire |= getRevision(dto, wire.provider).bundle == 0;
            }
            assertTrue("Wire to the system bundle found", foundSystemWire);

            BundleWiringDTO[] dtos = bundle.adapt(BundleWiringDTO[].class);
            assertNotNull(dtos);
            assertEquals(1, dtos.length);
        } finally {
            bundle.uninstall();
        }
        assertNull(bundle.adapt(BundleWiringDTO.class));
        assertNull(bundle.adapt(BundleWiringDTO[].class));
    }

    @Test
    public void testServiceReferenceDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-service"));
        try {
            bundle.start();
            Hashtable<String, Object> props = new Hashtable<String, Object>();
            props.put("string", "value");
            props.put("number", Integer.valueOf(42));
            props.put("version", Version.parseVersion("1.2.3"));
            props.put("strings", new String[] { "a", "b" });
            ServiceRegistration<?> sreg = bundle.getBundleContext().registerService(Runnable.class.getName(), new Runnable() {
                public void run() {
                }
            }, props);
            getSystemContext().getService(sreg.getReference());

            ServiceReferenceDTO[] dtos = bundle.adapt(ServiceReferenceDTO[].class);
            assertNotNull(dtos);
            assertEquals(1, dtos.length);
            ServiceReferenceDTO dto = dtos[0];
            assertEquals(bundle.getBundleId(), dto.bundle);
            assertEquals(sreg.getReference().getProperty(Constants.SERVICE_ID), dto.id);
            assertEquals("value", dto.properties.get("string"));
            assertEquals(Integer.valueOf(42), dto.properties.get("number"));
            assertEquals("Version is converted to a string", "1.2.3", dto.properties.get("version"));
            assertEquals(2, ((String[]) dto.properties.get("strings")).length);
            assertEquals(1, dto.usingBundles.length);
            assertEquals(0, dto.usingBundles[0]);

            // A stopped bundle has no bundle context and therefore no registered services
            bundle.stop();
            assertNull(bundle.adapt(ServiceReferenceDTO[].class));
        } finally {
            bundle.uninstall();
        }
        assertNull(bundle.adapt(ServiceReferenceDTO[].class));
    }

    @Test
    public void testFrameworkDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-framework"));
        try {
            assertNull("Only the system bundle has a FrameworkDTO", bundle.adapt(FrameworkDTO.class));
            assertNull("Only the system bundle has a FrameworkStartLevelDTO", bundle.adapt(FrameworkStartLevelDTO.class));

            Bundle systemBundle = getSystemContext().getBundle(0);
            FrameworkDTO dto = systemBundle.adapt(FrameworkDTO.class);
            assertNotNull(dto);

            boolean foundSystem = false, foundBundle = false;
            for (BundleDTO bdto : dto.bundles) {
                foundSystem |= bdto.id == 0;
                foundBundle |= bdto.id == bundle.getBundleId();
            }
            assertTrue("System bundle found", foundSystem);
            assertTrue("Test bundle found", foundBundle);

            assertEquals(getSystemContext().getProperty(Constants.FRAMEWORK_VERSION), dto.properties.get(Constants.FRAMEWORK_VERSION));
            assertNotNull(dto.services);

            FrameworkStartLevel startLevel = systemBundle.adapt(FrameworkStartLevel.class);
            FrameworkStartLevelDTO sldto = systemBundle.adapt(FrameworkStartLevelDTO.class);
            assertNotNull(sldto);
            assertEquals(startLevel.getStartLevel(), sldto.startLevel);
            assertEquals(startLevel.getInitialBundleStartLevel(), sldto.initialBundleStartLevel);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testFrameworkWiringDTO() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-framework-wiring"));
        try {
            bundle.start();

            Bundle systemBundle = getSystemContext().getBundle(0);
            FrameworkWiringDTO dto = systemBundle.adapt(FrameworkWiringDTO.class);
            assertNotNull(dto);
            assertNull("Only for the system bundle", bundle.adapt(FrameworkWiringDTO.class));

            // The ids of the revisions are the same in all DTOs
            int sysrevId = systemBundle.adapt(BundleRevisionDTO.class).id;
            int brevId = bundle.adapt(BundleRevisionDTO.class).id;
            assertFalse(sysrevId == brevId);
            assertEquals(brevId, bundle.adapt(BundleRevisionDTO.class).id);
            assertEquals(brevId, bundle.adapt(BundleRevisionDTO[].class)[0].id);

            BundleWiringDTO.NodeDTO sysnode = null, bundlenode = null;
            for (BundleWiringDTO.NodeDTO node : dto.wirings) {
                if (node.resource == sysrevId)
                    sysnode = node;
                if (node.resource == brevId)
                    bundlenode = node;
            }
            assertNotNull("System wiring found", sysnode);
            assertNotNull("Bundle wiring found", bundlenode);
            assertTrue(bundlenode.current);
            assertTrue(bundlenode.inUse);

            // The bundle is wired to the system bundle
            boolean wired = false;
            for (WireDTO wire : bundlenode.requiredWires) {
                BundleWireDTO bwire = (BundleWireDTO) wire;
                wired |= bwire.providerWiring == sysnode.id && bwire.provider == sysrevId;
            }
            assertTrue("Wired to the system bundle", wired);

            boolean foundSystem = false, foundBundle = false;
            for (BundleRevisionDTO rev : dto.resources) {
                foundSystem |= rev.id == sysrevId && rev.bundle == 0;
                foundBundle |= rev.id == brevId && rev.bundle == bundle.getBundleId();
            }
            assertTrue("System revision found", foundSystem);
            assertTrue("Bundle revision found", foundBundle);

            // The wiring of an uninstalled bundle is gone after it is no longer in use
            bundle.uninstall();
            dto = systemBundle.adapt(FrameworkWiringDTO.class);
            for (BundleWiringDTO.NodeDTO node : dto.wirings)
                assertFalse("Wiring of the uninstalled bundle", node.resource == brevId);
        } finally {
            if (bundle.getState() != Bundle.UNINSTALLED)
                bundle.uninstall();
        }
    }

    @Test
    public void testServiceReferenceDTOWithoutBundleContext() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("dto-no-context"));
        try {
            // A bundle that is not started does not have registered services
            assertNull(bundle.adapt(ServiceReferenceDTO[].class));

            bundle.start();
            ServiceReferenceDTO[] dtos = bundle.adapt(ServiceReferenceDTO[].class);
            assertNotNull(dtos);
            assertEquals(0, dtos.length);

            bundle.stop();
            assertNull(bundle.adapt(ServiceReferenceDTO[].class));
        } finally {
            bundle.uninstall();
        }
        assertNull(bundle.adapt(ServiceReferenceDTO[].class));
    }

    @Test
    public void testWiringDTOAfterUpdate() throws Exception {
        JavaArchive archive = getBundleArchive("dto-update");
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            bundle.update(toInputStream(archive));

            // The wiring of the old revision is no longer in use
            assertEquals(2, bundle.adapt(BundleRevisionDTO[].class).length);
            BundleWiringDTO[] dtos = bundle.adapt(BundleWiringDTO[].class);
            assertNotNull(dtos);
            assertEquals(1, dtos.length);
            assertNotNull(bundle.adapt(BundleWiringDTO.class));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testDTOPackagesAreSystemPackages() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "dto-imports");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages(BundleDTO.class, BundleStartLevelDTO.class, BundleWiringDTO.class, CapabilityDTO.class, org.osgi.dto.DTO.class);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            assertEquals(Bundle.ACTIVE, bundle.getState());
            assertSame(BundleWiringDTO.class, bundle.loadClass(BundleWiringDTO.class.getName()));
        } finally {
            bundle.uninstall();
        }
    }

    private BundleRevisionDTO getRevision(BundleWiringDTO dto, int id) {
        for (BundleRevisionDTO rev : dto.resources) {
            if (rev.id == id)
                return rev;
        }
        throw new AssertionError("Revision not found: " + id);
    }

    private JavaArchive getBundleArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages(BundleActivator.class);
                return builder.openStream();
            }
        });
        return archive;
    }
}
