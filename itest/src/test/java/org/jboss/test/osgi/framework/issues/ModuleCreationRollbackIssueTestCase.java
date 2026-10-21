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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A resolve that fails while it creates the modules of the resolved bundles keeps the modules it created before the
 * failure: the framework is left partly modified.
 *
 * <h2>Severity</h2>
 * minor. In WildFly the module services that are left behind make the next resolve of the bundles fail with a
 * DuplicateServiceException.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkResolverImpl.applyResolverResults}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of fuzz/50 (fw-major).
 *
 * <h2>Spec reference</h2>
 * FrameworkWiring.resolveBundles: bundles that cannot be resolved stay INSTALLED and can be resolved later.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkResolverImpl.java:403-450 (a TODO).
 *
 * <h2>Root cause</h2>
 * The fragments, module specs and module services were not removed when a later step failed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ModuleCreationRollbackIssueTestCase
 * </pre>
 * The class loader of one of two connect bundles cannot be obtained while their modules are created.
 *
 * <h2>Expected</h2>
 * The failed resolve leaves both bundles INSTALLED, both resolve once the class loader can be obtained.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class ModuleCreationRollbackIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testResolveAfterFailedModuleCreation() throws Exception {
        final AtomicBoolean failing = new AtomicBoolean();
        final Map<String, ConnectModule> modules = new HashMap<String, ConnectModule>();
        modules.put("connect:rollback-a", module("rollback.a", null));
        modules.put("connect:rollback-b", module("rollback.b", failing));
        ModuleConnector connector = new ModuleConnector() {
            public void initialize(File storage, Map<String, String> configuration) {
            }

            public Optional<ConnectModule> connect(String location) {
                return Optional.ofNullable(modules.get(location));
            }

            public Optional<BundleActivator> newBundleActivator() {
                return Optional.empty();
            }
        };
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/module-creation-rollback").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
        framework.start();

        Bundle bundleA = framework.getBundleContext().installBundle("connect:rollback-a");
        Bundle bundleB = framework.getBundleContext().installBundle("connect:rollback-b");
        FrameworkWiring wiring = framework.adapt(FrameworkWiring.class);

        failing.set(true);
        assertFalse("The resolve fails", wiring.resolveBundles(Arrays.asList(bundleA, bundleB)));
        assertEquals("A stays INSTALLED", Bundle.INSTALLED, bundleA.getState());
        assertEquals("B stays INSTALLED", Bundle.INSTALLED, bundleB.getState());

        failing.set(false);
        assertTrue("Both bundles resolve later", wiring.resolveBundles(Arrays.asList(bundleA, bundleB)));
        assertTrue("The entry of A is found", bundleA.getResource("rollback.a.txt") != null || bundleA.getEntry("rollback.a.txt") != null);
    }

    private static ConnectModule module(final String symbolicName, final AtomicBoolean failing) {
        final ConnectContent content = new ConnectContent() {
            public Optional<Map<String, String>> getHeaders() {
                Map<String, String> headers = new LinkedHashMap<String, String>();
                headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
                headers.put(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
                return Optional.of(headers);
            }

            public Iterable<String> getEntries() throws IOException {
                return Collections.singletonList(symbolicName + ".txt");
            }

            public Optional<ConnectEntry> getEntry(String path) {
                if (path.equals(symbolicName + ".txt") == false)
                    return Optional.empty();
                return Optional.<ConnectEntry> of(new ConnectEntry() {
                    public String getName() {
                        return symbolicName + ".txt";
                    }

                    public long getContentLength() {
                        return 1;
                    }

                    public long getLastModified() {
                        return 0;
                    }

                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(new byte[] { 'x' });
                    }
                });
            }

            public Optional<ClassLoader> getClassLoader() {
                if (failing != null && failing.get())
                    throw new IllegalStateException("No class loader for " + symbolicName);
                return Optional.empty();
            }

            public void open() {
            }

            public void close() {
            }
        };
        return new ConnectModule() {
            public ConnectContent getContent() {
                return content;
            }
        };
    }
}
