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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.bundle.support.a.ObjectA;
import org.jboss.test.osgi.framework.bundle.support.b.ObjectB;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A dynamic import does not follow the provider selection rules and ignores uses constraints: of several exporters
 * the bundle with the highest bundle id wins (not the highest package version, not the lowest bundle id), and an
 * exporter whose uses constraints conflict with the importer's class space is chosen. The resolver hooks are not
 * called as for one resolve operation either: every candidate exporter gets its own begin/filterMatches/end cycle
 * with a one-element collection.
 *
 * <h3>Severity</h3>
 * major - a bundle with DynamicImport-Package (very common: {@code DynamicImport-Package: *} in integration
 * bundles) gets an older package version than its static importers, or an exporter whose class space is
 * inconsistent with its own, which leads to LinkageError/ClassCastException at run time.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FallbackLoader} (dynamic import resolution).
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential of class loading scenarios (DynamicImport-Package with two exporters).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.9.2 Dynamic Import Package
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module.dynamicimportpackage):
 * "Each matching package name is used in turn to attempt to wire to an export using the same rules as
 * Import-Package. If a wiring attempt is successful (taking any uses constraints into account), the search is
 * forwarded to the exporter's class loader". 3.9.4 Overall Search Order, step 7: "a dynamic import of the package is
 * now attempted. An exporter must conform to any implied package constraints." 3.8 Resolving Process (the rules for
 * Import-Package): "The following list defines the preferences, if multiple choices are possible, in order of
 * decreasing priority: A resolved exporter must be preferred over an unresolved exporter. An exporter with a higher
 * version is preferred over an exporter with a lower version. An exporter with a lower bundle ID is preferred over a
 * bundle with a higher ID."
 * Resolver hooks (testResolverHooksCalledOncePerDynamicImport): 53.2.1 lists "Dynamic Import - A Dynamic Import can
 * require the framework to start a resolve operation." 53.3 The Resolve Operation: "For each bundle revision B in U
 * which the Resolver attempts to resolve the following steps must be followed: For each requirement R(equirement)
 * specified by bundle revision B, determine the collection of capabilities that satisfy the requirement R and place
 * each matching capability into a shrinkable collection C(apabilities). [...] For each Resolver Hook in H, call the
 * filterMatches(BundleRequirement,Collection) method with the Bundle Requirement R and the shrinkable collection C."
 * and "This is true for all methods except the begin and end methods that are only called once during a resolve
 * operation." (same rule in the ResolverHook interface javadoc, 53.5.2).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FallbackLoader.java:308-353}
 * ({@code findInResolvedRevisions}). Pre-existing, not touched by the R7/R8 commits (the R7 commit added
 * {@code Resolver.resolveDynamic} to jbosgi-resolver, but the framework does not use it).
 *
 * <h3>Root cause</h3>
 * {@code findInResolvedRevisions} collects every resolved bundle with a matching package capability and sorts them
 * with a comparator that compares the bundle version when the symbolic names are equal and otherwise prefers the
 * HIGHER bundle id; the package version is never compared and uses constraints are not checked at all (the Felix
 * resolver, which checks them for static imports, is bypassed). For the hooks, {@code FallbackLoader.filterMatches}
 * ({@code FallbackLoader.java:356-389}, called per candidate from {@code getCapabilityCandidate}, line 464) creates a
 * new {@code ResolverHookProcessor} and runs begin, filterMatches with a collection that only holds the one candidate,
 * and end, for each candidate bundle separately - again because the dynamic import is not one resolve operation of
 * the resolver.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=DynamicImportProviderSelectionIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testHighestVersionPreferred: The dynamic import must be wired to the exporter of the highest version
 *   expected:&lt;dynimport-v2[:0.0.0]&gt; but was:&lt;dynimport-v1[:0.0.0]&gt;
 * testLowestBundleIdPreferred: With equal versions the dynamic import must be wired to the exporter with the
 *   lowest bundle id expected:&lt;dynimport-first[:0.0.0]&gt; but was:&lt;dynimport-second[:0.0.0]&gt;
 * testUsesConstraintRespected: The dynamic import must be wired to the exporter that is consistent with the uses
 *   constraint on package org.jboss.test.osgi.framework.bundle.support.b expected:&lt;dynimport-uses-v1[:0.0.0]&gt;
 *   but was:&lt;dynimport-uses-v2[:0.0.0]&gt;
 * testResolverHooksCalledOncePerDynamicImport: A dynamic import is one resolve operation: begin must be called once,
 *   calls were [begin, filterMatches:1, end, begin, filterMatches:1, end] expected:&lt;1&gt; but was:&lt;2&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The same exporter that a static import would get: highest package version, then lowest bundle id, and never an
 * exporter whose uses constraints conflict with the importer's wiring. A resolver hook sees one begin, one
 * filterMatches call for the dynamic requirement with all matching candidates (here both exporters), and one end.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 wires the dynamic import to the highest version (a), to the lowest bundle id on a tie, and to the
 * consistent exporter in the uses scenario. For the hooks it calls begin once, filterResolvable with an empty
 * collection, filterMatches once with all candidates and end once ($SP/explore/resolver/fork-hooks/dyn2, three
 * exporters: jbosgi calls begin three times and filterMatches three times with one capability each). The WF41 branch
 * of jbosgi behaves like the R8 branch.
 *
 * <h3>Suggested fix</h3>
 * Resolve dynamic imports through the resolver: build the ResolveContext for the host wiring and call
 * {@code Resolver.resolveDynamic(context, hostWiring, dynamicRequirement)} (now available, Felix resolver 2.0.4),
 * which orders candidates via {@code ResolveContext.findProviders} and checks uses constraints; apply the returned
 * wires. At the very least sort the candidates with the framework preference comparator (package version
 * descending, then bundle id ascending). With resolveDynamic inside one ResolverHookProcessor begin/end, the hooks are
 * called once per dynamic import with all candidates (AbstractEnvironment.findProviders already calls filterMatches
 * with the full candidate collection).
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour on WF41; still a spec violation on the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class DynamicImportProviderSelectionIssueTestCase extends OSGiFrameworkTest {

    private static final String PACKAGE_A = ObjectA.class.getPackage().getName();
    private static final String PACKAGE_B = ObjectB.class.getPackage().getName();

    @Test
    public void testHighestVersionPreferred() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            Bundle v2 = install(bundles, "dynimport-v2", PACKAGE_A + ";version=2.0", null, ObjectA.class);
            install(bundles, "dynimport-v1", PACKAGE_A + ";version=1.0", null, ObjectA.class);
            Bundle importer = install(bundles, "dynimport-importer", null, null, null, PACKAGE_A);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
            importer.loadClass(ObjectA.class.getName());
            assertEquals("The dynamic import must be wired to the exporter of the highest version", v2, getProvider(importer, PACKAGE_A));
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testLowestBundleIdPreferred() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            Bundle first = install(bundles, "dynimport-first", PACKAGE_A + ";version=1.0", null, ObjectA.class);
            install(bundles, "dynimport-second", PACKAGE_A + ";version=1.0", null, ObjectA.class);
            Bundle importer = install(bundles, "dynimport-importer", null, null, null, PACKAGE_A);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
            importer.loadClass(ObjectA.class.getName());
            assertEquals("With equal versions the dynamic import must be wired to the exporter with the lowest bundle id", first, getProvider(importer, PACKAGE_A));
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testUsesConstraintRespected() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        try {
            // Two versions of package b
            install(bundles, "dynimport-b2", PACKAGE_B + ";version=2.0", null, ObjectB.class);
            install(bundles, "dynimport-b1", PACKAGE_B + ";version=1.0", null, ObjectB.class);
            // Two exporters of package a that use package b, each wired to another version of b
            Bundle consistent = install(bundles, "dynimport-uses-v1", PACKAGE_A + ";version=1.0;uses:=\"" + PACKAGE_B + "\"", PACKAGE_B + ";version=\"[1,2)\"", ObjectA.class);
            install(bundles, "dynimport-uses-v2", PACKAGE_A + ";version=2.0;uses:=\"" + PACKAGE_B + "\"", PACKAGE_B + ";version=\"[2,3)\"", ObjectA.class);
            // The importer sees b 1.0, so it can only use the exporter of a that is wired to b 1.0
            Bundle importer = install(bundles, "dynimport-uses-importer", null, PACKAGE_B + ";version=\"[1,2)\"", null, PACKAGE_A);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));
            importer.loadClass(ObjectA.class.getName());
            assertEquals("The dynamic import must be wired to the exporter that is consistent with the uses constraint on package " + PACKAGE_B, consistent, getProvider(importer, PACKAGE_A));
        } finally {
            uninstall(bundles);
        }
    }

    @Test
    public void testResolverHooksCalledOncePerDynamicImport() throws Exception {
        List<Bundle> bundles = new ArrayList<Bundle>();
        final List<String> calls = new CopyOnWriteArrayList<String>();
        ServiceRegistration<ResolverHookFactory> registration = null;
        try {
            install(bundles, "dynimport-hook-exporter1", PACKAGE_A + ";version=1.0", null, ObjectA.class);
            install(bundles, "dynimport-hook-exporter2", PACKAGE_A + ";version=1.0", null, ObjectA.class);
            final Bundle importer = install(bundles, "dynimport-hook-importer", null, null, null, PACKAGE_A);
            assertTrue(getFramework().adapt(FrameworkWiring.class).resolveBundles(bundles));

            registration = getSystemContext().registerService(ResolverHookFactory.class, new ResolverHookFactory() {
                public ResolverHook begin(Collection<BundleRevision> triggers) {
                    calls.add("begin");
                    return new ResolverHook() {
                        public void filterResolvable(Collection<BundleRevision> candidates) {
                        }

                        public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                        }

                        public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                            if (requirement.getRevision().getBundle() == importer)
                                calls.add("filterMatches:" + candidates.size());
                        }

                        public void end() {
                            calls.add("end");
                        }
                    };
                }
            }, null);

            importer.loadClass(ObjectA.class.getName());
            assertEquals("A dynamic import is one resolve operation: begin must be called once, calls were " + calls, 1, count(calls, "begin"));
            assertEquals("filterMatches must get all matching candidates of the dynamic requirement at once, calls were " + calls, 1, count(calls, "filterMatches:2"));
            assertEquals("A dynamic import is one resolve operation: end must be called once, calls were " + calls, 1, count(calls, "end"));
        } finally {
            if (registration != null)
                registration.unregister();
            uninstall(bundles);
        }
    }

    private int count(List<String> calls, String call) {
        int result = 0;
        for (String aux : calls)
            result += aux.equals(call) ? 1 : 0;
        return result;
    }

    private Bundle getProvider(Bundle importer, String packageName) {
        for (BundleWire wire : importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (packageName.equals(wire.getCapability().getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE)))
                return wire.getProvider().getBundle();
        }
        return null;
    }

    private Bundle install(List<Bundle> bundles, String name, String exports, String imports, Class<?> content, String... dynamicImports) throws Exception {
        Bundle bundle = installBundle(createBundle(name, exports, imports, content, dynamicImports));
        bundles.add(bundle);
        return bundle;
    }

    private void uninstall(List<Bundle> bundles) throws Exception {
        for (Bundle bundle : bundles)
            bundle.uninstall();
    }

    private JavaArchive createBundle(final String name, final String exports, final String imports, Class<?> content, final String... dynamicImports) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        if (content != null)
            archive.addClasses(content);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                if (exports != null)
                    builder.addManifestHeader("Export-Package", exports);
                if (imports != null)
                    builder.addManifestHeader("Import-Package", imports);
                if (dynamicImports.length > 0)
                    builder.addDynamicImportPackages(dynamicImports);
                return builder.openStream();
            }
        });
        return archive;
    }
}
