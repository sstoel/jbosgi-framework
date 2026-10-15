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
import java.util.Collection;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A resolve operation that fails because a ResolverHook throws is not aborted consistently: when end() throws, the
 * resolve result has already been applied (the bundles are RESOLVED) although resolveBundles returns false and
 * Bundle.start throws REJECTED_BY_HOOK; and resolveBundles returns false for bundles that are already resolved as
 * soon as any hook throws.
 *
 * <h3>Severity</h3>
 * minor - needs a faulty resolver hook, but the framework then reports a state that is not true: callers (management
 * agents, the WildFly osgi subsystem, deployers) treat the bundle as not resolved / not started, while it is RESOLVED
 * and its classes are loadable; a retry finds the bundle already resolved and behaves differently. For resolveBundles
 * on resolved bundles the result is plainly wrong ({@code false} although every specified bundle is resolved).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkResolverImpl#resolveInternal} and
 * {@code FrameworkWiringImpl#resolveBundles}; jbosgi-resolver api {@code ResolverHookProcessor#end}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 2): {@code fa.ResolverHookModel} (seed 3, 300 steps) against Equinox
 * 3.21 and Felix 7.0.5: step 215, {@code resolveBundles([b7])} with b7 already RESOLVED while a factory whose hook
 * throws in end() was registered: Equinox true, jbosgi false. Follow-up probe {@code fa.ProbeEndThrows} (hook throwing
 * in end() resp. filterResolvable; resolveBundles of a resolved bundle, of an unresolved bundle, start of an
 * unresolved bundle).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 53.3.2 Failures
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.resolverhooks.html): "If during the resolving anything
 * goes wrong then the Resolver must abort the resolve operation, clean up and report an error. [...] The following
 * are potential failures: [...] A Resolver Hook throws an exception. If the Resolver fails, it must throw a Bundle
 * Exception to the caller if possible."<br>
 * FrameworkWiring.resolveBundles javadoc: "@return true if all specified bundles are resolved; false otherwise."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:184/192}
 * (results applied and RESOLVED events sent) before {@code :201} {@code hookregs.end()} in the finally block, whose
 * exception (jbosgi-resolver {@code ResolverHookProcessor.java:305-308}, "[TODO] The TCK expects to see the exception
 * thrown in end()") then fails the operation; {@code FrameworkWiringImpl.java:280-283} returns false for any
 * ResolverHookException without looking at the specified bundles. Pre-existing (b4f00e47/c62abbc1, 2012-2013);
 * unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The end() exception is reported as a failure of an operation whose result is already committed; resolveBundles
 * maps every hook exception to {@code false} instead of reporting the state of the specified bundles.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ResolverHookFailureStateIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testFailedResolveLeavesBundleUnresolved: resolveBundles returned false (a resolver hook threw in end()), so the
 *   resolve must have been aborted: state expected:&lt;[INSTALLED]&gt; but was:&lt;[RESOLVED]&gt;
 * testFailedStartLeavesBundleUnresolved: start() failed with BundleException (a resolver hook threw in end()), so
 *   the resolve must have been aborted: state expected:&lt;[INSTALLED]&gt; but was:&lt;[RESOLVED]&gt;
 * testResolveBundlesOfResolvedBundle: resolveBundles must return true, the specified bundle is resolved
 * </pre>
 *
 * <h3>Expected</h3>
 * A failed resolve leaves the bundles unresolved (INSTALLED) and is reported (false / BundleException); resolveBundles
 * returns true when all specified bundles are resolved, also if a hook threw.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: end() exception: resolveBundles false and the bundle stays INSTALLED, start throws BundleException
 * (REJECTED_BY_HOOK) and the bundle stays INSTALLED; resolveBundles([resolved bundle]) true (end or filterResolvable
 * throwing). Felix 7.0.5 ignores end() exceptions (resolves, true, starts) and returns false for a resolved bundle
 * when filterResolvable throws.
 *
 * <h3>Suggested fix</h3>
 * Call end() before applying the results (the hooks are no longer needed once the wiring map is computed) and apply
 * the results only if no hook failed; in resolveBundles compute the return value from the states of the specified
 * bundles also when a hook exception was caught.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class ResolverHookFailureStateIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFailedResolveLeavesBundleUnresolved() throws Exception {
        Bundle bundle = installBundle(getBundle("hook-failure-resolve"));
        ServiceRegistration<ResolverHookFactory> reg = getSystemContext().registerService(ResolverHookFactory.class, new ThrowingFactory(false, true), null);
        try {
            boolean result = getFramework().adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
            assertEquals("resolveBundles must report the failure of the hook", false, result);
            assertEquals("resolveBundles returned false (a resolver hook threw in end()), so the resolve must have been aborted: state", "INSTALLED", stateName(bundle.getState()));
        } finally {
            reg.unregister();
            bundle.uninstall();
        }
    }

    @Test
    public void testFailedStartLeavesBundleUnresolved() throws Exception {
        Bundle bundle = installBundle(getBundle("hook-failure-start"));
        ServiceRegistration<ResolverHookFactory> reg = getSystemContext().registerService(ResolverHookFactory.class, new ThrowingFactory(false, true), null);
        try {
            try {
                bundle.start();
                fail("start() must fail, a resolver hook threw in end()");
            } catch (BundleException ex) {
                // expected
            }
            assertEquals("start() failed with BundleException (a resolver hook threw in end()), so the resolve must have been aborted: state", "INSTALLED", stateName(bundle.getState()));
        } finally {
            reg.unregister();
            bundle.uninstall();
        }
    }

    @Test
    public void testResolveBundlesOfResolvedBundle() throws Exception {
        Bundle bundle = installBundle(getBundle("hook-failure-resolved"));
        FrameworkWiring frameworkWiring = getFramework().adapt(FrameworkWiring.class);
        assertTrue(frameworkWiring.resolveBundles(Arrays.asList(bundle)));
        ServiceRegistration<ResolverHookFactory> reg = getSystemContext().registerService(ResolverHookFactory.class, new ThrowingFactory(true, false), null);
        try {
            assertEquals(Bundle.RESOLVED, bundle.getState());
            assertTrue("resolveBundles must return true, the specified bundle is resolved", frameworkWiring.resolveBundles(Arrays.asList(bundle)));
        } finally {
            reg.unregister();
            bundle.uninstall();
        }
    }

    static class ThrowingFactory implements ResolverHookFactory {
        private final boolean throwInFilter;
        private final boolean throwInEnd;

        ThrowingFactory(boolean throwInFilter, boolean throwInEnd) {
            this.throwInFilter = throwInFilter;
            this.throwInEnd = throwInEnd;
        }

        public ResolverHook begin(Collection<BundleRevision> triggers) {
            return new ResolverHook() {
                public void filterResolvable(Collection<BundleRevision> candidates) {
                    if (throwInFilter)
                        throw new IllegalStateException("filterResolvable failure");
                }

                public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                }

                public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                }

                public void end() {
                    if (throwInEnd)
                        throw new IllegalStateException("end failure");
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

    private JavaArchive getBundle(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
