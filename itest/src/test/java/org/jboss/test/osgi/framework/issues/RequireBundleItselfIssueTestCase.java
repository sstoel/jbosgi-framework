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

import java.io.InputStream;
import java.util.Arrays;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A Require-Bundle clause that matches only the requiring bundle itself is never satisfied: the bundle does not
 * resolve, although a requirement may be satisfied by a capability of the bundle itself.
 *
 * <h3>Severity</h3>
 * minor - unusual manifests (a bundle that requires its own symbolic name, e.g. generated or copied manifests, or a
 * range that includes the bundle's own version among other providers of that name) do not resolve; they resolve on
 * Equinox and Felix.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractRequirement.matchesResourceRequirement}.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of randomized resolution scenarios (seeds s00011, s00048, s00110, s00169:
 * a bundle with Require-Bundle on its own symbolic name stays INSTALLED on jbosgi and resolves on Equinox 3.21 and
 * Felix 7.0.5).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.8 Resolving Process
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.resolvingprocess):
 * "Any mandatory requirement must be matched to at least one capability in the same namespace provided by any of the
 * resolved bundles, including itself and the system bundle."
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractRequirement.java:246-252}
 * ({@code // cannot require itself: if (getResource() == cap.getResource()) return false;}), since commit d760be7
 * (2012). Pre-existing.
 *
 * <h3>Root cause</h3>
 * An osgi.wiring.bundle requirement is explicitly prevented from matching the requirer's own osgi.wiring.bundle
 * capability, so the resolver finds no provider.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=RequireBundleItselfIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests
 * -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testRequireBundleItself: A bundle whose Require-Bundle names itself must resolve expected:&lt;true&gt; but
 *   was:&lt;false&gt;
 * (Bundle.start() of such a bundle fails with "ReasonException: Unable to resolve HostBundleRevision[a:0.0.0]: missing
 * requirement XIdentityRequirement[dirs={filter=(osgi.wiring.bundle=a)},[a:0.0.0]]")
 * </pre>
 *
 * <h3>Expected</h3>
 * The bundle resolves; the self wire may be omitted from the wiring (Equinox reports no osgi.wiring.bundle wire for
 * it).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 resolve the bundle.
 *
 * <h3>Suggested fix</h3>
 * Remove the self check from {@code matchesResourceRequirement} (and let the framework drop or keep the self wire as
 * for an import of the bundle's own export).
 *
 * <h3>Regression vs WF41</h3>
 * no - same code on WF41.
 *
 * @since 13-Oct-2026
 */
public class RequireBundleItselfIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRequireBundleItself() throws Exception {
        Bundle bundle = installBundle(createBundle("require-itself"));
        try {
            assertEquals("A bundle whose Require-Bundle names itself must resolve", true, getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle)));
        } finally {
            bundle.uninstall();
        }
    }

    private JavaArchive createBundle(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addRequireBundle(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
