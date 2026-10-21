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
import java.util.Collection;
import java.util.Collections;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * While a resolver hook is registered, a new importer cannot be wired to an uninstalled exporter that is still in use:
 * the capabilities of removal pending revisions are dropped before the hooks see the matches.
 *
 * <h2>Severity</h2>
 * minor. As fuzz/48, only while a ResolverHookFactory is registered: the importer does not resolve.
 *
 * <h2>Component</h2>
 * jbosgi-resolver, api: {@code AbstractEnvironment.filterByHooks}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of fuzz/48 and resolver/09 (support-01).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.4.10: the packages of an uninstalled bundle that is still in use remain available to new importers
 * until the next refresh.
 *
 * <h2>Code location</h2>
 * jbosgi-resolver api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:298 (ff9e37c).
 *
 * <h2>Root cause</h2>
 * The hook filter passed only capabilities of effective revisions and of the resources of the resolve.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=RemovalPendingCapabilityWithResolverHookIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The new importer is wired to the uninstalled exporter, as without a hook.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class RemovalPendingCapabilityWithResolverHookIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRemovalPendingExporterWithResolverHook() throws Exception {
        Bundle exporter = installBundle(createBundle("pending-hook-exporter", "org.acme.pendinghook", null));
        Bundle importer = installBundle(createBundle("pending-hook-importer", null, "org.acme.pendinghook"));
        Bundle late = null;
        ServiceRegistration<ResolverHookFactory> registration = null;
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, importer)));
            exporter.uninstall();

            registration = getSystemContext().registerService(ResolverHookFactory.class, new ResolverHookFactory() {
                public ResolverHook begin(Collection<BundleRevision> triggers) {
                    return new ResolverHook() {
                        public void filterResolvable(Collection<BundleRevision> candidates) {
                        }

                        public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                        }

                        public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                        }

                        public void end() {
                        }
                    };
                }
            }, null);

            late = installBundle(createBundle("pending-hook-late", null, "org.acme.pendinghook"));
            assertTrue("The late importer resolves while a resolver hook is registered", frameworkWiring.resolveBundles(Collections.singleton(late)));
            Bundle provider = late.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).get(0).getProvider().getBundle();
            assertEquals("The late importer is wired to the uninstalled exporter", exporter, provider);
        } finally {
            if (registration != null)
                registration.unregister();
            if (late != null)
                late.uninstall();
            importer.uninstall();
            if (exporter.getState() != Bundle.UNINSTALLED)
                exporter.uninstall();
            refreshBundles(null);
        }
    }

    private JavaArchive createBundle(final String name, final String exports, final String imports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (exports != null)
                    builder.addExportPackages(exports);
                if (imports != null)
                    builder.addImportPackages(imports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
