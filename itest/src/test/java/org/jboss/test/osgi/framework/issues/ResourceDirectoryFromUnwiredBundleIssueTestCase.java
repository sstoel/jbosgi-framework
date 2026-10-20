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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle with DynamicImport-Package: * sees the META-INF/services and OSGI-INF entries of another bundle that exports
 * nothing and to which it has no wire.
 *
 * <h2>Severity</h2>
 * major. The ServiceLoader and DS descriptors of unrelated bundles leak into every bundle with a wildcard dynamic import:
 * the lowest-id resolved bundle that has the entry serves it.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FallbackLoader.findResourceDirectory}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 9 (fw-major-03).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.9.4 Overall Search Order: a dynamic import is wired to an exported package; a resource of a bundle
 * that exports nothing is not visible to other bundles.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:559 (89ed93ad).
 *
 * <h2>Root cause</h2>
 * The lookup of a resource directory that is not a package (for the module bundles of WildFly, whose capabilities name
 * every path of a module) takes any resolved bundle that has the entry, without a capability for the directory.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ResourceDirectoryFromUnwiredBundleIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The importer does not see the entries of the provider.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by 89ed93ad.
 */
public class ResourceDirectoryFromUnwiredBundleIssueTestCase extends OSGiFrameworkTest {

    private static final String SERVICES = "META-INF/services/org.jboss.test.osgi.Unwired";
    private static final String COMPONENT = "OSGI-INF/unwired-component.xml";

    @Test
    public void testResourceDirectoryOfUnwiredBundle() throws Exception {
        final JavaArchive provider = ShrinkWrap.create(JavaArchive.class, "unwired-resource-provider");
        provider.add(new StringAsset("org.jboss.test.osgi.UnwiredImpl\n"), SERVICES);
        provider.add(new StringAsset("<component/>"), COMPONENT);
        provider.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(provider.getName());
                return builder.openStream();
            }
        });
        final JavaArchive importer = ShrinkWrap.create(JavaArchive.class, "unwired-resource-importer");
        importer.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(importer.getName());
                builder.addDynamicImportPackages("*");
                return builder.openStream();
            }
        });
        Bundle providerBundle = installBundle(provider);
        Bundle importerBundle = installBundle(importer);
        try {
            assertTrue(getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(providerBundle, importerBundle)));
            assertEquals(Bundle.RESOLVED, providerBundle.getState());
            assertEquals(Bundle.RESOLVED, importerBundle.getState());

            URL services = importerBundle.getResource(SERVICES);
            URL component = importerBundle.getResource(COMPONENT);
            assertNull("The provider exports nothing, the importer must not see its services file: " + services, services);
            assertNull("The provider exports nothing, the importer must not see its OSGI-INF entry: " + component, component);
        } finally {
            importerBundle.uninstall();
            providerBundle.uninstall();
        }
    }
}
