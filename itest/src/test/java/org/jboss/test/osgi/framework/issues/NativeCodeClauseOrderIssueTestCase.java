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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleWiring;

/**
 * When several Bundle-NativeCode clauses match the platform, the framework neither sorts them (osversion floor,
 * language, position) nor uses only the first one: the libraries of all matching clauses are made available, and a
 * library name that occurs in several clauses always comes from the leftmost clause.
 *
 * <h3>Severity</h3>
 * minor. A bundle that ships a generic library and an optimized one for newer OS versions
 * ({@code lib/generic/libx.so; osname=Linux, lib/v5/libx.so; osname=Linux; osversion=5.0}) loads the generic one;
 * libraries of clauses that were not selected can be loaded too.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.NativeCodeImpl.resolveNativeCode}.
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness ({@code explore/manifest}, cases {@code nco-osversion},
 * {@code nco-floor}, {@code nco-language}, {@code nco-single}, using the class loader's findLibrary), resolving the
 * UNVERIFIED matrix entries framework.module#180.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10.1 Native Code Algorithm (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html):
 * "The selected clauses are now sorted in the following priority order: osversion: floor of the osversion range in
 * descending order, osversion not specified; language: language specified, language not specified; Position in
 * the Bundle-NativeCode manifest header: lexical left to right. The first clause of the sorted clauses from step 3
 * must be used as the selected native code clause."<br>
 * 3.10: "The bundle's class loader must attempt to find the native library by examining the selected native code
 * clauses ... If the library is not referenced in any of the selected native code clauses then null must be
 * returned".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:225-235}: the comment lists the
 * sort order, followed by "[TODO] sort the clauses", then every matching clause's library is added
 * (since ab09755c, 2011).
 *
 * <h3>Root cause</h3>
 * Step 3 and 4 of the algorithm are not implemented.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=NativeCodeClauseOrderIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testOSVersionClauseBeforeClauseWithoutOSVersion: For 'Bundle-NativeCode: lib/one/libfoo.so; osname=..., lib/two/
 *   libfoo.so; ...; osversion="1.0"' the library of the clause that sorts first (lib/two) must be used, but
 *   .../bundle-N/lib/one/libfoo.so was used expected:&lt;[two]&gt; but was:&lt;[one]&gt;
 * testHigherOSVersionFloorFirst: ... osversion="1.0", ... osversion="2.0"' ... (lib/two) ... but .../lib/one/libfoo.so
 * testLanguageClauseBeforeClauseWithoutLanguage: ... language="en"' ... (lib/two) ... but .../lib/one/libfoo.so
 * testOnlyTheSelectedClauseIsUsed: Only the first clause of the sorted clauses is used, libbar of the second clause
 *   must not be found expected null, but was:&lt;.../bundle-N/lib/two/libbar.so&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * lib/two is used in the first three cases; findLibrary("bar") returns null in the fourth.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 implements the osversion ordering and the single selected clause (lib/two, lib/two, null for bar)
 * but not the language step; Felix 7.0.5 implements the osversion step and the single clause, not the language
 * step. The spec text is explicit for all three steps.
 *
 * <h3>Suggested fix</h3>
 * Sort the matched clauses (stable) by osversion floor descending with unspecified osversion last, then clauses
 * with a language first, and register only the libraries of the first clause (a clause is the group of paths
 * that share one parameter set). Verified: with that change the four test methods pass and NativeCodeTestCase and
 * NativeNamespaceTestCase still pass (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class NativeCodeClauseOrderIssueTestCase extends OSGiFrameworkTest {

    // The clauses name the mapped library file names of the running platform: the class loader finds a library by the
    // name that System.mapLibraryName maps the requested name to (compendium/02)
    private static final String FOO = System.mapLibraryName("foo");
    private static final String BAR = System.mapLibraryName("bar");

    @Test
    public void testOSVersionClauseBeforeClauseWithoutOSVersion() throws Exception {
        // 3.10.1 step 3: osversion specified (floor descending) before osversion not specified
        assertSelected("native-order-osversion", "lib/one/" + FOO + "; " + platform() + ", lib/two/" + FOO + "; " + platform() + "; osversion=\"1.0\"", "foo", "two");
    }

    @Test
    public void testHigherOSVersionFloorFirst() throws Exception {
        assertSelected("native-order-floor", "lib/one/" + FOO + "; " + platform() + "; osversion=\"1.0\", lib/two/" + FOO + "; " + platform() + "; osversion=\"2.0\"", "foo", "two");
    }

    @Test
    public void testLanguageClauseBeforeClauseWithoutLanguage() throws Exception {
        String language = getSystemContext().getProperty(Constants.FRAMEWORK_LANGUAGE);
        assertSelected("native-order-language", "lib/one/" + FOO + "; " + platform() + ", lib/two/" + FOO + "; " + platform() + "; language=\"" + language + "\"", "foo", "two");
    }

    @Test
    public void testOnlyTheSelectedClauseIsUsed() throws Exception {
        // The first clause is selected, the library of the second matching clause must not be found
        Bundle bundle = installAndStart("native-order-single", "lib/one/" + FOO + "; " + platform() + ", lib/two/" + BAR + "; " + platform());
        try {
            assertNotNull("libfoo of the selected (first) clause is found", findLibrary(bundle, "foo"));
            assertNull("Only the first clause of the sorted clauses is used, libbar of the second clause must not be found", findLibrary(bundle, "bar"));
        } finally {
            bundle.uninstall();
        }
    }

    private void assertSelected(String name, String nativeCode, String libname, String expected) throws Exception {
        Bundle bundle = installAndStart(name, nativeCode);
        try {
            String path = findLibrary(bundle, libname);
            assertNotNull("lib" + libname + " is found", path);
            String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
            assertEquals("For 'Bundle-NativeCode: " + nativeCode + "' the library of the clause that sorts first (lib/" + expected + ") must be used, but " + path + " was used",
                    expected, content);
        } finally {
            bundle.uninstall();
        }
    }

    private String platform() throws Exception {
        return "osname=\"" + getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME) + "\"; processor=\"" + getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR) + "\"";
    }

    private String findLibrary(Bundle bundle, String libname) throws Exception {
        ClassLoader loader = bundle.adapt(BundleWiring.class).getClassLoader();
        Method method = null;
        for (Class<?> clazz = loader.getClass(); method == null && clazz != null; clazz = clazz.getSuperclass()) {
            try {
                method = clazz.getDeclaredMethod("findLibrary", String.class);
            } catch (NoSuchMethodException ex) {
                // continue
            }
        }
        method.setAccessible(true);
        return (String) method.invoke(loader, libname);
    }

    private Bundle installAndStart(String name, final String nativeCode) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new StringAsset("one"), "lib/one/" + System.mapLibraryName("foo"));
        archive.add(new StringAsset("two"), "lib/two/" + System.mapLibraryName("foo"));
        archive.add(new StringAsset("two"), "lib/two/" + System.mapLibraryName("bar"));
        archive.add(new StringAsset("one"), "lib/one/libfoo.so");
        archive.add(new StringAsset("two"), "lib/two/libfoo.so");
        archive.add(new StringAsset("two"), "lib/two/libbar.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        bundle.start();
        return bundle;
    }
}
