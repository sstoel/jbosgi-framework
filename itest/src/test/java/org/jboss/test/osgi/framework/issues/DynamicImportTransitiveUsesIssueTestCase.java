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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.jboss.test.osgi.framework.bundle.support.b.ObjectB;
import org.jboss.test.osgi.framework.bundle.support.c.ObjectC;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A dynamic import is wired to an exporter whose class space conflicts with the importer: the uses constraints are
 * only checked one level deep, and without the packages that the importer gets from its required bundles.
 *
 * <h2>Severity</h2>
 * minor. The importer can get a ClassCastException or a LinkageError when it uses classes of both class spaces.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FallbackLoader.isUsesConsistent}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of resolver/04 (fw-major).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.7.6 Uses Directive and 3.9.2 Dynamic Import Package: a dynamic import must not make the class space of
 * the importer inconsistent; the uses constraints are transitive.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:497-530 (89ed93ad).
 *
 * <h2>Root cause</h2>
 * The check compared only the packages that the candidate's uses directive names directly, against the import wires and
 * the exports of the importer.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=DynamicImportTransitiveUsesIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The dynamic import is wired to the exporter of the lower version, which is consistent with the class space of the
 * importer.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class DynamicImportTransitiveUsesIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE_A = ObjectA.class.getPackage().getName();
    private static final String PACKAGE_B = ObjectB.class.getPackage().getName();
    private static final String PACKAGE_C = ObjectC.class.getPackage().getName();

    @Test
    public void testTransitiveUses() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            install(bundles, "dyntrans-c1", headers("Export-Package", PACKAGE_C + ";version=1.0"), ObjectC.class);
            install(bundles, "dyntrans-c2", headers("Export-Package", PACKAGE_C + ";version=2.0"), ObjectC.class);
            // b 1.0 uses c and is wired to c 2.0
            install(bundles, "dyntrans-b", headers("Export-Package", PACKAGE_B + ";version=1.0;uses:=\"" + PACKAGE_C + "\"", "Import-Package",
                    PACKAGE_C + ";version=\"[2,3)\""), ObjectB.class);
            // a 2.0 uses b, so it sees c 2.0 through b; a 1.0 uses nothing
            install(bundles, "dyntrans-a2", headers("Export-Package", PACKAGE_A + ";version=2.0;uses:=\"" + PACKAGE_B + "\"", "Import-Package",
                    PACKAGE_B + ";version=\"[1,2)\""), ObjectA.class);
            Bundle consistent = install(bundles, "dyntrans-a1", headers("Export-Package", PACKAGE_A + ";version=1.0"), ObjectA.class);
            // The importer sees c 1.0
            Bundle importer = install(bundles, "dyntrans-importer", headers("Import-Package", PACKAGE_C + ";version=\"[1,2)\"",
                    "DynamicImport-Package", PACKAGE_A), null);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
            importer.loadClass(ObjectA.class.getName());
            assertEquals("The dynamic import is wired to the exporter that does not imply c 2.0", consistent, getProvider(importer, PACKAGE_A));
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testUsesOfRequiredBundlePackage() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            install(bundles, "dynreq-b1", headers("Export-Package", PACKAGE_B + ";version=1.0"), ObjectB.class);
            install(bundles, "dynreq-b2", headers("Export-Package", PACKAGE_B + ";version=2.0"), ObjectB.class);
            install(bundles, "dynreq-a2", headers("Export-Package", PACKAGE_A + ";version=2.0;uses:=\"" + PACKAGE_B + "\"", "Import-Package",
                    PACKAGE_B + ";version=\"[2,3)\""), ObjectA.class);
            Bundle consistent = install(bundles, "dynreq-a1", headers("Export-Package", PACKAGE_A + ";version=1.0;uses:=\"" + PACKAGE_B + "\"",
                    "Import-Package", PACKAGE_B + ";version=\"[1,2)\""), ObjectA.class);
            // The importer sees b 1.0 through its required bundle
            Bundle importer = install(bundles, "dynreq-importer", headers("Require-Bundle", "dynreq-b1", "DynamicImport-Package", PACKAGE_A), null);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
            importer.loadClass(ObjectA.class.getName());
            assertEquals("The dynamic import is wired to the exporter that uses the b of the required bundle", consistent, getProvider(importer, PACKAGE_A));
        } finally {
            uninstall(bundles);
        }
    }

    private static Map<String, String> headers(String... nameValues) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < nameValues.length; i += 2)
            result.put(nameValues[i], nameValues[i + 1]);
        return result;
    }

    private Bundle install(List<Bundle> bundles, final String name, final Map<String, String> headers, Class<?> content) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (content != null)
            archive.addClasses(content);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                for (Map.Entry<String, String> entry : headers.entrySet())
                    builder.addManifestHeader(entry.getKey(), entry.getValue());
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        bundles.add(bundle);
        return bundle;
    }

    private static Bundle getProvider(Bundle importer, String packageName) {
        for (BundleWire wire : importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (packageName.equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                return wire.getProvider().getBundle();
        }
        return null;
    }

    private static void uninstall(List<Bundle> bundles) throws Exception {
        for (Bundle bundle : bundles)
            bundle.uninstall();
    }
}
