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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

/**
 * Two Bundle-NativeCode clauses without parameters are taken as one clause: the libraries of both clauses are offered,
 * not only those of the selected clause.
 *
 * <h2>Severity</h2>
 * minor. A bundle that lists alternative libraries as clauses ({@code lib/a.so, lib/b.so}) gets both of them; with
 * selection attributes on one of the clauses it gets only the libraries of the selected clause.
 *
 * <h2>Component</h2>
 * jbosgi-metadata, {@code ManifestParser} (the clauses of the header), and jbosgi-framework, core,
 * {@code NativeCodeImpl.resolveNativeCode}, which groups the paths of the header into clauses by their attributes.
 *
 * <h2>Found by</h2>
 * R8 fix review, confirmed by test (fw-minor-B04/C02).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 3.10.1 Native Code Algorithm: of the clauses that match, the first one is selected, "the native code
 * clause selected in step 5 is used".
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:212 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The paths of a clause share its attribute map, and the framework takes paths with the same map as one clause. The
 * clauses without parameters all had the same empty map.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=NativeCodeParameterlessClausesIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * Only the library of the first clause is offered, the paths of one clause are all offered.
 *
 * <h2>Regression vs WF41</h2>
 * no (before dc5d2881 such a bundle did not resolve at all).
 */
public class NativeCodeParameterlessClausesIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testFirstOfTwoClausesWithoutParameters() throws Exception {
        String result = loadLibraries("native-two-clauses", "lib/" + library("ncpa") + ", lib/" + library("ncpb"), "ncpa", "ncpb");
        assertTrue("The library of the first clause is offered: " + result, offered(result, "ncpa"));
        assertFalse("The library of the second clause is not offered: " + result, offered(result, "ncpb"));
    }

    @Test
    public void testPathsOfOneClauseWithoutParameters() throws Exception {
        String result = loadLibraries("native-one-clause", "lib/" + library("ncpc") + "; lib/" + library("ncpd"), "ncpc", "ncpd");
        assertTrue("The first library of the clause is offered: " + result, offered(result, "ncpc"));
        assertTrue("The second library of the clause is offered: " + result, offered(result, "ncpd"));
    }

    private static String library(String name) {
        return System.mapLibraryName(name);
    }

    // A library is offered when it was loaded or when loading the file that the bundle offered failed
    private static boolean offered(String result, String name) {
        String outcome = System.getProperty(LoadLibraryActivator.PREFIX + name);
        return outcome != null && (outcome.equals("loaded") || outcome.contains(library(name)));
    }

    private String loadLibraries(final String name, final String nativeCode, final String... libraries) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(LoadLibraryActivator.class);
        for (String library : libraries) {
            System.clearProperty(LoadLibraryActivator.PREFIX + library);
            archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/" + library(library));
        }
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                builder.addBundleActivator(LoadLibraryActivator.class);
                builder.addImportPackages(BundleActivator.class);
                builder.addManifestHeader(LoadLibraryActivator.HEADER, String.join(",", libraries));
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            bundle.start();
            StringBuilder result = new StringBuilder();
            for (String library : libraries)
                result.append(library).append(": ").append(System.getProperty(LoadLibraryActivator.PREFIX + library)).append("; ");
            return result.toString();
        } finally {
            bundle.uninstall();
        }
    }

    /** Loads the libraries that the header Issues-Libraries names, the outcome goes to system properties */
    public static class LoadLibraryActivator implements BundleActivator {

        static final String HEADER = "Issues-Libraries";
        static final String PREFIX = "issues.native.clauses.";

        public void start(BundleContext context) {
            for (String name : context.getBundle().getHeaders().get(HEADER).split(",")) {
                String result;
                try {
                    System.loadLibrary(name);
                    result = "loaded";
                } catch (Throwable th) {
                    result = String.valueOf(th.getMessage());
                }
                System.setProperty(PREFIX + name, result);
            }
        }

        public void stop(BundleContext context) {
        }
    }
}
