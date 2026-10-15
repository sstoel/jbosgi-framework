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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The framework resolves more than one bundle of a singleton symbolic name: a singleton bundle is resolved next to an
 * already resolved singleton of the same name, and two singletons of the same name are resolved together when they
 * are pulled in as providers.
 *
 * <h3>Severity</h3>
 * major - the singleton directive is the standard way to keep a second version of a bundle (Eclipse plug-ins,
 * bundles that own a global resource) from resolving; installing a new version next to the old one, or a bundle
 * that imports from both, silently gives two resolved singletons.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkResolverImpl} ({@code filterSingletons});
 * jbosgi-resolver {@code AbstractEnvironment.findProviders} offers the capabilities of every installed singleton.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of randomized resolution scenarios (seed s00170: two singleton
 * {@code b4} bundles both RESOLVED on jbosgi, one on Equinox 3.21 and Felix 7.0.5), then reduced by hand.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.6.2 Bundle-SymbolicName
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.bsn):
 * "singleton - Indicates that the bundle can only have a single version resolved. [...] The Framework must resolve
 * at most one bundle when multiple versions of a singleton bundle with the same symbolic name are installed."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:226-240}
 * ({@code filterSingletons}, called at line 173 when no resolver hook is registered). The code dates from 2012
 * (commit 8814dd28 "Fix singleton handling in resolver call"); unchanged by the R7/R8 commits.
 *
 * <h3>Root cause</h3>
 * {@code filterSingletons} only removes duplicate singletons from the collection of resources that is passed to
 * the resolver (mandatory + optional). It does not look at singletons that are already resolved, and it does not
 * stop the resolver from pulling an unresolved singleton in as a provider: {@code AbstractEnvironment.findProviders}
 * returns the capabilities of every installed bundle, so the Felix resolver resolves any singleton that provides a
 * needed capability. The Felix resolver has no singleton notion of its own (that is the job of the ResolveContext,
 * see the ResolveContext class javadoc: "the returned capabilities [...] must not violate the singleton policy of
 * the implementer"). Only the resolver hook path ({@code ResolverHookProcessor.filterSingletonCollisions} plus the
 * hook check in {@code AbstractEnvironment.findProviders}) takes resolved singletons into account.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SingletonNotEnforcedIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testSingletonNextToResolvedSingleton: A second singleton of the same symbolic name must not resolve while
 *   singleton:1.0.0 is resolved, but resolveBundles returned true expected:&lt;false&gt; but was:&lt;true&gt;
 * testSingletonsPulledInAsProviders: At most one of the singletons singleton:1.0.0 and singleton:2.0.0 may be
 *   resolved, but 2 are resolved
 * </pre>
 *
 * <h3>Expected</h3>
 * At most one bundle per singleton symbolic name is resolved at any time: the second singleton stays INSTALLED (and
 * resolveBundles returns false); a bundle that needs packages from both singleton versions does not resolve.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 (same scenarios run through the launch API): only one singleton resolves in both
 * cases, the second singleton stays INSTALLED and resolveBundles returns false.
 *
 * <h3>Suggested fix</h3>
 * Enforce the singleton policy in the ResolveContext used without hooks: treat every resolved singleton as a
 * collision for the unresolved singletons of the same name (remove them from the resources to resolve), and let
 * {@code findProviders} drop capabilities of unresolved singletons that collide with a resolved singleton or that
 * are not the chosen singleton of the resolve operation (same mechanism as the hook path:
 * {@code ResolverHookProcessor.filterSingletonCollisions} + {@code hasResource}).
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 (jbosgi-framework 6.6.0) behaves the same; still a spec violation on the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class SingletonNotEnforcedIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testSingletonNextToResolvedSingleton() throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        Bundle first = installBundle(createBundle("singleton-v1", "singleton;singleton:=true", "1.0.0", null, null));
        try {
            assertEquals("The first singleton must resolve", true, frameworkWiring.resolveBundles(Collections.singleton(first)));
            Bundle second = installBundle(createBundle("singleton-v2", "singleton;singleton:=true", "2.0.0", null, null));
            try {
                boolean resolved = frameworkWiring.resolveBundles(Collections.singleton(second));
                assertEquals("A second singleton of the same symbolic name must not resolve while " + first + " is resolved, but resolveBundles returned " + resolved, false, resolved);
                assertEquals("The second singleton must stay INSTALLED", Bundle.INSTALLED, second.getState());
            } finally {
                second.uninstall();
            }
        } finally {
            first.uninstall();
        }
    }

    @Test
    public void testSingletonsPulledInAsProviders() throws Exception {
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        Bundle v1 = installBundle(createBundle("singleton-p1", "singleton;singleton:=true", "1.0.0", "org.acme.singleton.p1", null));
        Bundle v2 = installBundle(createBundle("singleton-p2", "singleton;singleton:=true", "2.0.0", "org.acme.singleton.p2", null));
        Bundle importer = installBundle(createBundle("singleton-importer", "importer", "1.0.0", null, "org.acme.singleton.p1,org.acme.singleton.p2"));
        try {
            frameworkWiring.resolveBundles(Collections.singleton(importer));
            int resolved = 0;
            for (Bundle bundle : Arrays.asList(v1, v2)) {
                if ((bundle.getState() & Bundle.RESOLVED) != 0)
                    resolved++;
            }
            assertTrue("At most one of the singletons " + v1 + " and " + v2 + " may be resolved, but " + resolved + " are resolved", resolved <= 1);
            assertFalse("The importer needs both singleton versions and must not resolve", importer.getState() == Bundle.RESOLVED);
        } finally {
            importer.uninstall();
            v2.uninstall();
            v1.uninstall();
        }
    }

    private JavaArchive createBundle(final String name, final String symbolicName, final String version, final String exports, final String imports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleVersion(version);
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
