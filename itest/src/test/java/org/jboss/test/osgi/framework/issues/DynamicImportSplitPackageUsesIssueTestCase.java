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

import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.bundle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.jboss.test.osgi.framework.issues.hooks.p.HookP;
import org.jboss.test.osgi.framework.issues.hooks.s.HookS;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;

/**
 * A dynamic import of a package whose uses directive names a split package fails with ClassNotFoundException: the uses
 * check of the dynamic import takes the several providers of the split package, from two required bundles or from a
 * facade that exports it and requires its provider, for a conflict, also when the importer does not see the package.
 *
 * <h2>Severity</h2>
 * minor; major for bundles that use Require-Bundle. A valid dynamic import does not wire.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FallbackLoader.isUsesConsistent}, {@code collectImpliedPackages}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-07 (side effect of the fix of resolver/04).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.7.6 uses constraints; 3.13.3 split packages through Require-Bundle. A conflict needs a package that the
 * importer sees from another provider.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:530-536 (84ac5c2e): one provider per used
 * package, a second provider of the same package is a conflict.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=DynamicImportSplitPackageUsesIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The host loads the class through its dynamic import in all three cases; the plain uses with an import is the control.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 3093973d (resolver/04). Felix and Equinox load the class.
 */
public class DynamicImportSplitPackageUsesIssueTestCase extends OSGiTest {

    private static final String P = HookP.class.getPackage().getName();
    private static final String S = HookS.class.getPackage().getName();
    private Framework framework;
    private BundleContext context;

    @Before
    public void setUp() throws Exception {
        File storage = ReleaseCheckSupport.storage("dynamic-import-split");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true);
        framework.start();
        context = framework.getBundleContext();
    }

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testSplitPackageOfTwoRequiredBundles() throws Exception {
        Bundle r1 = context.installBundle("split-r1", bundle("split.r1", new Class<?>[] { HookS.class }, Constants.EXPORT_PACKAGE, S));
        Bundle r2 = context.installBundle("split-r2", bundle("split.r2", new Class<?>[] { HookS.class }, Constants.EXPORT_PACKAGE, S));
        Bundle e = context.installBundle("split-e", bundle("split.e", new Class<?>[] { HookP.class },
                Constants.EXPORT_PACKAGE, P + ";uses:=\"" + S + "\"", Constants.REQUIRE_BUNDLE, "split.r1,split.r2"));
        assertDynamicImport(e, r1, r2);
    }

    @Test
    public void testFacadeThatExportsTheRequiredPackage() throws Exception {
        Bundle r1 = context.installBundle("facade-r1", bundle("facade.r1", new Class<?>[] { HookS.class }, Constants.EXPORT_PACKAGE, S));
        Bundle e = context.installBundle("facade-e", bundle("facade.e", new Class<?>[] { HookP.class, HookS.class },
                Constants.EXPORT_PACKAGE, P + ";uses:=\"" + S + "\"," + S, Constants.REQUIRE_BUNDLE, "facade.r1"));
        assertDynamicImport(e, r1);
    }

    @Test
    public void testUsesWithImportControl() throws Exception {
        Bundle r1 = context.installBundle("control-r1", bundle("control.r1", new Class<?>[] { HookS.class }, Constants.EXPORT_PACKAGE, S));
        Bundle e = context.installBundle("control-e", bundle("control.e", new Class<?>[] { HookP.class },
                Constants.EXPORT_PACKAGE, P + ";uses:=\"" + S + "\"", Constants.IMPORT_PACKAGE, S));
        assertDynamicImport(e, r1);
    }

    // The host sees no package s, its dynamic import of p wires to E
    private void assertDynamicImport(Bundle exporter, Bundle... others) throws Exception {
        Bundle host = context.installBundle(exporter.getLocation() + "-host", bundle(exporter.getSymbolicName() + ".host", Constants.DYNAMICIMPORT_PACKAGE, P));
        exporter.start();
        for (Bundle other : others)
            other.start();
        host.start();
        assertEquals("Precondition: the exporter is resolved", Bundle.ACTIVE, exporter.getState());
        Class<?> loaded = host.loadClass(HookP.class.getName());
        assertNotNull(loaded);
        assertEquals("The class comes from the exporter", exporter.loadClass(HookP.class.getName()), loaded);
    }
}
