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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.InputStream;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * The bundle class loader returns a native library whose file name is not {@code System.mapLibraryName(name)}: it
 * matches any library of the selected Bundle-NativeCode clause whose file name, without the last extension and without
 * a "lib" prefix, equals the requested name, and it ignores org.osgi.framework.library.extensions.
 *
 * <h3>Severity</h3>
 * minor - edge-case spec deviation. {@code System.loadLibrary("x")} can be handed a file the platform cannot load
 * (e.g. {@code libx.a} or {@code libx.so} on macOS) instead of falling back to the parent class loader /
 * {@code java.library.path}; the launching property org.osgi.framework.library.extensions has no effect at all.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ModuleManagerImpl#addNativeResourceLoader} and
 * {@code NativeResourceLoader#getLibrary}.
 *
 * <h3>Found by</h3>
 * compendium-gaps workstream (closing UNVERIFIED row framework.api#126 of the spec matrix; the TCK's
 * FrameworkLaunchTests.testLibraryExtensions cannot run on macOS for any framework), Equinox/Felix differential probe
 * {@code explore/compendium-gaps/h/hsrc/FwHarness.java} ({@code lib}): {@code findLibrary("probe")} for a clause
 * {@code lib/libprobe.a} returns {@code .../lib/libprobe.a} on jbosgi with and without
 * {@code org.osgi.framework.library.extensions=a}; Equinox 3.18.600 and Felix 7.0.5 return null without the property
 * and the path only with {@code library.extensions=a}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 10.1.15.62 {@code Constants.FRAMEWORK_LIBRARY_EXTENSIONS}
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.Constants.FRAMEWORK_LIBRARY_EXTENSIONS):
 * "Framework launching property specifying a comma separated list of additional library file extensions that must be
 * used when a bundle's class loader is searching for native libraries. If this property is not set, then only the
 * library name returned by System.mapLibraryName(String) will be used to search." (same text in 4.2.2 Launching
 * Properties). 3.10 Loading Native Code Libraries: "The bundle class loader can use the mapLibraryName method from the
 * VM to map this name to an operating system dependent name, like libhttp.so. ... If the library is not referenced in
 * any of the selected native code clauses then null must be returned which allows the parent class loader to search
 * for the native library." (matrix ids framework.api#126, framework.module#162)
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/ModuleManagerImpl.java:529-543}: the
 * library name is derived from the file name ({@code libfile.substring(0, libfile.lastIndexOf('.'))}, registered once
 * with and once without a "lib" prefix); {@code NativeResourceLoader.java:61-84} compares the requested name with
 * these derived names. {@code System.mapLibraryName} and {@code Constants.FRAMEWORK_LIBRARY_EXTENSIONS} are not used
 * anywhere in jbosgi. Introduced by e10f814f (2010, "Resurect native code handling"), unchanged since.
 *
 * <h3>Root cause</h3>
 * Library lookup by a name derived from the file name instead of mapping the requested name with
 * {@code System.mapLibraryName} (plus the configured extra extensions) and comparing it with the file names of the
 * selected clauses.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NativeLibraryNameMappingIssueTestCase} (in jbosgi-framework, after
 * {@code mvn -Dmaven.compiler.proc=full install -DskipTests}).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: findLibrary("probe") must not return lib/libprobe.a: org.osgi.framework.library.extensions
 *   is not set and System.mapLibraryName("probe") is libprobe.dylib, so the class loader must return null
 *   (UnsatisfiedLinkError 'no probe in java.library.path'), but System.loadLibrary was handed the bundle's file:
 *   .../itest/target/osgi-store/bundle-1/lib/libprobe.a: dlopen(.../lib/libprobe.a, 0x0001): tried:
 *   '.../lib/libprobe.a' (slice is not valid mach-o file), ...
 * </pre>
 *
 * <h3>Expected</h3>
 * With org.osgi.framework.library.extensions unset, {@code findLibrary("probe")} only looks for
 * {@code System.mapLibraryName("probe")} (libprobe.dylib on macOS, libprobe.so on Linux, probe.dll on Windows), which
 * the clause does not contain, so it returns null and {@code System.loadLibrary} fails with "no probe in
 * java.library.path" (or loads a library from java.library.path).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.600 and Felix 7.0.5 (JDK 25, macOS aarch64): {@code findLibrary("probe")} returns null without the
 * property and {@code .../lib/libprobe.a} with {@code org.osgi.framework.library.extensions=a}.
 *
 * <h3>Suggested fix</h3>
 * In {@code NativeResourceLoader.getLibrary(name)} compute {@code System.mapLibraryName(name)} and, for each extension
 * of {@code org.osgi.framework.library.extensions}, the same name with that extension; return the first library of the
 * selected clauses (host first, then fragments by bundle id) whose file name (last path segment) equals one of them.
 * Register libraries by file name instead of a derived "library name".
 *
 * <h3>Regression vs WF41</h3>
 * no - identical code on WF41 (pre-existing).
 *
 * @since 14-Oct-2026
 */
public class NativeLibraryNameMappingIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testOnlyMappedLibraryNameIsUsedWithoutExtensionsProperty() throws Exception {
        assumeTrue("libprobe.a is the mapped name on this OS", !"libprobe.a".equals(System.mapLibraryName("probe")));
        BundleContext context = getSystemContext();
        assumeTrue("the test framework does not set " + Constants.FRAMEWORK_LIBRARY_EXTENSIONS,
                context.getProperty(Constants.FRAMEWORK_LIBRARY_EXTENSIONS) == null);
        final String nativeCode = "lib/libprobe.a;osname=\"" + context.getProperty(Constants.FRAMEWORK_OS_NAME) + "\";processor=\""
                + context.getProperty(Constants.FRAMEWORK_PROCESSOR) + "\"";
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "native-name-mapping");
        archive.addClasses(LoadLibraryActivator.class);
        archive.add(new StringAsset("not a native library"), "lib/libprobe.a");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(LoadLibraryActivator.class);
                builder.addImportPackages(BundleActivator.class);
                builder.addManifestHeader(Constants.BUNDLE_NATIVECODE, nativeCode);
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            try {
                bundle.start();
                fail("System.loadLibrary(\"probe\") must fail: neither " + System.mapLibraryName("probe")
                        + " in the bundle nor a probe library on java.library.path exists");
            } catch (BundleException ex) {
                Throwable cause = ex.getCause();
                assertNotNull("Precondition: activator failure has a cause", cause);
                assertTrue("Precondition: UnsatisfiedLinkError expected, but was " + cause, cause instanceof UnsatisfiedLinkError);
                String message = String.valueOf(cause.getMessage());
                assertTrue("findLibrary(\"probe\") must not return lib/libprobe.a: " + Constants.FRAMEWORK_LIBRARY_EXTENSIONS
                        + " is not set and System.mapLibraryName(\"probe\") is " + System.mapLibraryName("probe")
                        + ", so the class loader must return null (UnsatisfiedLinkError 'no probe in java.library.path'),"
                        + " but System.loadLibrary was handed the bundle's file: " + message, !message.contains("libprobe.a"));
            }
        } finally {
            bundle.uninstall();
        }
    }

    public static class LoadLibraryActivator implements BundleActivator {
        public void start(BundleContext context) throws Exception {
            System.loadLibrary("probe");
        }

        public void stop(BundleContext context) throws Exception {
        }
    }
}
