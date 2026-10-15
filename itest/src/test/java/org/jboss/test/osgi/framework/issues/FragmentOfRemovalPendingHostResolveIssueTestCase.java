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
import static org.junit.Assert.fail;

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
 * Resolving after an update of a host whose fragment exports a package that is in use throws NullPointerException
 * from inside the resolver: FrameworkWiring.resolveBundles(null) fails with an unchecked exception on every call, and
 * the new revision of the host can never resolve until the host is refreshed.
 *
 * <h3>Severity</h3>
 * major - an ordinary update of a host bundle with an attached fragment (fragments that export packages: language
 * packs, platform-specific implementations, test fragments) while its exports are used makes every later
 * resolve that touches one of the fragment's capabilities crash; resolveBundles throws an undeclared
 * NullPointerException instead of returning, the updated host cannot be resolved or started (management agents and
 * the WildFly osgi subsystem report the failure as an internal error), and the state is only cleared by a refresh.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractEnvironment#getWirings} (the ResolveContext wirings
 * passed to the embedded Felix resolver); crash site {@code org.apache.felix.resolver.Candidates#processCandidates}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 5): {@code fa.WiringModel} with fragments and Require-Bundle (seed 12,
 * 250 steps) against Equinox 3.21 and Felix 7.0.5: step 175 {@code resolveBundles(null)} threw
 * {@code NullPointerException: Cannot invoke "org.osgi.resource.Wiring.getResourceCapabilities(String)" because the
 * return value of "java.util.Map.get(Object)" is null} at {@code Candidates.processCandidates(Candidates.java:592)}
 * &lt; {@code Candidates.populate(:210)} &lt; {@code ResolverImpl.getInitialCandidates(:542)} &lt; ... &lt;
 * {@code FrameworkResolverImpl.resolveInternal(:182)} &lt; {@code FrameworkWiringImpl.resolveBundles(:270)}, again at
 * steps 239 and 241. The recorded op script (87 ops) was delta-minimised ({@code ddmin.py}, 41 replays) to 6 ops:
 * install w4 (exports q2, imports q3 optional), install w1 (imports q2, q3), install w5 (fragment of w1, exports q3),
 * resolveAll, update w1, resolveAll. Equinox and Felix resolve the new host revision.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.resolveBundles javadoc (OSGi Core R8): "@return true if all specified bundles are resolved; false
 * otherwise" (only IllegalArgumentException/SecurityException are declared).<br>
 * ResolveContext.getWirings javadoc (Resolver Service 1.1): "Returns the wirings for existing resolved resources."
 * - a removal pending host revision is resolved and in use, and the fragment's host wire points to it.<br>
 * 4.4.9 Updating Bundles: "If the old bundle revision has an isInUse() bundle wiring then all capabilities provided by
 * the old bundle wiring must remain available for existing bundles and future resolves until the bundle is refreshed".
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:348-357}
 * ({@code getWirings}: only {@code getWiring(true)}, the effective wirings; the updated host's old revision was made
 * non-effective by {@code UserBundleState.java:289}) while the attached fragment's wiring is still effective and its
 * host wire points to the old host revision; the embedded Felix resolver then dereferences
 * {@code rc.getWirings().get(wire.getProvider())} (felix {@code Candidates.java:590-592}) for a package capability of
 * the resolved fragment. Pre-existing (getWiring(true): 43d16c8, 2013); unchanged by the R8 branch. Same family as
 * fuzz/48 (removal pending revisions treated as unresolved), different code path and symptom.
 *
 * <h3>Root cause</h3>
 * The resolve context's wiring map leaves out resolved removal pending revisions, so it is inconsistent with the wirings
 * of other resources (here the fragment) that it does contain.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FragmentOfRemovalPendingHostResolveIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testResolveAfterHostUpdate: resolveBundles(null) after the update of the host must not throw, but threw
 *   java.lang.NullPointerException: Cannot invoke "org.osgi.resource.Wiring.getResourceCapabilities(String)" because
 *   the return value of "java.util.Map.get(Object)" is null
 * </pre>
 *
 * <h3>Expected</h3>
 * resolveBundles(null) returns (true: the new host revision resolves, its optional import is satisfied by the fragment
 * still attached to the old revision or left unwired).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: resolveBundles(null) returns true twice, the host is RESOLVED.
 *
 * <h3>Suggested fix</h3>
 * Return all resolved wirings that are still in use from getWirings (also non-effective, removal pending ones), as
 * findProviders should offer their capabilities (fuzz/48).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class FragmentOfRemovalPendingHostResolveIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testResolveAfterHostUpdate() throws Exception {
        Bundle exporter = installBundle(getBundle("frag-pending-exporter", null, "org.acme.frag.q2", "org.acme.frag.q3;resolution:=optional"));
        Bundle host = installBundle(getBundle("frag-pending-host", null, null, "org.acme.frag.q2,org.acme.frag.q3"));
        Bundle fragment = installBundle(getBundle("frag-pending-fragment", "frag-pending-host", "org.acme.frag.q3", null));
        try {
            FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
            assertTrue(frameworkWiring.resolveBundles(Arrays.asList(exporter, host, fragment)));
            assertEquals(Bundle.RESOLVED, fragment.getState());

            // The exporter imports the package that the fragment exports through the host: the old host revision stays in use
            host.update(toInputStream(getBundle("frag-pending-host", null, "org.acme.frag.q2", "org.acme.frag.q3;resolution:=optional")));
            try {
                frameworkWiring.resolveBundles(null);
            } catch (RuntimeException ex) {
                fail("resolveBundles(null) after the update of the host must not throw, but threw " + ex);
            }
            assertEquals("The new host revision resolves", Bundle.RESOLVED, host.getState());
        } finally {
            fragment.uninstall();
            host.uninstall();
            exporter.uninstall();
            refreshBundles(null);
        }
    }

    private JavaArchive getBundle(final String name, final String fragmentHost, final String exports, final String imports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (fragmentHost != null)
                    builder.addFragmentHost(fragmentHost);
                if (exports != null)
                    builder.addExportPackages(exports.split(","));
                if (imports != null)
                    builder.addImportPackages(imports.split(","));
                return builder.openStream();
            }
        });
        return archive;
    }
}
