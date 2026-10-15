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
 * The framework takes {@code org.osgi.framework.uuid} from the configuration properties (and from a system property
 * of that name) instead of always generating its own unique identity.
 *
 * <h3>Severity</h3>
 * minor. A launcher configuration that is reused for several framework instances (or a JVM started with
 * {@code -Dorg.osgi.framework.uuid=...}) gives all of them the same, fixed "unique" identity, and the identity no
 * longer changes between launches; remote management / distributed OSGi uses this id to tell frameworks apart.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin} (constructor and
 * {@code getProperty}).
 *
 * <h3>Found by</h3>
 * connect workstream (framework lifecycle, compliance matrix framework.lifecycle#68/#70), exploratory test plus a
 * differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.2.8 Framework UUID
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.uuid): "Each
 * framework must have a unique identity every time before the framework is started. ... Setting this property in the
 * configuration properties has no effect, the framework must override it."<br>
 * 4.2.2 Launching Properties: "The framework must not look in the System properties for configuration properties,
 * the specified configuration properties are complete."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:198-199}
 * ({@code if (getProperty(Constants.FRAMEWORK_UUID) == null) setProperty(FRAMEWORK_UUID, UUID.randomUUID()...)}),
 * where {@code getProperty} (lines 375-380) returns the configured value or, failing that, the system property.
 * Unchanged since WF41 (lines 190-191 there).
 *
 * <h3>Root cause</h3>
 * The UUID is only generated when no value is found, and the lookup includes the configuration and the system
 * properties.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkUuidNotOverriddenIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testConfiguredUuidIsOverridden: The framework must override org.osgi.framework.uuid from the configuration, but it
 *   uses the configured value configured-uuid
 * testSystemPropertyUuidIsIgnored: The framework must not take org.osgi.framework.uuid from the system properties,
 *   but it uses the system property value system-property-uuid
 * </pre>
 *
 * <h3>Expected</h3>
 * {@code BundleContext.getProperty("org.osgi.framework.uuid")} is a freshly generated UUID in both cases.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 with {@code org.osgi.framework.uuid=config-uuid} in the configuration reports a generated UUID
 * (e.g. 092a0923-5891-4ccb-b99c-961795246305).
 *
 * <h3>Suggested fix</h3>
 * Always {@code setProperty(Constants.FRAMEWORK_UUID, UUID.randomUUID().toString())} in the BundleManagerPlugin
 * constructor (it is created for every init, so the TCK requirement of a new id per init stays satisfied).
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class FrameworkUuidNotOverriddenIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        System.clearProperty(Constants.FRAMEWORK_UUID);
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConfiguredUuidIsOverridden() throws Exception {
        Map<String, String> props = getConfiguration();
        props.put(Constants.FRAMEWORK_UUID, "configured-uuid");
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.init();
        String uuid = framework.getBundleContext().getProperty(Constants.FRAMEWORK_UUID);
        assertFalse("The framework must override org.osgi.framework.uuid from the configuration, but it uses the configured value " + uuid,
                "configured-uuid".equals(uuid));
    }

    @Test
    public void testSystemPropertyUuidIsIgnored() throws Exception {
        System.setProperty(Constants.FRAMEWORK_UUID, "system-property-uuid");
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(getConfiguration());
        framework.init();
        String uuid = framework.getBundleContext().getProperty(Constants.FRAMEWORK_UUID);
        assertFalse("The framework must not take org.osgi.framework.uuid from the system properties, but it uses the system property value " + uuid,
                "system-property-uuid".equals(uuid));
    }

    private Map<String, String> getConfiguration() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return props;
    }
}
