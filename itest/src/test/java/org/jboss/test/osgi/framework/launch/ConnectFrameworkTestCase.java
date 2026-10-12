package org.jboss.test.osgi.framework.launch;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.connect.ConnectContent;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;

/**
 * Test a framework that gets the content of bundles from a module connector.
 *
 * @since 11-Oct-2026
 */
public class ConnectFrameworkTestCase extends AbstractFrameworkLaunchTest {

    private static final String RESOURCE_PATH = "org/jboss/test/connect/resource.txt";

    private TestConnector connector;
    private Framework framework;

    @Before
    public void setUp() throws Exception {
        connector = new TestConnector();
        connector.contents.put("connect:a", new TestContent("connect.a", null));
        connector.contents.put("connect:b", new TestContent("connect.b", getClass().getClassLoader()));
    }

    @After
    public void tearDown() throws Exception {
        stopFramework();
    }

    private Framework newConnectFramework(boolean cleanOnFirstInit) {
        ConnectFrameworkFactory factory = ServiceLoader.loadService(ConnectFrameworkFactory.class);
        return framework = factory.newFramework(getFrameworkInitProperties(cleanOnFirstInit), connector);
    }

    private void stopFramework() throws Exception {
        if (framework != null && framework.getState() != Bundle.INSTALLED && framework.getState() != Bundle.RESOLVED) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testConnectorLifecycle() throws Exception {
        Map<String, String> props = getFrameworkInitProperties(true);
        newConnectFramework(true);
        framework.init();
        assertEquals("Initialized once", 1, connector.initialized.size());
        assertEquals(new File(props.get(Constants.FRAMEWORK_STORAGE)), connector.initialized.get(0));
        assertEquals("Configuration", props.get(Constants.FRAMEWORK_STORAGE), connector.configuration.get(Constants.FRAMEWORK_STORAGE));
        try {
            connector.configuration.put("some", "value");
            fail("Unmodifiable configuration expected");
        } catch (UnsupportedOperationException ex) {
            // expected
        }
        assertEquals("Activator started", 1, connector.started);
        assertEquals("Activator not stopped", 0, connector.stopped);

        framework.start();
        stopFramework();
        assertEquals("Activator stopped", 1, connector.stopped);

        // The connector is initialized once, its activator is started with every init
        framework.start();
        assertEquals("Initialized once", 1, connector.initialized.size());
        assertEquals("Activator started again", 2, connector.started);
    }

    @Test
    public void testConnectBundle() throws Exception {
        newConnectFramework(true);
        framework.start();
        BundleContext context = framework.getBundleContext();

        // With an input stream the bundle is installed from the stream, the connector is not asked
        try {
            context.installBundle("connect:a", new ByteArrayInputStream(new byte[0]));
            fail("BundleException expected");
        } catch (BundleException ex) {
            // expected
        }
        assertEquals("Connector not asked", 0, connector.connected.size());

        Bundle bundle = context.installBundle("connect:a");
        assertEquals(Collections.singletonList("connect:a"), connector.connected);
        assertEquals("connect.a", bundle.getSymbolicName());
        TestContent content = connector.contents.get("connect:a");
        assertTrue("Content open", content.open);

        // The content is not copied to the storage area
        File bundleDir = new File(getBundleStorageDir(), "bundle-" + bundle.getBundleId());
        assertTrue("Storage directory exists", bundleDir.isDirectory());
        assertEquals("No stored content", 0, bundleDir.listFiles((dir, name) -> name.endsWith(".jar")).length);

        URL entry = bundle.getEntry(RESOURCE_PATH);
        assertNotNull("Entry found", entry);
        assertEquals("/" + RESOURCE_PATH, entry.getPath());
        URLConnection connection = entry.openConnection();
        assertEquals(content.bytes.length, connection.getContentLengthLong());
        assertEquals(4711, connection.getLastModified());
        try (InputStream input = connection.getInputStream()) {
            assertEquals("connect.a", new String(input.readAllBytes(), "UTF-8"));
        }
        assertNull(bundle.getEntry("does/not/exist"));
        assertEquals(RESOURCE_PATH, Collections.list(bundle.getEntryPaths("org/jboss/test/connect")).get(0));
        assertEquals(1, Collections.list(bundle.findEntries("/", "*.txt", true)).size());

        // The identity, bundle and host capabilities of a connect bundle are tagged
        BundleRevision brev = bundle.adapt(BundleRevision.class);
        for (String namespace : new String[] { IdentityNamespace.IDENTITY_NAMESPACE, BundleNamespace.BUNDLE_NAMESPACE, HostNamespace.HOST_NAMESPACE }) {
            BundleCapability cap = brev.getDeclaredCapabilities(namespace).get(0);
            assertEquals(namespace, Collections.singletonList(ConnectContent.TAG_OSGI_CONNECT), cap.getAttributes().get(IdentityNamespace.CAPABILITY_TAGS_ATTRIBUTE));
        }

        // A resource of the content is loaded by the framework
        bundle.start();
        URL resource = bundle.getResource(RESOURCE_PATH);
        assertNotNull("Resource found", resource);
        try (InputStream input = resource.openStream()) {
            assertEquals("connect.a", new String(input.readAllBytes(), "UTF-8"));
        }

        // A location that the connector does not know is not a connect bundle
        try {
            context.installBundle("connect:unknown");
            fail("BundleException expected");
        } catch (BundleException ex) {
            // expected
        }

        bundle.uninstall();
        assertFalse("Content closed", content.open);
    }

    @Test
    public void testUpdateBetweenConnectAndStream() throws Exception {
        newConnectFramework(true);
        framework.start();
        BundleContext context = framework.getBundleContext();

        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "connect.stream");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });

        // An update with an input stream makes a connect bundle a bundle that the framework stores
        Bundle bundle = context.installBundle("connect:a");
        TestContent content = connector.contents.get("connect:a");
        bundle.update(archive.as(ZipExporter.class).exportAsInputStream());
        assertEquals("connect.stream", bundle.getSymbolicName());
        assertNull("Not tagged", getIdentityTags(bundle));
        assertFalse("Content closed", content.open);
        File bundleDir = new File(getBundleStorageDir(), "bundle-" + bundle.getBundleId());
        assertEquals("Stored content", 1, bundleDir.listFiles((dir, name) -> name.endsWith(".jar")).length);

        // An update without an input stream asks the connector again
        bundle.update();
        assertEquals("connect.a", bundle.getSymbolicName());
        assertEquals(Collections.singletonList(ConnectContent.TAG_OSGI_CONNECT), getIdentityTags(bundle));
        assertTrue("Content open", content.open);
    }

    private Object getIdentityTags(Bundle bundle) {
        BundleRevision brev = bundle.adapt(BundleRevision.class);
        BundleCapability icap = brev.getDeclaredCapabilities(IdentityNamespace.IDENTITY_NAMESPACE).get(0);
        return icap.getAttributes().get(IdentityNamespace.CAPABILITY_TAGS_ATTRIBUTE);
    }

    @Test
    public void testContentWithoutDirectoryEntries() throws Exception {
        newConnectFramework(true);
        framework.start();
        BundleContext context = framework.getBundleContext();

        // The content only lists its files, like most jars do
        connector.contents.get("connect:a").directoryEntries = false;
        Bundle bundle = context.installBundle("connect:a");

        assertEquals(Collections.singletonList("org/"), Collections.list(bundle.getEntryPaths("/")));
        assertEquals(Collections.singletonList("org/jboss/"), Collections.list(bundle.getEntryPaths("org")));
        assertEquals(Collections.singletonList(RESOURCE_PATH), Collections.list(bundle.getEntryPaths("org/jboss/test/connect")));

        List<URL> entries = Collections.list(bundle.findEntries("/", "*", false));
        assertEquals(1, entries.size());
        assertEquals("/org/", entries.get(0).getPath());
        assertEquals(5, Collections.list(bundle.findEntries("/", "*", true)).size());
        assertNotNull("Directory entry found", bundle.getEntry("org/jboss"));
    }

    @Test
    public void testConnectClassLoader() throws Exception {
        newConnectFramework(true);
        framework.start();
        BundleContext context = framework.getBundleContext();

        // The classes of the bundle come from the class loader that the content provides
        Bundle bundle = context.installBundle("connect:b");
        assertSame(TestContent.class, bundle.loadClass(TestContent.class.getName()));
    }

    @Test
    public void testPersistentConnectBundles() throws Exception {
        newConnectFramework(true);
        framework.start();
        BundleContext context = framework.getBundleContext();
        long idA = context.installBundle("connect:a").getBundleId();
        long idB = context.installBundle("connect:b").getBundleId();
        stopFramework();
        assertFalse("Content closed", connector.contents.get("connect:a").open);

        // A connect bundle is restored for as long as the connector provides its content
        connector.contents.remove("connect:b");
        newConnectFramework(false);
        framework.init();
        context = framework.getBundleContext();
        Bundle bundle = context.getBundle(idA);
        assertNotNull("Bundle restored", bundle);
        assertEquals("connect.a", bundle.getSymbolicName());
        assertNotNull(bundle.getEntry(RESOURCE_PATH));
        assertNull("Bundle removed", context.getBundle(idB));
        assertNull("Bundle removed", context.getBundle("connect:b"));

        // An update takes the content that the connector has now
        TestContent original = connector.contents.get("connect:a");
        TestContent updated = new TestContent("connect.updated", null);
        connector.contents.put("connect:a", updated);
        bundle.update();
        assertEquals("connect.updated", bundle.getSymbolicName());
        assertNotNull(bundle.getEntry(RESOURCE_PATH));
        assertTrue("Updated content open", updated.open);
        assertFalse("Original content closed", original.open);
    }

    static class TestConnector implements ModuleConnector {

        final Map<String, TestContent> contents = new HashMap<String, TestContent>();
        final List<File> initialized = new ArrayList<File>();
        final List<String> connected = new ArrayList<String>();
        Map<String, String> configuration;
        int started;
        int stopped;

        @Override
        public void initialize(File storage, Map<String, String> configuration) {
            this.initialized.add(storage);
            this.configuration = configuration;
        }

        @Override
        public Optional<ConnectModule> connect(String location) {
            connected.add(location);
            final TestContent content = contents.get(location);
            if (content == null)
                return Optional.empty();
            return Optional.of(new ConnectModule() {
                @Override
                public ConnectContent getContent() {
                    return content;
                }
            });
        }

        @Override
        public Optional<BundleActivator> newBundleActivator() {
            return Optional.of(new BundleActivator() {
                @Override
                public void start(BundleContext context) {
                    assertNotNull(context);
                    started++;
                }

                @Override
                public void stop(BundleContext context) {
                    stopped++;
                }
            });
        }
    }

    static class TestContent implements ConnectContent {

        final Map<String, String> headers = new LinkedHashMap<String, String>();
        final ClassLoader classLoader;
        final byte[] bytes;
        boolean directoryEntries = true;
        boolean open;

        TestContent(String symbolicName, ClassLoader classLoader) throws IOException {
            this.classLoader = classLoader;
            this.bytes = symbolicName.getBytes("UTF-8");
            headers.put(Constants.BUNDLE_MANIFESTVERSION, "2");
            headers.put(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        }

        private void assertOpen() {
            if (open == false)
                throw new IllegalStateException("Not open");
        }

        @Override
        public Optional<Map<String, String>> getHeaders() {
            assertOpen();
            return Optional.of(headers);
        }

        @Override
        public Iterable<String> getEntries() {
            assertOpen();
            List<String> entries = new ArrayList<String>();
            if (directoryEntries) {
                entries.add("org/");
                entries.add("org/jboss/");
                entries.add("org/jboss/test/");
                entries.add("org/jboss/test/connect/");
            }
            entries.add(RESOURCE_PATH);
            return entries;
        }

        @Override
        public Optional<ConnectEntry> getEntry(String path) {
            assertOpen();
            if (RESOURCE_PATH.equals(path) == false)
                return Optional.empty();
            return Optional.<ConnectEntry> of(new ConnectEntry() {
                @Override
                public String getName() {
                    return RESOURCE_PATH;
                }

                @Override
                public long getContentLength() {
                    return bytes.length;
                }

                @Override
                public long getLastModified() {
                    return 4711;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            });
        }

        @Override
        public Optional<ClassLoader> getClassLoader() {
            assertOpen();
            return Optional.ofNullable(classLoader);
        }

        @Override
        public void open() {
            if (open)
                throw new IllegalStateException("Already open");
            open = true;
        }

        @Override
        public void close() {
            if (open == false)
                throw new IllegalStateException("Already closed");
            open = false;
        }
    }
}
