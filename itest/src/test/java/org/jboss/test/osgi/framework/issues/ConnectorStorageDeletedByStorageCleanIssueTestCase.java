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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * With {@code org.osgi.framework.storage.clean=onFirstInit} the framework deletes the storage area after it has
 * handed it to {@link ModuleConnector#initialize(File, Map)}, so whatever the module connector stored there during
 * initialization is lost.
 *
 * <h3>Severity</h3>
 * minor. Only connect frameworks launched with {@code storage.clean=onFirstInit} are affected (a common launcher
 * setting), and only connectors that persist something in the framework storage during {@code initialize}; they
 * silently lose that data on every clean launch, and the directory they were given may not even exist afterwards.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkProxy.init},
 * {@code StorageManagerImpl.initialize}.
 *
 * <h3>Found by</h3>
 * connect workstream, review of the init sequence plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.2.2 Initializing the Module Connector
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html): "Once the framework instance has
 * determined the storage area the ModuleConnector method initialize(File,Map) must be called. The file is the storage
 * area used by the Framework ... This file may be used by the module connector for persistent storage."<br>
 * OSGi Core R8, 4.2.2 Launching Properties, {@code org.osgi.framework.storage.clean}: "onFirstInit - The framework
 * storage area will be cleaned before the Framework bundle is initialized for the first time."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:156} calls
 * {@code connector.initialize(storage, ...)}; only afterwards line 160 installs the CREATE services, whose
 * {@code StorageManagerPlugin} runs {@code StorageManagerImpl.initialize}
 * ({@code core/src/main/java/org/jboss/osgi/framework/internal/StorageManagerImpl.java:84-87}), which deletes the whole
 * storage area recursively. Introduced by 3680cfcc "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * Wrong order in {@code FrameworkProxy.init}: the storage area is cleaned after the connector was initialized with it,
 * instead of before.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectorStorageDeletedByStorageCleanIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: The file that the module connector wrote into the framework storage area during
 *   initialize must still exist after init(), but the storage clean deleted it
 * </pre>
 *
 * <h3>Expected</h3>
 * The storage area is cleaned first, then the connector is initialized with it; the connector's file survives init().
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: same scenario, the connector's file exists after {@code init()}.
 *
 * <h3>Suggested fix</h3>
 * Call {@code connector.initialize} after the CREATE phase has cleaned the storage area (e.g. from the
 * {@code StorageManagerPlugin} right after {@code StorageManagerImpl.initialize} cleaned it and before it restores
 * connect bundles), or clean the storage area in {@code FrameworkProxy.init} before calling {@code initialize}.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectorStorageDeletedByStorageCleanIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConnectorFileSurvivesStorageClean() throws Exception {
        final File[] connectorFile = new File[1];
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
                // A connector may use the storage area for persistent storage
                File dir = new File(storage, "connector");
                dir.mkdirs();
                connectorFile[0] = new File(dir, "index.txt");
                try {
                    Files.write(connectorFile[0].toPath(), "index".getBytes());
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                return Optional.empty();
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
        framework.init();

        assertTrue("The file that the module connector wrote into the framework storage area during initialize must still exist after init(), but the storage clean deleted it",
                connectorFile[0] != null && connectorFile[0].isFile());
    }
}
