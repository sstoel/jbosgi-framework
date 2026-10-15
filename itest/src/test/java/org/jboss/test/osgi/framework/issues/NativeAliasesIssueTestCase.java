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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The osgi.native capability of the system bundle lacks the canonical names and aliases of the configured
 * processor and OS name: only an alias is mapped to its canonical name, a canonical name gets no aliases and AArch64
 * is unknown. Bundles whose Bundle-NativeCode uses another spelling (x86_64 on an x86-64 machine, arm64 on aarch64,
 * Win32 on Windows) do not resolve.
 *
 * <h3>Severity</h3>
 * major - native code bundles are selected by processor/osname; e.g. with
 * {@code org.osgi.framework.processor=x86-64} a bundle with {@code processor=x86_64} or {@code processor=amd64} stays
 * INSTALLED, and on Apple silicon or ARM servers (os.arch aarch64) {@code processor=arm64} never matches.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.NativeCodeImpl} (alias tables,
 * {@code getOSNames}/{@code getProcessors}) used by {@code NativeNamespaceSupport} for the osgi.native capability.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox differential with configured {@code org.osgi.framework.processor} /
 * {@code org.osgi.framework.os.name} values and Bundle-NativeCode clauses using the reference names and aliases.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 8.7 osgi.native Namespace, Table 8.8
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html#framework.namespaces.osgi.native):
 * "osgi.native.osname - A comma separated list of canonical os name aliases to the org.osgi.framework.os.name launching
 * property value (See Table 4.3 and [11] OSGi Reference Names). For example, an org.osgi.framework.os.name value of
 * Windows95 will get a List&lt;String&gt; value of Windows95,Windows 95,Win95,Win32". "osgi.native.processor - A comma
 * separated list of canonical processor aliases to the org.osgi.framework.processor launching property value [...]
 * For example, an org.osgi.framework.processor value of x86 will get a List&lt;String&gt; value of
 * x86,pentium,i386,i486,i586,i686". OSGi Reference Names (https://docs.osgi.org/reference/osnames.html): processor
 * "AArch64", alias "ARM64"; "x86-64", aliases "amd64 em64t x86_64".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeCodeImpl.java:80-147} (alias maps
 * from alias to canonical name only, no AArch64 entry, no Windows 10/11 entry) and {@code :150-174}
 * ({@code getOSNames}/{@code getProcessors} add the canonical name of an alias, never the aliases of a canonical
 * name). Pre-existing; the R8 commits use these lists for the osgi.native capability.
 *
 * <h3>Root cause</h3>
 * The lists are built as "configured value + canonical name if the configured value is a known alias". A canonical
 * configured value (x86, x86-64, Windows95, aarch64) gets no aliases; aliases of the same canonical name are not
 * added; AArch64/ARM64 is missing from the table.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NativeAliasesIssueTestCase} (in jbosgi-framework, after {@code mvn install -DskipTests
 * -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testProcessorAliasesOfX86: osgi.native.processor for processor x86 must contain the aliases [x86, pentium, i386,
 *   i486, i586, i686] but is [x86]
 * testOSNameAliasesOfWindows95: osgi.native.osname for os.name Windows95 must contain the aliases [Windows95,
 *   Windows 95, Win95, Win32] but is [Windows95]
 * testProcessorAliasesOfAArch64: osgi.native.processor for processor aarch64 must contain the aliases [aarch64, arm64]
 *   but is [aarch64]
 * testNativeCodeWithAliasResolves: Bundle with Bundle-NativeCode processor=x86_64 must resolve with
 *   org.osgi.framework.processor=x86-64 expected:&lt;true&gt; but was:&lt;false&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The osgi.native capability lists the canonical name and all its aliases for the configured processor and OS name,
 * as in the examples of Table 8.8; bundles that use any of these names resolve.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: with processor x86-64 configured, Bundle-NativeCode with x86_64, x86-64, em64t and amd64 all resolve;
 * with aarch64, {@code osgi.native.processor=[aarch64, arm64]}; with os.name Windows7 / processor x86, clauses with
 * Win32/i386/pentium/"Windows 7" resolve (jbosgi: none of them).
 *
 * <h3>Suggested fix</h3>
 * Model the reference names as groups (canonical name + aliases, e.g. {x86-64, amd64, em64t, x86_64},
 * {AArch64, arm64}, {Windows95, Windows 95, Win95, Win32}, ...) and return the whole group of the configured value
 * (matched case-insensitively), configured value first; add Windows 10/11/Server 2016+ to the Win32 group like
 * Equinox.
 *
 * <h3>Regression vs WF41</h3>
 * no - same tables on WF41; on the R8 branch they feed the osgi.native capability the resolver matches against.
 *
 * @since 13-Oct-2026
 */
public class NativeAliasesIssueTestCase extends AbstractFrameworkLaunchTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testProcessorAliasesOfX86() throws Exception {
        startFramework("Linux", "x86");
        assertContains("osgi.native.processor for processor x86", getNativeAttribute(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE), "x86", "pentium", "i386", "i486", "i586", "i686");
    }

    @Test
    public void testOSNameAliasesOfWindows95() throws Exception {
        startFramework("Windows95", "x86");
        assertContains("osgi.native.osname for os.name Windows95", getNativeAttribute(NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE), "Windows95", "Windows 95", "Win95", "Win32");
    }

    @Test
    public void testProcessorAliasesOfAArch64() throws Exception {
        startFramework("Linux", "aarch64");
        assertContains("osgi.native.processor for processor aarch64", getNativeAttribute(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE), "aarch64", "arm64");
    }

    @Test
    public void testNativeCodeWithAliasResolves() throws Exception {
        startFramework("Linux", "x86-64");
        Bundle bundle = installNativeBundle("lib/libfoo.so; osname=Linux; processor=x86_64");
        boolean resolved = framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
        assertEquals("Bundle with Bundle-NativeCode processor=x86_64 must resolve with org.osgi.framework.processor=x86-64", true, resolved);
    }

    private void startFramework(String osname, String processor) throws Exception {
        Map<String, String> props = getFrameworkInitProperties(true);
        props.put(Constants.FRAMEWORK_OS_NAME, osname);
        props.put(Constants.FRAMEWORK_PROCESSOR, processor);
        framework = newFramework(props);
        framework.start();
    }

    private List<String> getNativeAttribute(String name) {
        BundleRevision sysrev = framework.adapt(BundleRevision.class);
        List<BundleCapability> caps = sysrev.getDeclaredCapabilities(NativeNamespace.NATIVE_NAMESPACE);
        assertEquals("One osgi.native capability", 1, caps.size());
        Object value = caps.get(0).getAttributes().get(name);
        List<String> result = new ArrayList<String>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value)
                result.add(String.valueOf(item));
        } else if (value != null) {
            result.add(String.valueOf(value));
        }
        return result;
    }

    private void assertContains(String message, List<String> actual, String... expected) {
        List<String> lower = new ArrayList<String>();
        for (String value : actual)
            lower.add(value.toLowerCase(Locale.ENGLISH));
        for (String value : expected) {
            assertTrue(message + " must contain the aliases " + Arrays.asList(expected) + " but is " + actual, lower.contains(value.toLowerCase(Locale.ENGLISH)));
        }
    }

    private Bundle installNativeBundle(String nativeCode) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "native.alias");
        attributes.putValue(Constants.BUNDLE_NATIVECODE, nativeCode);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        jos.putNextEntry(new JarEntry("lib/libfoo.so"));
        jos.write(new byte[] { 0 });
        jos.closeEntry();
        jos.close();
        return framework.getBundleContext().installBundle("native-alias", new ByteArrayInputStream(baos.toByteArray()));
    }
}
