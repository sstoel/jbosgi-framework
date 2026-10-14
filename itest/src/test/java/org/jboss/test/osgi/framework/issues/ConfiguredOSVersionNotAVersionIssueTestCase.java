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
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A configured org.osgi.framework.os.version that is not a valid version is handled inconsistently: for values like
 * {@code 1.2.3.a+b} or {@code -1.0} the framework no longer initializes at all (it did on WF41), and for the value the
 * fix was written for ({@code 5.15.0-91-generic}) a native code clause with an osversion range makes
 * FrameworkWiring.resolveBundles throw IllegalArgumentException.
 *
 * <h3>Severity</h3>
 * minor - needs a non-standard configured os version (the spec asks the launcher to configure a valid version), but
 * the R8 branch turned a tolerated configuration into a framework that cannot start, and the commit claims to
 * support such values.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code NativeNamespaceSupport.getCapabilityAttributes} (osgi.native.osversion),
 * {@code BundleManagerPlugin.getOSVersionInOSGiFormat}, {@code NativeCodeImpl.matchParameter}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, version-helper fuzzing of {@code getOSVersionInOSGiFormat} / the osgi.native capability
 * (inputs: kernel style, negative and invalid-qualifier versions), confirmed on a launched framework and compared
 * with the WF41 build of the framework (6.6.0.Final, same JDK 25).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.2 Launching Properties, org.osgi.framework.os.version: "The version of the operating system as
 * used in the native code clause. If not set, then the framework must provide a default value. If the operating
 * system reported version does not fit the standard version syntax (e.g. 2.4.32-kwt), then the launcher should
 * define this launching property with a valid version value."
 * FrameworkWiring.resolveBundles (10.4.8.5) declares only IllegalArgumentException for bundles of another
 * framework. Commit 8322ec6d: "Use the numeric part of a configured os version that is not a version".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeNamespaceSupport.java:67-74}:
 * commit 8322ec6d replaced the fallback {@code return Version.emptyVersion;} with
 * {@code return Version.parseVersion(BundleManagerPlugin.getOSVersionInOSGiFormat(osVersion));}, which throws again
 * when the numeric prefix keeps an invalid qualifier ({@code 1.2.3.a+b}) or a negative number ({@code -1.0}):
 * {@code BundleManagerPlugin.java:1069-1092} accepts any {@code Integer.parseInt} value and appends the 4th part
 * unchecked. The exception escapes from the system bundle's osgi.native capability attribute map while the system
 * bundle revision is validated, so {@code Framework.init} fails with JBOSGI011246.
 * {@code NativeCodeImpl.java:318} still does {@code Version.parseVersion(fwOSVersion)} on the configured value, so a
 * clause with osversion fails with IllegalArgumentException out of the resolver even for {@code 5.15.0-91-generic}.
 *
 * <h3>Root cause</h3>
 * The tolerant handling of a non-version os version was added to one of the three places that parse it, and the
 * new fallback is itself not total.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ConfiguredOSVersionNotAVersionIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testInvalidQualifierFrameworkStarts: The framework must start with org.osgi.framework.os.version=1.2.3.a+b (as on WF41),
 *   but: BundleException: JBOSGI011246: Cannot initialize Framework caused by IllegalArgumentException:
 *   invalid version "1.2.3.a+b": invalid qualifier "a+b"
 * testNegativeFrameworkStarts: ... os.version=-1.0 ... JBOSGI011246: Cannot initialize Framework caused by
 *   IllegalArgumentException: invalid version "-1.0.0": negative number "-1"
 * testKernelVersionNativeCodeResolves: resolveBundles must not throw with os version 5.15.0-91-generic, but threw:
 *   java.lang.IllegalArgumentException: invalid version "5.15.0-91-generic": non-numeric "0-91-generic"
 * Stack of the init failure: Version.parseVersion &lt;- NativeNamespaceSupport$1.get(NativeNamespaceSupport.java:73)
 *   &lt;- entrySet(:99) &lt;- AbstractCapability.toString &lt;- AbstractResource.validate &lt;- BundleStateRevision.&lt;init&gt;(:119)
 * </pre>
 *
 * <h3>Expected</h3>
 * The framework starts with these configured values (as the WF41 framework does), and the native code selection
 * uses the same numeric part as the osgi.native capability, so a clause with {@code osversion="[1.0,100.0)"} resolves
 * with {@code 5.15.0-91-generic}.
 *
 * <h3>Reference behaviour</h3>
 * WF41 jbosgi (6.6.0.Final): the framework starts with {@code 1.2.3.a+b} and {@code -1.0} (osversion capability
 * 0.0.0), but its resolve of an osversion clause throws the same IllegalArgumentException. Equinox 3.21 rejects all
 * three values when the framework is created (IllegalStateException), i.e. it does not try to be tolerant.
 *
 * <h3>Suggested fix</h3>
 * Compute the effective os version once (valid version, else numeric prefix with a validated qualifier and
 * non-negative parts, else 0.0.0) and use it in NativeNamespaceSupport and NativeCodeImpl; or reject an invalid
 * configured value at init with a clear BundleException.
 *
 * <h3>Regression vs WF41</h3>
 * yes for the framework init failure (commit 8322ec6d); no for the resolve exception (pre-existing in NativeCodeImpl).
 *
 * @since 13-Oct-2026
 */
public class ConfiguredOSVersionNotAVersionIssueTestCase {

    @Test
    public void testInvalidQualifierFrameworkStarts() throws Exception {
        assertFrameworkStarts("1.2.3.a+b");
    }

    @Test
    public void testNegativeFrameworkStarts() throws Exception {
        assertFrameworkStarts("-1.0");
    }

    @Test
    public void testKernelVersionNativeCodeResolves() throws Exception {
        Framework framework = newFramework("5.15.0-91-generic");
        framework.start();
        try {
            BundleContext context = framework.getBundleContext();
            String osname = context.getProperty(Constants.FRAMEWORK_OS_NAME);
            String processor = context.getProperty(Constants.FRAMEWORK_PROCESSOR);
            Bundle bundle = install(context, "lib/a.so; osname=\"" + osname + "\"; processor=\"" + processor + "\"; osversion=\"[1.0,100.0)\"");
            boolean resolved;
            try {
                resolved = framework.adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundle));
            } catch (RuntimeException ex) {
                fail("resolveBundles must not throw with os version 5.15.0-91-generic, but threw: " + ex);
                return;
            }
            assertTrue("The clause with osversion [1.0,100.0) must be selected for os version 5.15.0-91-generic (numeric part 5.15.0)", resolved);
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private void assertFrameworkStarts(String osVersion) throws Exception {
        Framework framework = newFramework(osVersion);
        try {
            framework.start();
        } catch (Exception ex) {
            fail("The framework must start with org.osgi.framework.os.version=" + osVersion + " (as on WF41), but: " + ex + " caused by "
                    + ex.getCause());
        }
        try {
            assertEquals("Framework state", Bundle.ACTIVE, framework.getState());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private Framework newFramework(String osVersion) {
        Map<String, String> props = new HashMap<String, String>();
        File storage = new File("target/configured-os-version/" + osVersion.replaceAll("[^A-Za-z0-9.]", "_"));
        props.put(Constants.FRAMEWORK_STORAGE, storage.getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        props.put(Constants.FRAMEWORK_OS_VERSION, osVersion);
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        return factory.newFramework(props);
    }

    private Bundle install(BundleContext context, String nativeCode) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "configured-os-version");
        attributes.putValue(Constants.BUNDLE_NATIVECODE, nativeCode);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        jar.putNextEntry(new JarEntry("lib/a.so"));
        jar.write(new byte[] { 0, 1, 2, 3 });
        jar.closeEntry();
        jar.close();
        return context.installBundle("configured-os-version", new ByteArrayInputStream(baos.toByteArray()));
    }
}
