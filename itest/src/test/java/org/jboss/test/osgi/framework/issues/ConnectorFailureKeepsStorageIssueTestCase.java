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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * A module connector that fails once while the framework restores a connect bundle, with a RuntimeException or a
 * BundleException, deletes the storage of the bundle with its data area: the bundle and its data are lost for good,
 * although the connector provides the content again at the next launch.
 *
 * <h2>Severity</h2>
 * minor. Only a connect framework; the data loss cannot be undone.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code StorageManagerImpl.initialize}, {@code restoreStorageState}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-08 (residual of the connect restore comment of the R8 fix review).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 60.3.5: a connect bundle whose module connector has no content for its location any more is discarded.
 * A connector that fails says nothing about the content.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/StorageManagerImpl.java:131-136, :159-165 (84ac5c2e): a
 * RuntimeException of the connector reaches the catch that deletes the directory, a BundleException gives no content.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=ConnectorFailureKeepsStorageIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The launch with the failing connector does not install the bundle and keeps its storage and data area; the next launch
 * with a healthy connector installs it with its id and data. A connector without content discards the bundle (control).
 *
 * <h2>Regression vs WF41</h2>
 * the RuntimeException: yes (3093973d); the BundleException: no.
 */
public class ConnectorFailureKeepsStorageIssueTestCase extends OSGiTest {

    private static final String LOCATION = "connect:connector-failure";
    private Framework framework;

    @After
    public void tearDown() throws Exception {
        ReleaseCheckSupport.stop(framework);
    }

    @Test
    public void testRuntimeExceptionOfConnector() throws Exception {
        assertConnectorFailureKeepsBundle("connector-failure-rt", new IllegalStateException("the backend of the connector is down"));
    }

    @Test
    public void testBundleExceptionOfConnector() throws Exception {
        assertConnectorFailureKeepsBundle("connector-failure-be", new BundleException("the backend of the connector is down"));
    }

    @Test
    public void testConnectorWithoutContentControl() throws Exception {
        File storage = ReleaseCheckSupport.storage("connector-failure-empty");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newConnectFramework(storage, new Connector(null));
        framework.start();
        long id = framework.getBundleContext().installBundle(LOCATION).getBundleId();
        ReleaseCheckSupport.stop(framework);

        framework = ReleaseCheckSupport.newConnectFramework(storage, new Connector(null) {
            @Override
            public Optional<ConnectModule> connect(String location) {
                return Optional.empty();
            }
        });
        framework.start();
        assertNull("A connect bundle without content is discarded", framework.getBundleContext().getBundle(LOCATION));
        assertFalse("Its storage is deleted", ReleaseCheckSupport.bundleDir(storage, id).exists());
    }

    private void assertConnectorFailureKeepsBundle(String name, Throwable failure) throws Exception {
        File storage = ReleaseCheckSupport.storage(name);
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newConnectFramework(storage, new Connector(null));
        framework.start();
        Bundle bundle = framework.getBundleContext().installBundle(LOCATION);
        long id = bundle.getBundleId();
        bundle.start();
        File dataFile = bundle.getBundleContext().getDataFile("keep.txt");
        Files.write(dataFile.toPath(), "data".getBytes("UTF-8"));
        ReleaseCheckSupport.stop(framework);

        // The connector fails once
        framework = ReleaseCheckSupport.newConnectFramework(storage, new Connector(failure));
        framework.start();
        assertNull("The bundle is not installed while its connector fails", framework.getBundleContext().getBundle(LOCATION));
        assertTrue("Its storage is kept", ReleaseCheckSupport.bundleDir(storage, id).isDirectory());
        assertTrue("Its data area is kept", dataFile.isFile());
        ReleaseCheckSupport.stop(framework);

        // The connector works again
        framework = ReleaseCheckSupport.newConnectFramework(storage, new Connector(null));
        framework.start();
        Bundle restored = framework.getBundleContext().getBundle(LOCATION);
        assertNotNull("The bundle is installed again", restored);
        assertEquals("With its id", id, restored.getBundleId());
        assertTrue("With its data area", dataFile.isFile());
    }

    static class Connector implements ModuleConnector {
        private final Throwable failure;

        Connector(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void initialize(File storage, Map<String, String> configuration) {
        }

        @Override
        public Optional<ConnectModule> connect(String location) throws BundleException {
            if (failure instanceof BundleException)
                throw (BundleException) failure;
            if (failure instanceof RuntimeException)
                throw (RuntimeException) failure;
            if (LOCATION.equals(location) == false)
                return Optional.empty();
            return Optional.<ConnectModule>of(new ConnectModule() {
                @Override
                public ConnectContent getContent() {
                    return new Content();
                }
            });
        }

        @Override
        public Optional<BundleActivator> newBundleActivator() {
            return Optional.empty();
        }
    }

    // The content of the connect bundle: a manifest only
    static class Content implements ConnectContent {
        private static final String MANIFEST = "META-INF/MANIFEST.MF";

        private static byte[] manifest() throws IOException {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
            manifest.getMainAttributes().putValue(Constants.BUNDLE_SYMBOLICNAME, "connector.failure");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manifest.write(out);
            return out.toByteArray();
        }

        @Override
        public Optional<Map<String, String>> getHeaders() {
            return Optional.empty();
        }

        @Override
        public Iterable<String> getEntries() {
            return Collections.singleton(MANIFEST);
        }

        @Override
        public Optional<ConnectEntry> getEntry(final String path) {
            if (MANIFEST.equals(path) == false)
                return Optional.empty();
            return Optional.<ConnectEntry>of(new ConnectEntry() {
                @Override
                public String getName() {
                    return path;
                }

                @Override
                public long getContentLength() {
                    try {
                        return manifest().length;
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }

                @Override
                public long getLastModified() {
                    return 0;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return new ByteArrayInputStream(manifest());
                }
            });
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            return Optional.of(Content.class.getClassLoader());
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }
    }
}
