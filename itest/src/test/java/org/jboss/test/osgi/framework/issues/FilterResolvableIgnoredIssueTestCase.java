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

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;

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
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A bundle that a ResolverHook removed in filterResolvable is resolved anyway when another resolvable bundle has an
 * optional Import-Package, and a fragment removed in filterResolvable is resolved (attached) with its host.
 *
 * <h3>Severity</h3>
 * major - filterResolvable is the veto of the resolver hook API (53.2.4: "a bundle that is being installed should not
 * be allowed to resolve until some activity has taken place. Sometimes certain bundles should never resolve");
 * isolation layers (regions, subsystems, "do not resolve until configured" agents, weaving set-ups where a source
 * bundle must never resolve) depend on it. A single bundle with an optional import in the same resolve operation
 * (or the vetoed bundle being a fragment) silently lifts the veto: the vetoed bundle is RESOLVED, wired and its
 * classes loadable, and resolveBundles reports success for it.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkResolverImpl#resolveInternal}, together
 * with jbosgi-resolver api {@code org.jboss.osgi.resolver.spi.AbstractResolver#createResolveContext}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 2): seeded stateful resolver-hook differential
 * {@code fa.ResolverHookModel} (9 generated bundles with random imports/exports/optional imports/Require-Bundle/
 * DynamicImport-Package/singletons; ResolverHookFactories with 14 behaviours registered and unregistered at random;
 * install/resolve/start/stop/load/uninstall/refresh; invariant "a bundle removed by filterResolvable does not
 * resolve in that operation") against Equinox 3.21 and Felix 7.0.5. Seed 1 step 187 and seed 4 violated the
 * invariant on jbosgi only; the op script of seed 1 was delta-minimised ({@code ddmin.py}, 87 replays) to 4 steps:
 * install b8 (optional import), install b3 (optional import), register a factory whose hook removes b3,
 * resolveBundles([b3]) - jbosgi returns true and b3 is RESOLVED; Equinox returns false and b3 stays INSTALLED. The
 * fragment variant was found by the follow-up probe {@code fa.ProbeFrag}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 53.3 The Resolve Operation
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.resolverhooks.html): "For each active Resolver Hook in
 * H(ooks), call the filterResolvable(Collection) method with U. The Handler can remove any candidate that should not
 * be resolved. U now contains all the unresolved bundle revisions that potentially could be resolved during this
 * resolve operation. Any bundle revisions that were removed by Handlers must not resolve in this operation."<br>
 * 53.5.2.3 ResolverHook.filterResolvable: "Filter resolvable candidates hook method. This method may be called multiple
 * times during a single resolve process. This method can filter the collection of candidates by removing potential
 * candidates. Removing a candidate will prevent the candidate from resolving during the current resolve process."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:171}:
 * {@code resolveContext = resolver.createResolveContext(env, getFilteredResources(hookregs, manres),
 * getFilteredResources(hookregs, optres));} - the hook-filtered resources go through createResolveContext again, and
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractResolver.java:68-73} re-runs
 * {@code appendOptionalFragments} (:115-141, adds every attachable fragment of a host in the set) and
 * {@code appendOptionalHostBundles} (:143-158, "Append the set of all unresolved resources if there is at least one
 * optional package requirement"), which put the vetoed revisions back into the optional resources. The findProviders
 * hook filter (AbstractEnvironment :285-300) does not help: the re-added revision is a resource of the resolve, not
 * just a provider. Pre-existing (FrameworkResolverImpl 471e2283 / AbstractResolver ee1f897, 2013); unchanged by the
 * R8 branch.
 *
 * <h3>Root cause</h3>
 * The resolve context is built from the filtered resources with the same "append optional fragments / all unresolved
 * bundles" expansion that is meant for the initial trigger set, after the hooks have filtered it; nothing removes
 * the vetoed revisions afterwards.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=FilterResolvableIgnoredIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testVetoedBundleNotResolved: The bundle removed by filterResolvable must not resolve, state expected:&lt;[INSTALLED]&gt;
 *   but was:&lt;[RESOLVED]&gt;
 * testVetoedFragmentNotAttached: The fragment removed by filterResolvable must not resolve, state
 *   expected:&lt;[INSTALLED]&gt; but was:&lt;[RESOLVED]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The vetoed bundle and the vetoed fragment stay INSTALLED (resolveBundles returns false when the vetoed bundle was
 * one of its arguments); the other bundle / the host resolve.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: vetoed bundle and fragment stay INSTALLED, resolveBundles false for the vetoed bundle. Felix 7.0.5:
 * the vetoed bundle stays INSTALLED (resolveBundles false), but it attaches the vetoed fragment (same deviation as
 * jbosgi for the fragment case).
 *
 * <h3>Suggested fix</h3>
 * After the hooks have filtered the resources, create the resolve context without the expansion (or expand first and
 * filter afterwards: call filterResolvable on the expanded set and remove every revision that is not in the hook
 * candidates from both the mandatory and the optional resources).
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class FilterResolvableIgnoredIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testVetoedBundleNotResolved() throws Exception {
        Bundle vetoed = installBundle(getBundle("filter-resolvable-vetoed", null, null));
        Bundle other = installBundle(getBundle("filter-resolvable-other", "org.acme.optional.missing;resolution:=optional", null));
        ServiceRegistration<ResolverHookFactory> reg = getSystemContext().registerService(ResolverHookFactory.class, new VetoFactory(vetoed), null);
        try {
            boolean result = getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(vetoed, other));
            assertEquals("The bundle that is not vetoed resolves", Bundle.RESOLVED, other.getState());
            assertEquals("The bundle removed by filterResolvable must not resolve, state", "INSTALLED", stateName(vetoed.getState()));
            assertFalse("resolveBundles must return false, the vetoed bundle is not resolved", result);
        } finally {
            reg.unregister();
            other.uninstall();
            vetoed.uninstall();
        }
    }

    @Test
    public void testVetoedFragmentNotAttached() throws Exception {
        Bundle host = installBundle(getBundle("filter-resolvable-host", null, null));
        Bundle fragment = installBundle(getBundle("filter-resolvable-fragment", null, "filter-resolvable-host"));
        ServiceRegistration<ResolverHookFactory> reg = getSystemContext().registerService(ResolverHookFactory.class, new VetoFactory(fragment), null);
        try {
            getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(host));
            assertEquals("The host resolves", Bundle.RESOLVED, host.getState());
            assertEquals("The fragment removed by filterResolvable must not resolve, state", "INSTALLED", stateName(fragment.getState()));
        } finally {
            reg.unregister();
            fragment.uninstall();
            host.uninstall();
        }
    }

    static class VetoFactory implements ResolverHookFactory {
        private final Bundle vetoed;

        VetoFactory(Bundle vetoed) {
            this.vetoed = vetoed;
        }

        public ResolverHook begin(Collection<BundleRevision> triggers) {
            return new ResolverHook() {
                public void filterResolvable(Collection<BundleRevision> candidates) {
                    for (java.util.Iterator<BundleRevision> it = candidates.iterator(); it.hasNext();) {
                        if (it.next().getBundle() == vetoed)
                            it.remove();
                    }
                }

                public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                }

                public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                }

                public void end() {
                }
            };
        }
    }

    private static String stateName(int state) {
        switch (state) {
        case Bundle.INSTALLED: return "INSTALLED";
        case Bundle.RESOLVED: return "RESOLVED";
        case Bundle.ACTIVE: return "ACTIVE";
        default: return "STATE" + state;
        }
    }

    private JavaArchive getBundle(final String name, final String imports, final String fragmentHost) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (imports != null)
                    builder.addImportPackages(imports);
                if (fragmentHost != null)
                    builder.addFragmentHost(fragmentHost);
                return builder.openStream();
            }
        });
        return archive;
    }
}
