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
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
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
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A framework extension that is a connect bundle has its content added to the framework's own class loading, which
 * the Connect specification forbids: the module connector is responsible for the class path of connect extensions.
 *
 * <h3>Severity</h3>
 * minor. Connect extensions are rare, but when a connector provides one (e.g. a framework extension that lives on the
 * class path), jbosgi defines its classes a second time in the framework module, next to the copies the connector's
 * class loader already defined, which leads to duplicate classes / ClassCastException / LinkageError between the
 * framework's and the connector's view of the same types; the extension's resources become visible through the
 * system bundle.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkExtensions.attach},
 * {@code FrameworkModuleProviderImpl.addExtensionContent}.
 *
 * <h3>Found by</h3>
 * connect workstream, review of FrameworkExtensions against 60.3.6 plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.3.6 Extension Bundles
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html): "If an extension bundle is also a
 * connect bundle then the framework must not attempt to perform the class path modifications of the Framework itself
 * with the extension bundle. It is assumed that the ModuleConnector is managing that outside of the Framework."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkExtensions.java:326-341}: {@code attach} adds a
 * {@code VirtualFileResourceLoader} for every class path content of the extension to the framework module
 * ({@code moduleProvider.addExtensionContent(contentURL, loader)}), also when the content is a
 * {@code ConnectVirtualFile}; for a connect bundle {@code getContentURL} returns null, so the content is loaded by the
 * framework module's class loader ({@code FrameworkModuleProviderImpl.java:155-171}). Introduced by 3680cfcc
 * "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * {@code FrameworkExtensions.attach} does not distinguish connect extensions from ordinary ones.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectExtensionModifiesFrameworkIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The content of a connect extension must not be added to the class loading of the
 *   framework, but the system bundle class loader finds its resource at connect://.../connectext/marker.txt
 * </pre>
 *
 * <h3>Expected</h3>
 * The extension is attached (its capabilities become the system bundle's, its activator is started), but the
 * framework does not load anything from the connect content; the connector is responsible for making the
 * extension's classes available to the framework's class loader.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: the same marker resource is not visible through the system bundle (Equinox did not resolve this
 * minimal extension either, so the comparison is only indicative).
 *
 * <h3>Suggested fix</h3>
 * In {@code FrameworkExtensions.attach} skip {@code addExtensionContent} when
 * {@code ConnectVirtualFile.isConnectFile(revContent.getVirtualFile())} (still mark the revision as attached), and load
 * {@code ExtensionBundle-Activator} classes of connect extensions from the framework class loader as is.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect and extension attachment are new in this branch).
 */
public class ConnectExtensionModifiesFrameworkIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:extension";
    private static final String MARKER = "connectext/marker.txt";

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        stopFramework();
    }

    private void stopFramework() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConnectExtensionContentIsNotAddedToTheFramework() throws Exception {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                if (LOCATION.equals(location) == false)
                    return Optional.empty();
                return Optional.<ConnectModule> of(new ConnectModule() {
                    @Override
                    public ConnectContent getContent() {
                        return new ExtensionContent();
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
        ConnectFrameworkFactory factory = ServiceLoader.loadService(ConnectFrameworkFactory.class);
        framework = factory.newFramework(props, connector);
        framework.start();
        framework.getBundleContext().installBundle(LOCATION);
        stopFramework();

        // The extension is attached when the framework is launched again
        props.remove(Constants.FRAMEWORK_STORAGE_CLEAN);
        framework = factory.newFramework(props, connector);
        framework.start();
        Bundle extension = framework.getBundleContext().getBundle(LOCATION);
        assertEquals("The connect extension must be resolved after the restart", Bundle.RESOLVED, extension.getState());

        BundleWiring systemWiring = framework.adapt(BundleWiring.class);
        assertNull("The content of a connect extension must not be added to the class loading of the framework, but the system bundle class loader finds its resource at "
                + systemWiring.getClassLoader().getResource(MARKER), systemWiring.getClassLoader().getResource(MARKER));
    }

    static class ExtensionContent implements ConnectContent {

        @Override
        public Optional<Map<String, String>> getHeaders() {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, "connect.extension");
            headers.put(Constants.FRAGMENT_HOST, "system.bundle; extension:=framework");
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            ArrayList<String> result = new ArrayList<String>();
            result.add("connectext/");
            result.add(MARKER);
            return result;
        }

        @Override
        public Optional<ConnectEntry> getEntry(final String path) {
            if (MARKER.equals(path) == false)
                return Optional.empty();
            return Optional.<ConnectEntry> of(new ConnectEntry() {
                @Override
                public String getName() {
                    return MARKER;
                }

                @Override
                public long getContentLength() {
                    return 1;
                }

                @Override
                public long getLastModified() {
                    return 0;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream("M".getBytes());
                }
            });
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
