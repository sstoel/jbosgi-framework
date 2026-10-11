package org.jboss.test.osgi.framework.xservice;
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

import org.jboss.modules.Module;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.xservice.moduleX.ModuleServiceX;
import org.junit.Assert;
import org.junit.Test;
import org.osgi.framework.dto.BundleDTO;
import org.osgi.framework.dto.FrameworkDTO;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.framework.startlevel.dto.BundleStartLevelDTO;
import org.osgi.framework.wiring.dto.BundleRevisionDTO;

/**
 * Test the DTOs of a bundle that adapts a module.
 *
 * @since 11-Oct-2026
 */
public class ModuleBundleDTOTestCase extends AbstractModuleIntegrationTest {

    @Test
    public void testModuleBundleDTOs() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleDTO");
        archive.addClasses(ModuleServiceX.class);
        Module module = loadModule(archive);
        XBundleRevision brev = installResource(module);
        try {
            XBundle bundle = brev.getBundle();

            BundleDTO dto = bundle.adapt(BundleDTO.class);
            Assert.assertNotNull(dto);
            Assert.assertEquals(bundle.getBundleId(), dto.id);
            Assert.assertEquals(bundle.getSymbolicName(), dto.symbolicName);
            Assert.assertEquals(bundle.getState(), dto.state);

            BundleRevisionDTO revdto = bundle.adapt(BundleRevisionDTO.class);
            Assert.assertNotNull(revdto);
            Assert.assertEquals(bundle.getBundleId(), revdto.bundle);
            Assert.assertNotNull(bundle.adapt(BundleStartLevelDTO.class));
            Assert.assertEquals(0, bundle.adapt(ServiceReferenceDTO[].class).length);
            Assert.assertNull("Only the system bundle has a FrameworkDTO", bundle.adapt(FrameworkDTO.class));

            // The framework DTO includes the module bundle
            FrameworkDTO fwdto = getSystemContext().getBundle(0).adapt(FrameworkDTO.class);
            Assert.assertNotNull(fwdto);
            boolean found = false;
            for (BundleDTO aux : fwdto.bundles)
                found |= aux.id == bundle.getBundleId();
            Assert.assertTrue("Module bundle found", found);
        } finally {
            removeModule(module);
        }
    }
}
