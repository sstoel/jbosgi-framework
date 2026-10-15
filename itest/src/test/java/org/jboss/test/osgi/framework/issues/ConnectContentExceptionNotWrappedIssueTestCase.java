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
import java.io.IOException;
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
 * A runtime exception thrown by {@link ConnectModule#getContent()} or {@link ConnectContent#open()} while a connect
 * bundle is installed escapes from {@code installBundle} as is, instead of being wrapped in
 * a {@link BundleException}. (The update path does wrap it: {@code UserBundleState.updateInternal} converts any
 * exception into a BundleException, so only install is affected.)
 *
 * <h3>Severity</h3>
 * minor. Management agents and launchers that catch {@code BundleException} around {@code installBundle} (the
 * documented failure type) get an unexpected unchecked exception from a misbehaving connector.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.connect} and
 * {@code ConnectVirtualFile} (content opened lazily from {@code Root.open()} while the manifest is read).
 *
 * <h3>Found by</h3>
 * connect workstream, review against the spec text plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.3.1 Installing Connect Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html): "Any exception thrown by the
 * getContent method must be wrapped in a BundleException and result in the BundleException being thrown by the
 * installBundle method." (60.3.2 says the same for update.)<br>
 * 60.3.3.1 Opening Connect Content: "If any exception is thrown by the open method as a result of installing or
 * updating a connect bundle then the exception must be wrapped in a BundleException and result in the
 * BundleException being thrown by the bundle install or update method."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:883-887} only catches the
 * {@code IOException} of {@code ConnectVirtualFile.create} (which calls {@code module.getContent()},
 * {@code ConnectVirtualFile.java:73}); {@code ConnectVirtualFile.java:322-328} ({@code Root.open()}) lets a runtime
 * exception of {@code content.open()} through, and the install path ({@code AbstractBundleContext.installBundle},
 * {@code BundleManagerPlugin.createBundleRevision}) does not wrap it either. Introduced by 3680cfcc
 * "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * Only checked exceptions of the connect content are converted; unchecked exceptions from connector code are not.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectContentExceptionNotWrappedIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testGetContentRuntimeException: installBundle must throw a BundleException that wraps the exception of
 *   getContent(), but it threw java.lang.IllegalStateException: getContent failure
 * testOpenRuntimeException: installBundle must throw a BundleException that wraps the exception of open(), but it
 *   threw java.lang.IllegalStateException: open failure
 * </pre>
 *
 * <h3>Expected</h3>
 * A {@link BundleException} whose cause is the connector's exception.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: both install scenarios throw
 * {@code BundleException: Error occurred installing a bundle.} caused by the IllegalStateException.
 *
 * <h3>Suggested fix</h3>
 * Catch {@code RuntimeException} around {@code module.getContent()} in {@code BundleManagerPlugin.connect} and wrap it
 * with {@code MESSAGES.cannotConnectBundle}; open the content eagerly in {@code BundleManagerPlugin.connect} (or wrap
 * {@code content.open()} in {@code Root.open()} into an IOException) so that a failure of {@code open()} during install
 * becomes a BundleException.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectContentExceptionNotWrappedIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:failing";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testGetContentRuntimeException() throws Exception {
        final IllegalStateException failure = new IllegalStateException("getContent failure");
        startFramework(new ConnectModule() {
            @Override
            public ConnectContent getContent() {
                throw failure;
            }
        });
        try {
            framework.getBundleContext().installBundle(LOCATION);
            fail("installBundle must throw a BundleException that wraps the exception of getContent(), but it returned");
        } catch (BundleException ex) {
            assertSame("The BundleException must wrap the exception of getContent()", failure, ex.getCause());
        } catch (RuntimeException ex) {
            fail("installBundle must throw a BundleException that wraps the exception of getContent(), but it threw " + ex);
        }
    }

    @Test
    public void testOpenRuntimeException() throws Exception {
        final IllegalStateException failure = new IllegalStateException("open failure");
        final Content content = new Content(failure);
        startFramework(module(content));
        try {
            framework.getBundleContext().installBundle(LOCATION);
            fail("installBundle must throw a BundleException that wraps the exception of open(), but it returned");
        } catch (BundleException ex) {
            assertSame("The BundleException must wrap the exception of open()", failure, ex.getCause());
        } catch (RuntimeException ex) {
            fail("installBundle must throw a BundleException that wraps the exception of open(), but it threw " + ex);
        }
    }

    private static ConnectModule module(final ConnectContent content) {
        return new ConnectModule() {
            @Override
            public ConnectContent getContent() {
                return content;
            }
        };
    }

    private void startFramework(final ConnectModule module) throws BundleException {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                return LOCATION.equals(location) ? Optional.of(module) : Optional.<ConnectModule> empty();
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

    static class Content implements ConnectContent {

        private final RuntimeException openFailure;

        Content(RuntimeException openFailure) {
            this.openFailure = openFailure;
        }

        @Override
        public Optional<Map<String, String>> getHeaders() {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.failing");
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() throws IOException {
            return new LinkedHashMap<String, String>().keySet();
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
            if (openFailure != null)
                throw openFailure;
        }

        @Override
        public void close() {
        }
    }
}
