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
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.issues.facade.q.FacadeQ;
import org.jboss.test.osgi.framework.issues.facade.s.FacadeS;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle that exports a package of a bundle that it requires (a facade) does not provide that package any more as
 * soon as it also imports another package from that bundle.
 *
 * <h2>Severity</h2>
 * major. The importers of the facade package get a ClassNotFoundException although they are wired to the facade.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: the export filter of a module dependency, {@code ModuleManagerImpl.ModuleDependencyHolder}.
 *
 * <h2>Found by</h2>
 * R8 fix review, confirmed by test (fw-small-07).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.13.3 Split Packages: a bundle can export a package that it gets from a required bundle.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:713 (0c897536).
 *
 * <h2>Root cause</h2>
 * A required bundle that is also an Import-Package exporter has one module dependency for both. Its export filter
 * kept only the host exports that the host imports, which left out those from the required bundle.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=RequireBundleFacadeWithImportIssueTestCase
 * </pre>
 * E exports s and q, H requires E, imports q and exports s, X imports s from H.
 *
 * <h2>Expected</h2>
 * X loads the class of s from E through its wire to H, with and without the Import-Package of H.
 *
 * <h2>Regression vs WF41</h2>
 * no (broken differently before 0c897536).
 */
public class RequireBundleFacadeWithImportIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFacadeWithImportFromRequiredBundle() throws Exception {
        assertFacade("facade-imp", true);
    }

    @Test
    public void testFacadeWithoutImport() throws Exception {
        assertFacade("facade-ctl", false);
    }

    private void assertFacade(String prefix, final boolean withImport) throws Exception {
        final String s = FacadeS.class.getPackage().getName();
        final String q = FacadeQ.class.getPackage().getName();
        final String e = prefix + "-e";
        final String h = prefix + "-h";
        Bundle exporter = installBundle(archive(e, new Headers() {
            void add(OSGiManifestBuilder builder) {
                builder.addExportPackages(s, q);
            }
        }, FacadeS.class, FacadeQ.class));
        Bundle facade = installBundle(archive(h, new Headers() {
            void add(OSGiManifestBuilder builder) {
                builder.addRequireBundle(e);
                if (withImport)
                    builder.addImportPackages(q);
                builder.addExportPackages(s);
            }
        }));
        Bundle importer = installBundle(archive(prefix + "-x", new Headers() {
            void add(OSGiManifestBuilder builder) {
                builder.addImportPackages(s + ";bundle-symbolic-name=" + h);
            }
        }));
        try {
            FrameworkWiring frameworkWiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, facade, importer)));
            BundleWire wire = importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).get(0);
            assertEquals("The importer is wired to the facade", facade, wire.getProvider().getBundle());

            Class<?> clazz = importer.loadClass(FacadeS.class.getName());
            assertEquals("The class of s comes from the required bundle", exporter.loadClass(FacadeS.class.getName()), clazz);
        } finally {
            importer.uninstall();
            facade.uninstall();
            exporter.uninstall();
        }
    }

    abstract static class Headers {
        abstract void add(OSGiManifestBuilder builder);
    }

    private static JavaArchive archive(final String name, final Headers headers, Class<?>... classes) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (classes.length > 0)
            archive.addClasses(classes);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                headers.add(builder);
                return builder.openStream();
            }
        });
        return archive;
    }
}
