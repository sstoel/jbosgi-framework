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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Namespace;

/**
 * A Bundle-NativeCode clause that repeats a selection attribute (osname, processor, language, osversion,
 * selection-filter) is converted into a wrong osgi.native requirement, so the bundle never resolves (or does not
 * even install) on a platform that the clause names.
 *
 * <h3>Severity</h3>
 * major - the canonical multi-platform form of the header from the specification itself
 * ({@code osname=Windows95; osname=Windows98; ...}) makes a bundle unusable.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.NativeNamespaceSupport} (with the
 * {@code AbstractParameter} value representation of jbosgi-metadata).
 *
 * <h3>Found by</h3>
 * fuzz workstream: Equinox differential on the seed corpus of the manifest install fuzzer. Input
 * {@code Bundle-NativeCode: lib/a.so;osname=Linux;osname=MacOSX;processor=x86;processor=aarch64} produced the
 * requirement filter {@code (&(osgi.native.osname~=Linux, MacOSX)(osgi.native.processor~=aarch64, x86))} on
 * jbosgi and {@code (&(|(osgi.native.osname~=Linux)(osgi.native.osname~=MacOSX))(|(osgi.native.processor~=x86)(osgi.native.processor~=aarch64)))}
 * on Equinox 3.21.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 3.10 Loading Native Code Libraries
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.module.html#framework.module-loading.native.code.libraries):
 * "If a Bundle-NativeCode clause contains duplicate parameter entries, the corresponding values must be OR'ed
 * together." and "In cases where the same Bundle-NativeCode attribute is specified multiple times within the same
 * clause then the filter components for each value for that attribute are ORed together. For example, if osname
 * attribute is specified as both "Windows95" and "Windows7" then the resulting filter will contain:
 * (|(osgi.native.osname~=Windows95)(osgi.native.osname~=Windows7))".
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeNamespaceSupport.java:173}
 * ({@code getValues}) relies on {@code Parameter.getValue()} returning a Collection for a repeated attribute, but
 * jbosgi-metadata {@code src/main/java/org/jboss/osgi/metadata/internal/AbstractParameter.java:54-58} joins the
 * values of a repeated attribute into ONE string ({@code values.toString()} without the brackets, i.e.
 * {@code "Linux, MacOSX"}) and passes that to the String value creator. Introduced with the osgi.native mapping in
 * commit 992ee69a (already on WF41); {@code NativeCodeImpl.matchParameter} splits that string on {@code ",\\s"},
 * the osgi.native mapping does not.
 *
 * <h3>Root cause</h3>
 * {@code getValues} sees the single string {@code "Linux, MacOSX"} and creates one approximate match
 * {@code (osgi.native.osname~=Linux, MacOSX)}; the approximate match ignores white space and case, so it is
 * compared with {@code "linux,macosx"} and matches no osgi.native.osname value of the system bundle. For osversion
 * the joined string {@code "[0,1), [1,10000)"} is no version range, the install fails with
 * {@code JBOSGI011291: Invalid Bundle-NativeCode header}. Two selection-filter values give the invalid filter
 * {@code (a=b), (c=d)}, the install fails with an invalid requirement. The HashSet in AbstractParameter also loses
 * the order of the values.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=NativeCodeRepeatedAttributeIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testRepeatedOSNameFilter: The osgi.native requirement must match a platform with osname Linux:
 *   filter (&amp;(osgi.native.osname~=Win32, Linux)(osgi.native.processor~=x86))
 * testRepeatedOSNameResolves: Bundle with Bundle-NativeCode: lib/a.so; osname=NoSuchOS; osname="Mac OS X"; processor="aarch64"
 *   must start, but: BundleException: JBOSGI011250: Cannot resolve bundle: repeated-osname:0.0.0 caused by ReasonException:
 *   ... missing requirement XCapabilityRequirement[osgi.native,dirs={filter=(&amp;(osgi.native.osname~=Mac OS X, NoSuchOS)(osgi.native.processor~=aarch64))}
 * testRepeatedProcessorResolves: ... missing requirement ... filter=(&amp;(osgi.native.osname~=Mac OS X)(osgi.native.processor~=aarch64, NoSuchCPU))
 * testRepeatedOSVersionInstalls: Bundle with Bundle-NativeCode: ...; osversion="[10000.0,20000.0)"; osversion="[0.0,27.0)" must install,
 *   but: BundleException: JBOSGI011291: Invalid Bundle-NativeCode header ... caused by IllegalArgumentException:
 *   invalid range "[10000.0,20000.0), [0.0,27.0)": invalid format
 * </pre>
 *
 * <h3>Expected</h3>
 * The values of a repeated attribute are ORed: the requirement matches a platform that has any of the values, the
 * bundle installs and resolves where one of the values applies.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 (R8 RI) builds {@code (|(osgi.native.osname~=Linux)(osgi.native.osname~=MacOSX))}, installs the
 * repeated osversion clause and starts all of these bundles on macOS/aarch64.
 *
 * <h3>Suggested fix</h3>
 * Keep the individual values of a repeated attribute: let {@code AbstractParameter.getValue()} return a List of
 * created values when there is more than one value (it already advertises {@code isCollection()}), or give
 * {@code Parameter} an accessor for the raw values and use it in {@code NativeNamespaceSupport.getValues} and in
 * {@code NativeCodeImpl.matchParameter} (whose {@code (String) osversionParam.getValue()} cast has the same problem).
 * Use an insertion ordered collection.
 *
 * <h3>Regression vs WF41</h3>
 * no - the same code is on WF41 (commit 992ee69a), still a spec violation on the R8 branch.
 *
 * @since 13-Oct-2026
 */
public class NativeCodeRepeatedAttributeIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testRepeatedOSNameFilter() throws Exception {
        Bundle bundle = install("repeated-osname-filter", "lib/a.so; osname=Linux; osname=Win32; processor=x86");
        try {
            List<BundleRequirement> reqs = bundle.adapt(BundleRevision.class).getDeclaredRequirements(NativeNamespace.NATIVE_NAMESPACE);
            assertEquals("One osgi.native requirement", 1, reqs.size());
            String filterSpec = reqs.get(0).getDirectives().get(Namespace.REQUIREMENT_FILTER_DIRECTIVE);
            Filter filter = FrameworkUtil.createFilter(filterSpec);
            assertTrue("The osgi.native requirement must match a platform with osname Linux: filter " + filterSpec,
                    filter.matches(platform("Linux", "x86")));
            assertTrue("The osgi.native requirement must match a platform with osname Win32: filter " + filterSpec,
                    filter.matches(platform("Win32", "x86")));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testRepeatedOSNameResolves() throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        assertStarts("repeated-osname", "lib/a.so; osname=NoSuchOS; osname=\"" + osname + "\"; processor=\"" + processor + "\"");
    }

    @Test
    public void testRepeatedProcessorResolves() throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        assertStarts("repeated-processor", "lib/a.so; osname=\"" + osname + "\"; processor=NoSuchCPU; processor=\"" + processor + "\"");
    }

    @Test
    public void testRepeatedOSVersionInstalls() throws Exception {
        String osname = getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME);
        String processor = getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR);
        Version osversion = Version.parseVersion(getSystemContext().getProperty(Constants.FRAMEWORK_OS_VERSION).replaceAll("[^0-9.].*", ""));
        String including = "[0.0," + (osversion.getMajor() + 1) + ".0)";
        assertStarts("repeated-osversion", "lib/a.so; osname=\"" + osname + "\"; processor=\"" + processor + "\"; osversion=\"[10000.0,20000.0)\"; osversion=\""
                + including + "\"");
    }

    private void assertStarts(String symbolicName, String nativeCode) throws Exception {
        Bundle bundle;
        try {
            bundle = install(symbolicName, nativeCode);
        } catch (BundleException ex) {
            fail("Bundle with Bundle-NativeCode: " + nativeCode + " must install, but: " + ex + " caused by " + ex.getCause());
            return;
        }
        try {
            bundle.start();
            assertEquals("Bundle state", Bundle.ACTIVE, bundle.getState());
        } catch (BundleException ex) {
            fail("Bundle with Bundle-NativeCode: " + nativeCode + " must start, but: " + ex + " caused by " + ex.getCause());
        } finally {
            bundle.uninstall();
        }
    }

    private static Map<String, Object> platform(String osname, String processor) {
        Map<String, Object> atts = new HashMap<String, Object>();
        atts.put(NativeNamespace.CAPABILITY_OSNAME_ATTRIBUTE, Arrays.asList(osname));
        atts.put(NativeNamespace.CAPABILITY_PROCESSOR_ATTRIBUTE, Arrays.asList(processor));
        atts.put(NativeNamespace.CAPABILITY_OSVERSION_ATTRIBUTE, Version.parseVersion("1.0"));
        atts.put(NativeNamespace.CAPABILITY_LANGUAGE_ATTRIBUTE, "en");
        return atts;
    }

    private Bundle install(String symbolicName, String nativeCode) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(Constants.BUNDLE_NATIVECODE, nativeCode);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jar = new JarOutputStream(baos, manifest);
        jar.putNextEntry(new JarEntry("lib/a.so"));
        jar.write(new byte[] { 0, 1, 2, 3 });
        jar.closeEntry();
        jar.close();
        return getSystemContext().installBundle("native-repeated:" + symbolicName, new ByteArrayInputStream(baos.toByteArray()));
    }
}
