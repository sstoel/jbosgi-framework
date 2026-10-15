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
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A configured org.osgi.framework.system.capabilities launching property does not replace the calculated osgi.ee
 * capabilities: the system bundle provides the configured capabilities in addition to the default JavaSE versions of
 * the running VM, so bundles that need a newer execution environment than the configured one still resolve.
 *
 * <h3>Severity</h3>
 * minor - only affects deployments that configure the property (e.g. to pin the execution environment the bundles
 * may rely on); the configuration is silently ignored.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.SystemBundlePlugin.createMetaData}.
 *
 * <h3>Found by</h3>
 * resolver workstream, Equinox/Felix differential of the system bundle capabilities with launching properties
 * ({@code org.osgi.framework.system.capabilities=osgi.ee;osgi.ee="JavaSE";version:List<Version>="1.7,1.8"}).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.2 Launching Properties, Table 4.1
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.launchingproperties):
 * "org.osgi.framework.system.capabilities - Specifies the capabilities of the environment in the grammar specified
 * for the Provide-Capability header [...]. These capabilities must be provided from the system bundle. If this
 * property is not set, the framework must calculate this header based on the environment." Constants
 * FRAMEWORK_SYSTEMCAPABILITIES javadoc: "If this property is not specified then the framework must calculate a
 * reasonable default value for the current execution environment."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/SystemBundlePlugin.java:145-233}
 * always adds the osgi.ee capabilities calculated from org.osgi.framework.executionenvironment (which the framework
 * itself always sets) or from the running VM, and {@code :235-240} then adds the configured
 * org.osgi.framework.system.capabilities on top. Pre-existing (same on WF41); the R8 commits changed the calculated
 * part (JavaSE up to the running feature version).
 *
 * <h3>Root cause</h3>
 * The configured property is treated like an "extra" property instead of replacing the calculated default.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=SystemCapabilitiesPropertyIssueTestCase} (in jbosgi-framework, after {@code mvn install
 * -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testConfiguredCapabilitiesReplaceDefault: With org.osgi.framework.system.capabilities limited to JavaSE 1.7/1.8 a
 *   bundle requiring JavaSE 11 must not resolve expected:&lt;false&gt; but was:&lt;true&gt;
 * testOnlyConfiguredJavaSECapability: The system bundle must only provide the configured JavaSE capability, but
 *   provides [[1.1.0, ..., 1.8.0], [9.0.0, ..., 25.0.0], [1.7.0, 1.8.0]] expected:&lt;1&gt; but was:&lt;3&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * When the property is set, the system bundle provides exactly the configured capabilities (plus the extra ones), so
 * the JavaSE 11 requirement is not satisfied.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5: the JavaSE 11 bundle stays INSTALLED, the JavaSE 1.8 bundle resolves.
 *
 * <h3>Suggested fix</h3>
 * In {@code SystemBundlePlugin.createMetaData}, only calculate the osgi.ee capabilities when
 * org.osgi.framework.system.capabilities is not set; keep adding the .extra capabilities in both cases (and keep the
 * osgi.native capability, which 8.7 requires anyway).
 *
 * <h3>Regression vs WF41</h3>
 * no - WF41 behaves the same.
 *
 * @since 13-Oct-2026
 */
public class SystemCapabilitiesPropertyIssueTestCase extends AbstractFrameworkLaunchTest {

    private static final String SYSTEM_CAPABILITIES = "osgi.ee;osgi.ee=\"JavaSE\";version:List<Version>=\"1.7,1.8\"";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConfiguredCapabilitiesReplaceDefault() throws Exception {
        startFramework();
        Bundle needs8 = install("needs-javase8", "(&(osgi.ee=JavaSE)(version=1.8))");
        Bundle needs11 = install("needs-javase11", "(&(osgi.ee=JavaSE)(version=11))");
        FrameworkWiring frameworkWiring = framework.adapt(FrameworkWiring.class);
        assertTrue("A bundle requiring JavaSE 1.8 resolves", frameworkWiring.resolveBundles(Arrays.asList(needs8)));
        assertEquals("With org.osgi.framework.system.capabilities limited to JavaSE 1.7/1.8 a bundle requiring JavaSE 11 must not resolve", false,
                frameworkWiring.resolveBundles(Arrays.asList(needs11)));
    }

    @Test
    public void testOnlyConfiguredJavaSECapability() throws Exception {
        startFramework();
        List<Object> versions = new ArrayList<Object>();
        for (BundleCapability cap : framework.adapt(BundleRevision.class).getDeclaredCapabilities(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)) {
            if ("JavaSE".equals(cap.getAttributes().get(ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE)))
                versions.add(cap.getAttributes().get(ExecutionEnvironmentNamespace.CAPABILITY_VERSION_ATTRIBUTE));
        }
        assertEquals("The system bundle must only provide the configured JavaSE capability, but provides " + versions, 1, versions.size());
    }

    private void startFramework() throws Exception {
        Map<String, String> props = getFrameworkInitProperties(true);
        props.put(Constants.FRAMEWORK_SYSTEMCAPABILITIES, SYSTEM_CAPABILITIES);
        framework = newFramework(props);
        framework.start();
    }

    private Bundle install(String name, String eeFilter) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, name.replace('-', '.'));
        attributes.putValue(Constants.REQUIRE_CAPABILITY, "osgi.ee;filter:=\"" + eeFilter + "\"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(baos.toByteArray()));
    }
}
