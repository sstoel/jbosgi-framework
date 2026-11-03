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
package org.jboss.test.osgi.framework.bundle;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.b.ActivatorB;
import org.jboss.test.osgi.framework.bundle.support.b.ServiceB;
import org.jboss.test.osgi.framework.bundle.support.x.ServiceX;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Bundle lifecycle TestCase.
 *
 * Bundle B depends on X
 *
 * @author thomas.diesler@jboss.com
 * @since 21-Sep-2010
 */
public class BundleReInstallTestCase extends OSGiFrameworkTest {

    @Test
    public void testSimpleReInstall() throws Exception {
        JavaArchive archiveX = assembleArchive("lifecycle-bundleX", "/bundles/lifecycle/bundleX", ServiceX.class);
        Bundle bundleX = installBundle(archiveX);
        try {
            assertBundleState(Bundle.INSTALLED, bundleX.getState());

            bundleX.start();
            assertBundleState(Bundle.ACTIVE, bundleX.getState());

            bundleX.uninstall();
            assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

            bundleX = installBundle(archiveX);
            assertBundleState(Bundle.INSTALLED, bundleX.getState());
        } finally {
            bundleX.uninstall();
            assertBundleState(Bundle.UNINSTALLED, bundleX.getState());
        }
    }

    @Test
    public void testUninstallWithWiredCapability() throws Exception {
        Bundle bundleB = installBundle(assembleArchive("lifecycle-bundleB", "/bundles/lifecycle/bundleB", ActivatorB.class, ServiceB.class));
        assertBundleState(Bundle.INSTALLED, bundleB.getState());

        Bundle bundleX = installBundle(assembleArchive("lifecycle-bundleX", "/bundles/lifecycle/bundleX", ServiceX.class));
        assertBundleState(Bundle.INSTALLED, bundleX.getState());

        bundleB.start();
        assertBundleState(Bundle.RESOLVED, bundleX.getState());
        assertBundleState(Bundle.ACTIVE, bundleB.getState());

        long bid = bundleB.getBundleId();
        long xid = bundleX.getBundleId();

        bundleX.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        // The UNINSTALLED bundle is retained for as long as it has wires, but it is not installed any more:
        // BundleContext.getBundle(long) does not return it (TCK-3 of the R8 release check, as Equinox and Felix)
        assertNull("BundleX not installed", getSystemContext().getBundle(xid));
        BundleWire wire = bundleB.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).get(0);
        assertSame("BundleX is still in use", bundleX, wire.getProvider().getBundle());
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        bundleB.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        // Uninstalling BundleB will also remove BundleX
        bundleX = getSystemContext().getBundle(xid);
        assertNull("BundleX not available", bundleX);

        // BundleB is removed because it has only wires to UNINSTALLED bundles
        bundleB = getSystemContext().getBundle(bid);
        assertNull("BundleB not available", bundleB);
    }

    @Test
    public void testUnInstallWithWiredRequirement() throws Exception {
        Bundle bundleB = installBundle(assembleArchive("lifecycle-bundleB", "/bundles/lifecycle/bundleB", ActivatorB.class, ServiceB.class));
        assertBundleState(Bundle.INSTALLED, bundleB.getState());

        Bundle bundleX = installBundle(assembleArchive("lifecycle-bundleX", "/bundles/lifecycle/bundleX", ServiceX.class));
        assertBundleState(Bundle.INSTALLED, bundleX.getState());

        bundleB.start();
        assertBundleState(Bundle.RESOLVED, bundleX.getState());
        assertBundleState(Bundle.ACTIVE, bundleB.getState());

        long bid = bundleB.getBundleId();
        long xid = bundleX.getBundleId();

        bundleB.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleB.getState());

        // BundleB is removed because it does not have wired capabilities
        bundleB = getSystemContext().getBundle(bid);
        assertNull("BundleB not available", bundleB);

        bundleX.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        bundleX = getSystemContext().getBundle(xid);
        assertNull("BundleX not available", bundleX);
    }

    @Test
    public void testReInstallWithWiredCapability() throws Exception {
        Bundle bundleB = installBundle(assembleArchive("lifecycle-bundleB", "/bundles/lifecycle/bundleB", ActivatorB.class, ServiceB.class));
        assertBundleState(Bundle.INSTALLED, bundleB.getState());

        Bundle bundleX = installBundle(assembleArchive("lifecycle-bundleX", "/bundles/lifecycle/bundleX", ServiceX.class));
        assertBundleState(Bundle.INSTALLED, bundleX.getState());

        bundleB.start();
        assertBundleState(Bundle.RESOLVED, bundleX.getState());
        assertBundleState(Bundle.ACTIVE, bundleB.getState());

        Class<?> classXone = bundleB.loadClass(ServiceX.class.getName());
        assertNotNull("BundleB loads ServiceX", classXone);

        long xid = bundleX.getBundleId();

        bundleX.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        // The UNINSTALLED bundle is in use, but not installed any more (TCK-3 of the R8 release check)
        assertNull("BundleX not installed", getSystemContext().getBundle(xid));
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        // Re-Install BundleX although it is still available
        bundleX = installBundle(assembleArchive("lifecycle-bundleX", "/bundles/lifecycle/bundleX", ServiceX.class));
        assertBundleState(Bundle.INSTALLED, bundleX.getState());

        Class<?> classXtwo = bundleB.loadClass(ServiceX.class.getName());
        assertNotNull("BundleB loads ServiceX", classXtwo);
        assertSame("Reload ServiceX from same ClassLoader", classXone, classXtwo);

        bundleX.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());

        bundleB.uninstall();
        assertBundleState(Bundle.UNINSTALLED, bundleX.getState());
    }
}
