package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The resolver always prefers a package exported by the system bundle, even when a resolved bundle exports the same
 * package with a higher version: an Import-Package without a version range is wired to the older system package.
 *
 * <h3>Severity</h3>
 * minor - affects bundles that provide a newer version of a package that is also a system package (for example a
 * newer OSGi API or org.jboss.logging) and importers without a version range; they get the older system package.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.FrameworkPreferencesComparator} (used by
 * {@code AbstractResolveContext.findProviders} / {@code insertHostedCapability}).
 *
 * <h3>Found by</h3>
 * resolver workstream (compliance matrix of service.resolver / 3.8), Equinox/Felix differential: bundle a exports
 * org.osgi.framework;version=1.11 and is resolved; b imports org.osgi.framework: jbosgi wires b to the system bundle
 * (1.10), Equinox 3.21 and Felix 7.0.5 wire it to a (1.11).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.8 Resolving Process
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.resolvingprocess):
 * "The following list defines the preferences, if multiple choices are possible, in order of decreasing priority: A
 * resolved exporter must be preferred over an unresolved exporter. An exporter with a higher version is preferred
 * over an exporter with a lower version. An exporter with a lower bundle ID is preferred over a bundle with a higher
 * ID." (The system bundle has the lowest bundle id, so it only wins ties.) ResolveContext.findProviders javadoc: "The
 * returned list contains Capabilitys in priority order".
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/FrameworkPreferencesComparator.java:52-56}
 * ("prefer system bundle": returns before the version comparison when one capability is provided by resource index
 * 0). Pre-existing, not touched by the R7/R8 commits.
 *
 * <h3>Root cause</h3>
 * The system bundle check comes first in the comparator instead of being covered by "resolved first" and "lowest
 * bundle id" after the version comparison.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemBundlePreferredExporterIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}). The itest configuration exports org.jboss.logging;version=3.0 from the
 * system bundle (jboss-osgi-framework.properties).
 *
 * <h3>Observed</h3>
 * <pre>
 * testHigherVersionPreferredOverSystemBundle: The import must be wired to the resolved exporter of the higher
 *   version expected:&lt;system-preference-exporter:0.0.0&gt; but was:&lt;system.bundle:0.0.0&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The importer is wired to the resolved bundle that exports version 3.5.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 wire the importer to the higher version of the resolved bundle.
 *
 * <h3>Suggested fix</h3>
 * Remove the "prefer system bundle" shortcut from {@code FrameworkPreferencesComparator.compare} (the system bundle
 * is resolved and has index 0, so "prefer wired" and "lower index" already order it correctly); keep it only as a
 * tie breaker.
 *
 * <h3>Regression vs WF41</h3>
 * no - same comparator on WF41.
 *
 * @since 13-Oct-2026
 */
public class SystemBundlePreferredExporterIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE = "org.jboss.logging";

    @Test
    public void testHigherVersionPreferredOverSystemBundle() throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        Bundle exporter = installBundle(createBundle("system-preference-exporter", PACKAGE + ";version=3.5", null));
        try {
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter)));
            Bundle importer = installBundle(createBundle("system-preference-importer", null, PACKAGE));
            try {
                assertTrue(frameworkWiring.resolveBundles(Arrays.asList(importer)));
                List<BundleWire> wires = importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
                assertEquals("One package wire", 1, wires.size());
                assertEquals("The import must be wired to the resolved exporter of the higher version", exporter, wires.get(0).getProvider().getBundle());
            } finally {
                importer.uninstall();
            }
        } finally {
            exporter.uninstall();
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
                    builder.addManifestHeader("Export-Package", exports);
                if (imports != null)
                    builder.addManifestHeader("Import-Package", imports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
