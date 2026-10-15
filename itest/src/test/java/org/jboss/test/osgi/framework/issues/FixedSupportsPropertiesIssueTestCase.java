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

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * The fixed framework properties {@code org.osgi.supports.framework.fragment} and
 * {@code org.osgi.supports.framework.requirebundle} are not set by the framework (they are null), and a value given
 * for them in the configuration properties is used instead of being ignored.
 *
 * <h3>Severity</h3>
 * minor. Bundles and tools that check these properties before relying on fragments or Require-Bundle (as the spec
 * intends them to) conclude that the framework does not support them, or believe whatever the configuration says.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin} (framework properties) and
 * {@code SystemBundlePlugin} (which since this branch sets only {@code org.osgi.supports.framework.extension}).
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix framework.lifecycle#37-#40), exploratory test plus a differential run against
 * Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.2 Launching Properties, Table 4.4 Fixed Framework Launching Properties
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.launchingproperties):
 * "The values of these properties are established by the framework implementation and added to the launching
 * properties. If these properties are set in the configuration properties, the framework must ignore them." ...
 * "org.osgi.supports.framework.fragment - Support for fragment bundles is mandatory, must therefore be set to true"
 * ... "org.osgi.supports.framework.requirebundle - Support for Require Bundle is mandatory, must therefore be set to
 * true".
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:185-210} copies the
 * configuration properties and fixes only vendor and version; nothing sets
 * {@code Constants.SUPPORTS_FRAMEWORK_FRAGMENT} or {@code SUPPORTS_FRAMEWORK_REQUIREBUNDLE}
 * ({@code SystemBundlePlugin.java:91-93} sets {@code SUPPORTS_FRAMEWORK_EXTENSION} only, added in 3680cfcc).
 * Pre-existing for the two properties.
 *
 * <h3>Root cause</h3>
 * The fixed "supports" properties are not established by the framework.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FixedSupportsPropertiesIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testSupportsPropertiesAreTrue: org.osgi.supports.framework.fragment must be true expected:&lt;true&gt; but was:&lt;null&gt;
 * testConfiguredSupportsPropertiesAreIgnored: org.osgi.supports.framework.requirebundle set in the configuration must
 *   be ignored expected:&lt;true&gt; but was:&lt;false&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Both properties are "true", whatever the configuration contains.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: fragment=true, requirebundle=true (also with requirebundle=false in the configuration),
 * extension=true.
 *
 * <h3>Suggested fix</h3>
 * In the BundleManagerPlugin constructor, next to vendor and version, unconditionally
 * {@code setProperty(Constants.SUPPORTS_FRAMEWORK_FRAGMENT, "true")} and
 * {@code setProperty(Constants.SUPPORTS_FRAMEWORK_REQUIREBUNDLE, "true")}.
 *
 * <h3>Regression vs WF41</h3>
 * no (the properties were not set on WF41 either).
 */
public class FixedSupportsPropertiesIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testSupportsPropertiesAreTrue() throws Exception {
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(getConfiguration());
        framework.init();
        assertEquals("org.osgi.supports.framework.fragment must be true", "true", framework.getBundleContext().getProperty(Constants.SUPPORTS_FRAMEWORK_FRAGMENT));
        assertEquals("org.osgi.supports.framework.requirebundle must be true", "true", framework.getBundleContext().getProperty(Constants.SUPPORTS_FRAMEWORK_REQUIREBUNDLE));
    }

    @Test
    public void testConfiguredSupportsPropertiesAreIgnored() throws Exception {
        Map<String, String> props = getConfiguration();
        props.put(Constants.SUPPORTS_FRAMEWORK_REQUIREBUNDLE, "false");
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.init();
        assertEquals("org.osgi.supports.framework.requirebundle set in the configuration must be ignored", "true",
                framework.getBundleContext().getProperty(Constants.SUPPORTS_FRAMEWORK_REQUIREBUNDLE));
    }

    private Map<String, String> getConfiguration() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return props;
    }
}
