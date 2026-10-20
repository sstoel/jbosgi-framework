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
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * An Import-Package with {@code bundle-symbolic-name=system.bundle} no longer resolves.
 *
 * <h2>Severity</h2>
 * major. Bundles that select the framework packages with the symbolic name of the system bundle fail to resolve since
 * the system bundle has its own symbolic name (org.jboss.osgi.framework) with system.bundle as an alias.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: the capabilities of the system bundle, {@code BundleStateRevision}.
 *
 * <h2>Found by</h2>
 * R8 fix review, regression 10 (fw-minor, services/14).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.6 The System Bundle: the system bundle has the alias system.bundle; 3.6.5 Export-Package: the
 * bundle-symbolic-name attribute of an export is the symbolic name of the exporting bundle. Equinox gives the package
 * exports of the system bundle both names.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleStateRevision.java:96-100 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The alias is added to the bundle and host capabilities of the system bundle, not to the bundle-symbolic-name of its
 * package capabilities.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=SystemBundleAliasImportIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The import resolves to the system bundle with either name.
 *
 * <h2>Regression vs WF41</h2>
 * yes, introduced by dc5d2881.
 */
public class SystemBundleAliasImportIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testImportWithSystemBundleAlias() throws Exception {
        assertImportResolves("system-alias-import", Constants.SYSTEM_BUNDLE_SYMBOLICNAME);
    }

    @Test
    public void testImportWithSystemBundleSymbolicName() throws Exception {
        assertImportResolves("system-name-import", getSystemContext().getBundle().getSymbolicName());
    }

    private void assertImportResolves(String name, final String symbolicName) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addImportPackages("org.osgi.framework;bundle-symbolic-name=\"" + symbolicName + "\"");
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            FrameworkWiring frameworkWiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            boolean resolved = frameworkWiring.resolveBundles(Collections.singleton(bundle));
            assertTrue("Import-Package: org.osgi.framework;bundle-symbolic-name=" + symbolicName + " resolves", resolved);
            List<BundleWire> wires = bundle.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
            assertEquals(1, wires.size());
            assertEquals(0, wires.get(0).getProvider().getBundle().getBundleId());
        } finally {
            bundle.uninstall();
        }
    }
}
