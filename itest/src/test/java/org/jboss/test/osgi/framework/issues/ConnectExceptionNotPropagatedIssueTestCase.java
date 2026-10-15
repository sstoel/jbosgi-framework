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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * A runtime exception thrown by {@link ModuleConnector#connect(String)} does not propagate to the caller of
 * {@code installBundle}/{@code update}: the framework wraps it in a {@link BundleException}.
 *
 * <h3>Severity</h3>
 * minor. A launcher or connector that signals a non-BundleException failure (e.g. a SecurityException or its own
 * runtime exception) cannot catch it as such; it gets a BundleException with the original as cause.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.connect}.
 *
 * <h3>Found by</h3>
 * connect workstream, review against 60.3.1/60.3.2 plus an exploratory test; Equinox 3.18.300 compared.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.3.1 Installing Connect Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html): "Throw a BundleException if the
 * installation of the bundle is to be prevented. In this case the BundleException must be thrown from the
 * installBundle method. Any other exception thrown by the connect method must propagate to the caller of the
 * installBundle method."<br>
 * 60.3.2 Updating Connect Bundles: "Any other exception thrown by the connect method must propagate to the caller of
 * the update method."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:872-879}:
 * {@code catch (RuntimeException ex) { throw MESSAGES.cannotConnectBundle(ex, location); }}; for update,
 * {@code UserBundleState.updateInternal} additionally converts every exception into a BundleException. Introduced by
 * 3680cfcc "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * The runtime exception of the connector is deliberately converted instead of being rethrown.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectExceptionNotPropagatedIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testRuntimeExceptionOnInstall: The runtime exception of ModuleConnector.connect must propagate to the caller of
 *   installBundle, but it threw org.osgi.framework.BundleException: JBOSGI011294: Cannot connect bundle at location:
 *   connect:failing
 * testRuntimeExceptionOnUpdate: The runtime exception of ModuleConnector.connect must propagate to the caller of
 *   update, but it threw org.osgi.framework.BundleException: JBOSGI011294: Cannot connect bundle at location:
 *   connect:failing
 * </pre>
 *
 * <h3>Expected</h3>
 * The connector's IllegalStateException itself is thrown by installBundle and update (a BundleException from the
 * connector is already passed through unchanged, which is correct).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 does not propagate it either (it ignores the exception and fails later with "Error reading bundle
 * content" caused by MalformedURLException for the location), so the RI deviates too; the spec text is explicit.
 *
 * <h3>Suggested fix</h3>
 * Remove the {@code catch (RuntimeException)} in {@code BundleManagerPlugin.connect} and let
 * {@code UserBundleState.updateInternal} rethrow runtime exceptions that come from the connector.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectExceptionNotPropagatedIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:failing";

    private Framework framework;
    private RuntimeException failure;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testRuntimeExceptionOnInstall() throws Exception {
        startFramework();
        failure = new IllegalStateException("connect failure");
        try {
            framework.getBundleContext().installBundle(LOCATION);
            fail("installBundle must throw the exception of ModuleConnector.connect, but it returned");
        } catch (IllegalStateException ex) {
            assertSame(failure, ex);
        } catch (BundleException ex) {
            fail("The runtime exception of ModuleConnector.connect must propagate to the caller of installBundle, but it threw " + ex);
        }
    }

    @Test
    public void testRuntimeExceptionOnUpdate() throws Exception {
        startFramework();
        Bundle bundle = framework.getBundleContext().installBundle(LOCATION);
        failure = new IllegalStateException("connect failure");
        try {
            bundle.update();
            fail("update must throw the exception of ModuleConnector.connect, but it returned");
        } catch (IllegalStateException ex) {
            assertSame(failure, ex);
        } catch (BundleException ex) {
            fail("The runtime exception of ModuleConnector.connect must propagate to the caller of update, but it threw " + ex);
        }
    }

    private void startFramework() throws Exception {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                if (failure != null)
                    throw failure;
                if (LOCATION.equals(location) == false)
                    return Optional.empty();
                return Optional.<ConnectModule> of(new ConnectModule() {
                    @Override
                    public ConnectContent getContent() {
                        return new SimpleContent();
                    }
                });
            }

            @Override
            public Optional<BundleActivator> newBundleActivator() {
                return Optional.empty();
            }
        };
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
        framework.start();
    }

    static class SimpleContent implements ConnectContent {

        @Override
        public Optional<Map<String, String>> getHeaders() {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.failing");
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            return Collections.emptyList();
        }

        @Override
        public Optional<ConnectEntry> getEntry(String path) {
            return Optional.empty();
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            return Optional.empty();
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }
    }
}
