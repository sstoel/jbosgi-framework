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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * When a resolver hook throws during the resolution of a dynamic import, the failure is not reported with a framework
 * event of type ERROR; instead the unchecked {@code org.jboss.osgi.resolver.spi.ResolverHookException} escapes from
 * {@code Bundle.loadClass} (which may only throw ClassNotFoundException for a class that cannot be loaded).
 *
 * <h3>Severity</h3>
 * minor - only with a faulty resolver hook; but then any class load that triggers a dynamic import throws an
 * unexpected RuntimeException into application code and the failure is not visible to framework listeners.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader.filterMatches} (hook calls for a
 * dynamic import) and jbosgi-resolver {@code ResolverHookProcessor} (wraps the hook exception into
 * ResolverHookException).
 *
 * <h3>Found by</h3>
 * resolver workstream (compliance matrix, framework.resolverhooks 53.3.2), exploratory program
 * {@code $SP/explore/resolver/fork-hooks/prog/DynHook.java} (mode "throw").
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 53.3.2 Failures
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.resolverhooks.html): "The following are potential
 * failures: [...] A Resolver Hook throws an exception. If the Resolver fails, it must throw a Bundle Exception to the
 * caller if possible. Otherwise it must publish a Framework Event of type ERROR." 53.3.1: "In cases where an exception
 * cannot be propagated to a caller, for example during dynamic import resolving, a Framework Event of type ERROR must
 * be published."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:356-389}
 * ({@code filterMatches}: {@code hookregs.begin/filterMatches/end} are called without catching the
 * ResolverHookException that {@code ResolverHookProcessor.filterMatches} throws), reached from {@code loadClassLocal}
 * (line 129) through {@code findRevisionDynamically}. Pre-existing.
 *
 * <h3>Root cause</h3>
 * The dynamic import path calls the hooks outside of a resolve operation of FrameworkResolverImpl and has no failure
 * handling; the exception travels up through jboss-modules into Bundle.loadClass.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportHookFailureIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testHookFailureDuringDynamicImport: A resolver hook failure during a dynamic import must not escape from
 *   Bundle.loadClass, but loadClass threw org.jboss.osgi.resolver.spi.ResolverHookException:
 *   java.lang.IllegalStateException: hook failure
 * </pre>
 * (and no FrameworkEvent.ERROR is published).
 *
 * <h3>Expected</h3>
 * The dynamic import fails (the class load ends with ClassNotFoundException) and a FrameworkEvent of type ERROR with
 * the hook exception is published.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 also lets a RuntimeException escape from loadClass and publishes no event (same deviation); the
 * statement is a MUST of the specification nevertheless.
 *
 * <h3>Suggested fix</h3>
 * Catch ResolverHookException (and RuntimeExceptions of the hooks) in the dynamic import resolution, publish
 * {@code FrameworkEvent.ERROR} with it, and treat the dynamic import as failed (no wire, return null from the
 * fallback loader so that the class load fails with ClassNotFoundException).
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41.
 *
 * @since 13-Oct-2026
 */
public class DynamicImportHookFailureIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE_A = ObjectA.class.getPackage().getName();

    @Test
    public void testHookFailureDuringDynamicImport() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        final CountDownLatch errorEvent = new CountDownLatch(1);
        FrameworkListener listener = new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR)
                    errorEvent.countDown();
            }
        };
        ServiceRegistration<ResolverHookFactory> registration = null;
        getSystemContext().addFrameworkListener(listener);
        try {
            bundles.add(installBundle(createBundle("hook-failure-exporter", PACKAGE_A, null)));
            final Bundle importer = installBundle(createBundle("hook-failure-importer", null, PACKAGE_A));
            bundles.add(importer);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));

            registration = getSystemContext().registerService(ResolverHookFactory.class, new ResolverHookFactory() {
                public ResolverHook begin(Collection<BundleRevision> triggers) {
                    return new ResolverHook() {
                        public void filterResolvable(Collection<BundleRevision> candidates) {
                        }

                        public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                        }

                        public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                            if (requirement.getRevision().getBundle() == importer)
                                throw new IllegalStateException("hook failure");
                        }

                        public void end() {
                        }
                    };
                }
            }, null);

            try {
                importer.loadClass(ObjectA.class.getName());
            } catch (ClassNotFoundException ex) {
                // expected, the dynamic import failed
            } catch (RuntimeException ex) {
                fail("A resolver hook failure during a dynamic import must not escape from Bundle.loadClass, but loadClass threw " + ex);
            }
            assertTrue("A resolver hook failure during a dynamic import must publish a FrameworkEvent of type ERROR", errorEvent.await(5, TimeUnit.SECONDS));
        } finally {
            if (registration != null)
                registration.unregister();
            getSystemContext().removeFrameworkListener(listener);
            for (Bundle bundle : bundles)
                bundle.uninstall();
        }
    }

    private JavaArchive createBundle(final String name, final String exports, final String dynamicImports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (exports != null)
            archive.addClasses(ObjectA.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (exports != null)
                    builder.addExportPackages(exports);
                if (dynamicImports != null)
                    builder.addDynamicImportPackages(dynamicImports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
