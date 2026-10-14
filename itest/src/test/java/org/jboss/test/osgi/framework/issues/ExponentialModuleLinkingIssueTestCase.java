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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * Resolving bundles whose imports form a layered graph with diamonds takes exponential time: every Import-Package
 * dependency of a bundle module re-exports the paths the importing bundle exports, so jboss-modules walks every path
 * through the dependency graph when it links a module. A chain of N bundles where bundle i imports the packages of
 * bundles i-1 and i-2 needs ~1.6^N steps: 12 bundles 32 ms, 20 bundles 0.5 s, 24 bundles 4.5 s, 40 bundles more than
 * 2 minutes.
 *
 * <h3>Severity</h3>
 * critical - a moderately deep import graph (every bundle importing from two bundles of the layer below, a very
 * common shape: API, implementation, integration layers) makes resolving (bundle start, deployment) practically hang
 * the framework; the time doubles with every two added layers. This is a scalability defect, not a regression: the
 * WF41 (R6) build has exactly the same curve (WF41 24 bundles 3.9 s, R8 4.5 s).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ModuleManagerImpl} ({@code ModuleDependencyHolder.create}),
 * in combination with jboss-modules {@code Module.addExportedPaths} (link time).
 *
 * <h3>Found by</h3>
 * resolver workstream, performance/scalability round (R6 vs R8 comparison): randomized install+resolve of 100+
 * bundles did not finish on either build; jstack showed {@code Module.addExportedPaths} recursing hundreds of levels;
 * reduced to the "diamond chain" ($SP/agents/resolver/perf/src/Perf.java, scenario {@code chain}, CHAIN=diamond).
 *
 * <h3>Spec reference</h3>
 * Not a spec clause; scalability. OSGi Core R8 3.6.4/3.9.4: an imported package is not re-exported by the importer
 * (only Require-Bundle with visibility:=reexport re-exports), so an import dependency has nothing to export except a
 * substituted package (3.6.6/3.8.1: a package both imported and exported, whose export is replaced by the import).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:626-635}
 * ({@code ModuleDependencyHolder.create}: when no export filter was set - always for Import-Package dependencies - the
 * export filter of the module dependency becomes {@code PathFilters.in(<all packages the importing host exports>)}),
 * used for every dependency built in {@code processModuleWireList} (:398-408). Pre-existing (same on WF41).
 * Measurements: $SP/agents/resolver/perf/RESULTS.md.
 *
 * <h3>Root cause</h3>
 * Because each import dependency carries a non-trivial export filter, jboss-modules must compute the exported paths
 * of the dependency module transitively (Module.addExportedPaths recurses into the dependencies of the dependency,
 * without memoization across paths), so linking visits every path of the dependency DAG - exponentially many for a
 * graph with diamonds.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ExponentialModuleLinkingIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}). The test compares the resolve time of a 22-bundle diamond chain with that
 * of an 11-bundle chain (machine independent: a ratio, linear behaviour gives ~2-4, the defect gives &gt; 50).
 *
 * <h3>Observed</h3>
 * <pre>
 * testDiamondChainResolvesInLinearTime: Resolving a 22 bundle diamond chain must take about twice as long as an 11
 *   bundle chain, but took 3507 ms vs 24 ms (ratio 145, exponential growth)
 * </pre>
 * With the trial fix described below the same test passes (22 vs 11 bundles: ratio ~2).
 *
 * <h3>Expected</h3>
 * Resolve time roughly proportional to the number of bundles/wires (Equinox 3.21: 20 bundles 7 ms, 40 bundles 10 ms,
 * 200 bundles 30 ms).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 is linear on the same chains (see above).
 *
 * <h3>Suggested fix</h3>
 * For Import-Package dependencies only re-export the packages that the importing host also exports (the substituted
 * packages, needed so that a bundle that requires the host sees them): export filter =
 * {@code PathFilters.in(hostExportedPaths ∩ importPaths)}, or {@code PathFilters.rejectAll()} when that intersection
 * is empty (the normal case). Verified with a trial patch ($SP/agents/resolver/perf/trial-noreexport.diff, JDK 25):
 * diamond chains become linear (24 bundles 17 ms, 200 bundles 78 ms), a randomized layered graph of 500 bundles with
 * fragments resolves in 157 ms, and the whole framework itest suite has no new failure (a plain rejectAll breaks
 * BundleWiringTestCase.testListResourcesWithRequiredBundle, which relies on the substituted package being re-exported).
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 shows the same exponential curve.
 *
 * @since 13-Oct-2026
 */
public class ExponentialModuleLinkingIssueTestCase extends AbstractFrameworkLaunchTest {

    @Test
    public void testDiamondChainResolvesInLinearTime() throws Exception {
        // warm up
        resolveDiamondChain(11);
        long small = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++)
            small = Math.min(small, resolveDiamondChain(11));
        long large = resolveDiamondChain(22);
        double ratio = (double) large / Math.max(small, 1000000L);
        assertTrue("Resolving a 22 bundle diamond chain must take about twice as long as an 11 bundle chain, but took " + (large / 1000000) + " ms vs "
                + (small / 1000000) + " ms (ratio " + Math.round(ratio) + ", exponential growth)", ratio < 15);
    }

    /**
     * Bundle i exports gen.p<i> and imports gen.p<i-1> and gen.p<i-2>. Returns the nanoseconds of resolveBundles(null).
     */
    private long resolveDiamondChain(int n) throws Exception {
        Framework framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        try {
            for (int i = 0; i < n; i++) {
                String imports = i == 0 ? null : i == 1 ? "gen.p0" : "gen.p" + (i - 1) + ",gen.p" + (i - 2);
                framework.getBundleContext().installBundle("chain" + i, new ByteArrayInputStream(createBundle(i, imports)));
            }
            long start = System.nanoTime();
            boolean resolved = framework.adapt(FrameworkWiring.class).resolveBundles(null);
            long time = System.nanoTime() - start;
            assertTrue("All bundles of the chain resolve", resolved);
            return time;
        } finally {
            framework.stop();
            framework.waitForStop(30000);
        }
    }

    private byte[] createBundle(int i, String imports) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "chain" + i);
        attributes.putValue(Constants.EXPORT_PACKAGE, "gen.p" + i);
        if (imports != null)
            attributes.putValue(Constants.IMPORT_PACKAGE, imports);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return baos.toByteArray();
    }
}
