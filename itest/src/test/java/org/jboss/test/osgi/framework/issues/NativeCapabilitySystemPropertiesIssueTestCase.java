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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.namespace.NativeNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * The osgi.native capability of the system bundle, and the evaluation of a Bundle-NativeCode selection-filter, use
 * every JVM system property in addition to the launching properties: a filter on a system property that is not a
 * launching property matches.
 *
 * <h3>Severity</h3>
 * minor. The capability carries about 70 attributes such as user.home, user.name, java.class.path and
 * java.io.tmpdir (also visible through BundleRevision/DTOs to every bundle); its attribute values follow later
 * System.setProperty calls, so a capability that was used to resolve a bundle can change afterwards. A native code
 * selection filter or an osgi.native requirement that tests a JVM system property (for example one set by WildFly
 * or another application in the same JVM) matches in jbosgi but not in a conforming framework.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code NativeNamespaceSupport.getCapabilityAttributes} (live map over
 * {@code BundleManagerPlugin.getProperty}/{@code getProperties}, which fall back to / merge System properties) and
 * {@code NativeCodeImpl.matchParameter} (selection-filter matched against {@code bundleManager.getProperties()}).
 *
 * <h3>Found by</h3>
 * manifest workstream, Equinox/Felix differential harness (system bundle osgi.native capability dump: jbosgi 73
 * attributes including user.home and java.class.path, Felix 7.0.5 64 and Equinox 3.23.0 14 attributes, none of them
 * JVM-only system properties).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 8.7 osgi.native Namespace (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.namespaces.html):
 * "In addition to the attributes defined in Table 8.8 an OSGi Framework must populate the osgi.native capability
 * attributes with the values included in the Framework launching properties (see Launching Properties)."<br>
 * 4.2.2 Launching Properties (framework.lifecycle.html): "The framework must not look in the System properties for
 * configuration properties, the specified configuration properties are complete. ... The configuration properties
 * plus any defaults set by the framework and the fixed properties set by the framework all together form the
 * launching properties for the framework." (Only BundleContext.getProperty additionally falls back to System
 * properties, 4.5.3.)<br>
 * 3.10.1 Native Code Algorithm (framework.module.html): "selection-filter evaluates to true when using the values
 * of the launching properties or selection-filter is not specified".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/NativeNamespaceSupport.java:54-105} (get() falls back
 * to {@code bundleManager.getProperty(key)}, entrySet() iterates {@code bundleManager.getProperties()});
 * {@code BundleManagerPlugin.java:375-400} (getProperty falls back to System properties, getProperties copies all
 * System properties); {@code NativeCodeImpl.java:351}. Introduced by 4d450c75 (on WF41).
 *
 * <h3>Root cause</h3>
 * The capability and the selection-filter use the framework's getProperty view (launching properties plus System
 * properties) instead of the launching properties.
 *
 * <h3>Reproduction</h3>
 * {@code cd jbosgi-framework && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US" mvn -pl itest test
 * -Dtest=NativeCapabilitySystemPropertiesIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testSystemPropertiesAreNotAttributes: The osgi.native capability must only carry launching properties, but it
 *   has the JVM system properties [jbosgi.native.issue, java.class.path, user.home]
 * testRequirementOnSystemProperty: Require-Capability osgi.native;filter:="(jbosgi.native.issue=yes)" tests a JVM
 *   system property that is not a launching property, it must not be satisfied
 * testSelectionFilterOnSystemProperty: The selection-filter (jbosgi.native.issue=yes) tests a JVM system property
 *   that is not a launching property, the clause must not be selected and the bundle must not start
 * </pre>
 *
 * <h3>Expected</h3>
 * None of these system properties is an attribute of the capability; the requirement is unsatisfied; the native
 * code clause is not selected (BundleException on start).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.23.0 and Felix 7.0.5 populate the capability from their framework (launching) properties only.
 *
 * <h3>Suggested fix</h3>
 * Build the capability attributes and the selection-filter dictionary from the launching properties (the
 * configuration map plus framework defaults and fixed properties; {@code BundleManagerPlugin.properties}), and drop
 * launching properties whose key starts with {@code osgi.native.}. The existing branch test
 * NativeNamespaceTestCase.testSelectionFilterWithLateSystemProperty asserts the non-conforming behaviour and would
 * have to set the property as a launching property instead. Verified: taking the extra attributes and the
 * selection-filter dictionary from BundleManagerPlugin's configured properties (and skipping osgi.native.* keys)
 * makes the three test methods pass; NativeCodeTestCase still passes, NativeNamespaceTestCase passes except
 * testSelectionFilterWithLateSystemProperty, which tests exactly the behaviour reported here (JDK 25).
 *
 * <h3>Regression vs WF41</h3>
 * no (WF41 behaves the same).
 */
public class NativeCapabilitySystemPropertiesIssueTestCase extends OSGiFrameworkTest {

    private static final String KEY = "jbosgi.native.issue";

    @Before
    public void setProperty() {
        System.setProperty(KEY, "yes");
    }

    @After
    public void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    public void testSystemPropertiesAreNotAttributes() throws Exception {
        BundleRevision sysrev = getSystemContext().getBundle().adapt(BundleRevision.class);
        BundleCapability cap = sysrev.getDeclaredCapabilities(NativeNamespace.NATIVE_NAMESPACE).get(0);
        Map<String, Object> atts = cap.getAttributes();
        List<String> found = new ArrayList<String>();
        for (String key : Arrays.asList(KEY, "java.class.path", "user.home")) {
            if (atts.containsKey(key) || atts.keySet().contains(key))
                found.add(key);
        }
        assertEquals("The osgi.native capability must only carry launching properties, but it has the JVM system properties " + found,
                new ArrayList<String>(), found);
    }

    @Test
    public void testRequirementOnSystemProperty() throws Exception {
        Bundle bundle = install("native-sysprop-req", Constants.REQUIRE_CAPABILITY, "osgi.native;filter:=\"(" + KEY + "=yes)\"");
        try {
            FrameworkWiring fwiring = getSystemContext().getBundle().adapt(FrameworkWiring.class);
            assertFalse("Require-Capability osgi.native;filter:=\"(" + KEY + "=yes)\" tests a JVM system property that is not a launching property, it must not be satisfied",
                    fwiring.resolveBundles(Arrays.asList(bundle)));
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testSelectionFilterOnSystemProperty() throws Exception {
        String header = "lib/libfoo.so; osname=\"" + getSystemContext().getProperty(Constants.FRAMEWORK_OS_NAME) + "\"; processor=\""
                + getSystemContext().getProperty(Constants.FRAMEWORK_PROCESSOR) + "\"; selection-filter=\"(" + KEY + "=yes)\"";
        Bundle bundle = install("native-sysprop-filter", Constants.BUNDLE_NATIVECODE, header);
        try {
            bundle.start();
            fail("The selection-filter (" + KEY + "=yes) tests a JVM system property that is not a launching property, the clause must not be selected and the bundle must not start");
        } catch (BundleException ex) {
            // expected
        } finally {
            bundle.uninstall();
        }
    }

    private Bundle install(final String name, final String header, final String value) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.add(new ByteArrayAsset(new byte[] { 0 }), "lib/libfoo.so");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                builder.addManifestHeader(header, value);
                return builder.openStream();
            }
        });
        return installBundle(archive);
    }
}
