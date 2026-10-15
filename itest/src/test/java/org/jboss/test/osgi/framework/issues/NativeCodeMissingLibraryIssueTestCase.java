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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A native code library that is listed in the selected Bundle-NativeCode clause but missing from the bundle fails
 * the resolve, and FrameworkWiring.resolveBundles throws the IllegalStateException of that failure.
 *
 * <h3>Severity</h3>
 * minor - R7/R8 behaviour change not implemented, plus an unchecked exception out of resolveBundles that its
 * contract does not allow.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.NativeCodeImpl.NativeLibraryProviderImpl}
 * (called from {@code ModuleManagerImpl.addNativeResourceLoader} during {@code FrameworkResolverImpl.applyResolverResults}).
 *
 * <h3>Found by</h3>
 * fuzz workstream, manifest install fuzzer (Jazzer, deep mode): input
 * {@code Bundle-NativeCode: lib/a.so;osnax/A.txtx;osname=MacOSX;processor=x86;processor=aarch64;...} made
 * {@code FrameworkWiring.resolveBundles} throw {@code IllegalStateException: JBOSGI011223: Cannot find native library: osnax/A.txtx}.
 * Equinox 3.21 resolves and starts the same bundle.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10.1 Native Code Algorithm
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module-native.code.algorithm):
 * "If a native code library in a selected native code clause cannot be found within the bundle then the bundle is
 * still allowed to resolve. A missing native code library will result in an error being thrown at runtime when the
 * bundle attempts to load the native code (for example, by invoking the method System.loadLibrary)."
 * (changed in R7; R6 required the resolve to fail).
 * FrameworkWiring.resolveBundles javadoc: "Returns true if all specified bundles are resolved; false otherwise.
 * Throws IllegalArgumentException - If the specified Bundles were not created by the same framework instance".
 *
 * <h3>Spec ambiguity (added by the coordinator)</h3>
 * Core R8 (and R7) contradicts itself here: 3.10 says "Any paths specified in the matching clause must be present in
 * the bundle or any of its attached fragments for a bundle to resolve", while 3.10.1 says "If a native code library in
 * a selected native code clause cannot be found within the bundle then the bundle is still allowed to resolve".
 * testStart follows the 3.10.1 reading, which Equinox 3.21 implements; a framework following the 3.10 sentence may
 * refuse to resolve. testResolveBundles holds under both readings: FrameworkWiring.resolveBundles must return false
 * for a bundle that cannot be resolved, never throw an unchecked IllegalStateException.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:394-396}
 * ({@code throw MESSAGES.illegalStateCannotFindNativeLibrary(libpath)}, with the R4 comment "the bundle must fail to
 * resolve"), reached from {@code ModuleManagerImpl.addNativeResourceLoader(ModuleManagerImpl.java:535)}.
 *
 * <h3>Root cause</h3>
 * The native library provider is created eagerly while the module of the resolved bundle is built, and it throws
 * when the entry does not exist. The exception is thrown after the resolver has produced its result, it is not
 * turned into an unresolved bundle: it propagates out of {@code resolveBundles} and {@code Bundle.start} reports it as
 * a resolve failure.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NativeCodeMissingLibraryIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testResolveBundles: resolveBundles must not throw, but threw:
 *   java.lang.IllegalStateException: JBOSGI011223: Cannot find native library: lib/missing.so
 * testStart: A bundle with a missing native library in its selected clause must start, but:
 *   BundleException: JBOSGI011250: Cannot resolve bundle: native-missing-start:0.0.0 caused by
 *   java.lang.IllegalStateException: JBOSGI011223: Cannot find native library: lib/missing.so
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code resolveBundles} returns true, the bundle starts; {@code System.loadLibrary("missing")} would fail later at
 * runtime.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 resolves and starts the bundle.
 *
 * <h3>Suggested fix</h3>
 * Skip a library path that is not found (or resolve it lazily in {@code findLibrary}) instead of throwing from the
 * {@code NativeLibraryProviderImpl} constructor; let {@code findLibrary} return null for it.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing, but the R8 branch claims R7/R8 compliance.
 *
 * @since 13-Oct-2026
 */
public class NativeCodeMissingLibraryIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testResolveBundles() throws Exception {
        Bundle bundle = install("native-missing-resolve");
        try {
            boolean resolved;
            try {
                resolved = getSystemContext().getBundle().adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            } catch (RuntimeException ex) {
                fail("resolveBundles must not throw, but threw: " + ex);
                return;
            }
            assertTrue("A bundle with a missing native library in its selected clause must resolve", resolved);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testStart() throws Exception {
        Bundle bundle = install("native-missing-start");
        try {
            bundle.start();
            assertEquals("Bundle state", Bundle.ACTIVE, bundle.getState());
        } catch (BundleException ex) {
            fail("A bundle with a missing native library in its selected clause must start, but: " + ex + " caused by " + ex.getCause());
        } finally {
            bundle.uninstall();
        }
    }

    private Bundle install(String symbolicName) throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        // lib/a.so is in the bundle, lib/missing.so is not
        attributes.putValue(Constants.BUNDLE_NATIVECODE, "lib/a.so; lib/missing.so; osname=\"" + osname + "\"; processor=\"" + processor + "\"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        jar.putNextEntry(new JarEntry("lib/a.so"));
        jar.write(new byte[] { 0, 1, 2, 3 });
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle("native-missing:" + symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
