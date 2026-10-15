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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.support.ExtensionActivator;
import org.jboss.test.osgi.framework.launch.support.VersionedType;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.framework.wiring.dto.BundleWiringDTO;
import org.osgi.framework.wiring.dto.FrameworkWiringDTO;

/**
 * Test framework extension bundles.
 *
 * @since 11-Oct-2026
 */
public class FrameworkExtensionTestCase extends AbstractFrameworkLaunchTest {

    private static final String TYPE_NAME = VersionedType.class.getName();
    private static final String PACKAGE_NAME = VersionedType.class.getPackage().getName();

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
        System.getProperties().remove(ExtensionActivator.EVENTS);
        System.getProperties().remove(ExtensionActivator.FAIL_ON_START);
    }

    @Test
    public void testExtensionIsPartOfTheFramework() throws Exception {
        startFramework();
        assertEquals("true", framework.getBundleContext().getProperty(Constants.SUPPORTS_FRAMEWORK_EXTENSION));

        Bundle extension = install("extension", extensionHeaders("system.bundle; extension:=framework"), VersionedType.class);
        assertTrue(resolve(extension));
        assertEquals(Bundle.RESOLVED, extension.getState());

        // The extension is attached to the system bundle
        List<BundleWire> hostWires = extension.adapt(BundleWiring.class).getRequiredWires(HostNamespace.HOST_NAMESPACE);
        assertEquals(1, hostWires.size());
        assertEquals(framework.getBundleContext().getBundle(0), hostWires.get(0).getProviderWiring().getBundle());

        // Its classes are loaded by the class loader of the framework implementation, as if the extension was on its
        // class path (Core 3.15), not by the extension bundle
        Class<?> clazz = framework.loadClass(TYPE_NAME);
        assertEquals("versioned", clazz.getConstructor().newInstance().toString());
        assertSame(org.jboss.osgi.framework.spi.BundleManager.class.getClassLoader(), clazz.getClassLoader());
        try {
            extension.loadClass(TYPE_NAME);
            fail("ClassNotFoundException expected");
        } catch (ClassNotFoundException ex) {
            // expected
        }

        // The classes of the extension can use the classes of the framework implementation
        assertNotNull(clazz.getClassLoader().loadClass("org.jboss.osgi.framework.spi.BundleManager"));
    }

    @Test
    public void testDefaultExtensionType() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));
        assertNotNull(framework.loadClass(TYPE_NAME));
    }

    @Test
    public void testImportPackageOfExtension() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.IMPORT_PACKAGE, PACKAGE_NAME);
        Bundle importer = install("importer", headers, null);
        importer.start();

        List<BundleWire> wires = importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
        assertEquals(1, wires.size());
        assertSame(framework.loadClass(TYPE_NAME), importer.loadClass(TYPE_NAME));
    }

    @Test
    public void testDynamicImportOfExtensionPackage() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.DYNAMICIMPORT_PACKAGE, PACKAGE_NAME);
        Bundle importer = install("importer", headers, null);
        importer.start();
        assertTrue(importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).isEmpty());

        // The package is provided by the system bundle
        assertSame(framework.loadClass(TYPE_NAME), importer.loadClass(TYPE_NAME));
        List<BundleWire> wires = importer.adapt(BundleWiring.class).getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE);
        assertEquals(1, wires.size());
        assertEquals(framework.getBundleContext().getBundle(0), wires.get(0).getProviderWiring().getBundle());
    }

    @Test
    public void testExtensionInstalledBeforeImporterResolves() throws Exception {
        startFramework();

        // The importer cannot resolve without the extension
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.IMPORT_PACKAGE, PACKAGE_NAME);
        Bundle importer = install("importer", headers, null);
        assertTrue(resolve(importer) == false);

        // Installing the extension is enough, no framework restart is needed
        install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        importer.start();
        assertSame(framework.loadClass(TYPE_NAME), importer.loadClass(TYPE_NAME));
    }

    @Test
    public void testExtensionRequirements() throws Exception {
        startFramework();

        // A requirement that the system bundle satisfies
        Map<String, String> headers = extensionHeaders("system.bundle");
        headers.put(Constants.REQUIRE_CAPABILITY, "osgi.ee; filter:=\"(osgi.ee=JavaSE)\"");
        headers.put(Constants.IMPORT_PACKAGE, "org.osgi.framework");
        Bundle satisfied = install("satisfied", headers, VersionedType.class);
        assertTrue(resolve(satisfied));

        // A requirement that only an ordinary bundle satisfies
        Map<String, String> provider = new LinkedHashMap<String, String>();
        provider.put(Constants.PROVIDE_CAPABILITY, "test.cap");
        install("provider", provider, null).start();
        headers = new LinkedHashMap<String, String>();
        headers.put(Constants.FRAGMENT_HOST, "system.bundle");
        headers.put(Constants.REQUIRE_CAPABILITY, "test.cap");
        Bundle unsatisfied = install("unsatisfied", headers, null);
        assertTrue(resolve(unsatisfied) == false);
        assertEquals(Bundle.INSTALLED, unsatisfied.getState());
    }

    @Test
    public void testIllegalHeaders() throws Exception {
        startFramework();
        for (String header : Arrays.asList(Constants.REQUIRE_BUNDLE, Constants.DYNAMICIMPORT_PACKAGE, Constants.BUNDLE_ACTIVATOR)) {
            Map<String, String> headers = extensionHeaders("system.bundle");
            headers.put(header, "some.value");
            try {
                install("illegal-" + header, headers, null);
                fail("BundleException expected for " + header);
            } catch (BundleException ex) {
                // expected
            }
        }
    }

    @Test
    public void testActivatorOfDynamicallyResolvedExtension() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.init();
        final List<String> bundleEvents = new ArrayList<String>();
        framework.getBundleContext().addBundleListener(new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getType() == BundleEvent.RESOLVED)
                    bundleEvents.add("resolved:" + ExtensionActivator.getEvents().size());
            }
        });

        Bundle extension = install("extension", activatorHeaders(), ExtensionActivator.class);
        assertTrue(ExtensionActivator.getEvents().isEmpty());
        assertTrue(resolve(extension));

        // The activator is started with the system bundle context, before the RESOLVED event
        assertEquals(Arrays.asList("start:bundle=0,state=" + Bundle.STARTING), ExtensionActivator.getEvents());
        assertEquals(Arrays.asList("resolved:1"), bundleEvents);
        ServiceReference<String> sref = framework.getBundleContext().getServiceReference(String.class);
        assertNotNull(sref);
        assertEquals(framework.getBundleContext().getBundle(0), sref.getBundle());

        // The start level cannot be changed before the framework is started
        try {
            framework.adapt(FrameworkStartLevel.class).setStartLevel(2);
            fail("IllegalStateException expected");
        } catch (IllegalStateException ex) {
            // expected
        }

        framework.start();
        framework.stop();
        framework.waitForStop(10000);
        assertEquals(Arrays.asList("start:bundle=0,state=" + Bundle.STARTING, "stop:bundle=0,state=" + Bundle.STOPPING), ExtensionActivator.getEvents());
    }

    @Test
    public void testActivatorAtFrameworkInit() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.init();
        Bundle extension = install("extension", activatorHeaders(), ExtensionActivator.class);
        Bundle other = install("other", new LinkedHashMap<String, String>(), null);
        long extensionId = extension.getBundleId();
        framework.stop();
        framework.waitForStop(10000);
        assertTrue("Not resolved, not started", ExtensionActivator.getEvents().isEmpty());

        // The installed extension is resolved when the framework is initialized, other bundles are not
        framework = newFramework(getFrameworkInitProperties(false));
        framework.init();
        extension = framework.getBundleContext().getBundle(extensionId);
        assertNotNull(extension);
        assertEquals(Bundle.RESOLVED, extension.getState());
        assertEquals(Bundle.INSTALLED, framework.getBundleContext().getBundle(other.getBundleId()).getState());
        assertEquals(Arrays.asList("start:bundle=0,state=" + Bundle.STARTING), ExtensionActivator.getEvents());
        assertNotNull(framework.getBundleContext().getServiceReference(String.class));

        framework.start();
        framework.stop();
        framework.waitForStop(10000);
        assertEquals(2, ExtensionActivator.getEvents().size());
    }

    @Test
    public void testActivatorFailure() throws Exception {
        System.setProperty(ExtensionActivator.FAIL_ON_START, "true");
        final List<FrameworkEvent> errors = Collections.synchronizedList(new ArrayList<FrameworkEvent>());
        FrameworkListener listener = new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR)
                    errors.add(event);
            }
        };
        framework = newFramework(getFrameworkInitProperties(true));
        framework.init();
        framework.getBundleContext().addFrameworkListener(listener);
        Bundle extension = install("extension", activatorHeaders(), ExtensionActivator.class);
        long extensionId = extension.getBundleId();
        assertTrue(resolve(extension));

        // The failure is reported as an error of the extension bundle, the extension stays resolved
        assertEquals(Bundle.RESOLVED, extension.getState());
        assertEquals(1, errors.size());
        assertEquals(extension, errors.get(0).getBundle());
        assertTrue(errors.get(0).getThrowable() instanceof BundleException);
        assertEquals(BundleException.ACTIVATOR_ERROR, ((BundleException) errors.get(0).getThrowable()).getType());

        // An activator that failed to start is not stopped
        framework.stop();
        framework.waitForStop(10000);
        assertEquals(1, ExtensionActivator.getEvents().size());

        // A listener that is given to init gets the errors of the initialization before init returns
        errors.clear();
        framework = newFramework(getFrameworkInitProperties(false));
        framework.init(listener);
        assertEquals(1, errors.size());
        assertEquals(extensionId, errors.get(0).getBundle().getBundleId());
    }

    @Test
    public void testInitListenerIsRemoved() throws Exception {
        final List<FrameworkEvent> events = Collections.synchronizedList(new ArrayList<FrameworkEvent>());
        framework = newFramework(getFrameworkInitProperties(true));
        framework.init(new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                events.add(event);
            }
        });
        events.clear();

        // The listener is no longer notified after init returned
        framework.start();
        framework.stop();
        framework.waitForStop(10000);
        assertTrue("No events: " + events, events.isEmpty());
    }

    @Test
    public void testRefreshStopsTheFramework() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));

        // An uninstalled extension stays attached
        extension.uninstall();
        assertNotNull(framework.loadClass(TYPE_NAME));
        assertEquals(Bundle.ACTIVE, framework.getState());

        framework.adapt(FrameworkWiring.class).refreshBundles(Arrays.asList(extension));
        FrameworkEvent event = framework.waitForStop(10000);
        assertEquals(FrameworkEvent.STOPPED_SYSTEM_REFRESHED, event.getType());

        // The extension is gone when the framework is launched again
        framework = newFramework(getFrameworkInitProperties(false));
        framework.start();
        assertTrue(framework.adapt(BundleWiring.class).getProvidedWires(HostNamespace.HOST_NAMESPACE).isEmpty());
        try {
            framework.loadClass(TYPE_NAME);
            fail("ClassNotFoundException expected");
        } catch (ClassNotFoundException ex) {
            // expected
        }
    }

    @Test
    public void testUpdatedExtensionStaysAttached() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));
        Class<?> clazz = framework.loadClass(TYPE_NAME);

        // The new revision is not attached before the framework is launched again
        extension.update(new ByteArrayInputStream(createBundle("extension", extensionHeaders("system.bundle"), VersionedType.class)));
        resolve(extension);
        assertSame(clazz, framework.loadClass(TYPE_NAME));
        assertEquals(1, framework.adapt(BundleWiring.class).getProvidedWires(HostNamespace.HOST_NAMESPACE).size());
    }

    @Test
    public void testWiresAreHostedBySystemBundle() throws Exception {
        startFramework();
        Map<String, String> headers = extensionHeaders("system.bundle");
        headers.put(Constants.PROVIDE_CAPABILITY, "test.cap; test.cap=one");
        Bundle provider = install("provider", headers, VersionedType.class);

        headers = new LinkedHashMap<String, String>();
        headers.put(Constants.FRAGMENT_HOST, "system.bundle");
        headers.put(Constants.REQUIRE_CAPABILITY, "test.cap; filter:=\"(test.cap=one)\", osgi.ee; filter:=\"(osgi.ee=JavaSE)\"");
        headers.put(Constants.IMPORT_PACKAGE, PACKAGE_NAME + ", org.osgi.framework");
        Bundle requirer = install("requirer", headers, null);

        // The extensions resolve together, the one with the lower bundle id first
        assertTrue(framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(requirer, provider)));

        BundleWiring syswiring = framework.adapt(BundleWiring.class);
        BundleWiring reqwiring = requirer.adapt(BundleWiring.class);
        assertEquals(2, syswiring.getProvidedWires(HostNamespace.HOST_NAMESPACE).size());

        // The host and execution environment requirements stay with the extension
        assertEquals(1, reqwiring.getRequiredWires(HostNamespace.HOST_NAMESPACE).size());
        assertEquals(1, reqwiring.getRequiredWires("osgi.ee").size());
        assertTrue(reqwiring.getRequiredWires("test.cap").isEmpty());

        // Other requirements and capabilities are hosted by the system bundle
        List<BundleWire> wires = syswiring.getRequiredWires("test.cap");
        assertEquals(1, wires.size());
        assertEquals(syswiring, wires.get(0).getRequirerWiring());
        assertEquals(syswiring, wires.get(0).getProviderWiring());
        assertEquals(requirer.adapt(BundleRevision.class), wires.get(0).getRequirement().getRevision());
        assertEquals(provider.adapt(BundleRevision.class), wires.get(0).getCapability().getRevision());
        assertEquals(wires, syswiring.getProvidedWires("test.cap"));
        assertEquals(1, syswiring.getCapabilities("test.cap").size());
        assertEquals(1, syswiring.getRequirements("test.cap").size());

        // The DTOs can represent the hosted wires
        assertNotNull(framework.getBundleContext().getBundle(0).adapt(FrameworkWiringDTO.class));
        assertNotNull(requirer.adapt(BundleWiringDTO.class));

        // The system bundle only exports packages
        assertTrue(syswiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).isEmpty());
        assertTrue(reqwiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE).isEmpty());
    }

    @Test
    public void testOnlyOneVersionIsAttached() throws Exception {
        startFramework();
        Map<String, String> headers = extensionHeaders("system.bundle");
        headers.put(Constants.BUNDLE_VERSION, "1.0.0");
        Bundle v1 = framework.getBundleContext().installBundle("v1", new ByteArrayInputStream(createBundle("extension", headers, VersionedType.class)));
        assertTrue(resolve(v1));

        headers.put(Constants.BUNDLE_VERSION, "2.0.0");
        Bundle v2 = framework.getBundleContext().installBundle("v2", new ByteArrayInputStream(createBundle("extension", headers, VersionedType.class)));
        assertTrue(resolve(v2) == false);
        assertEquals(Bundle.INSTALLED, v2.getState());
        assertEquals(1, framework.adapt(BundleWiring.class).getProvidedWires(HostNamespace.HOST_NAMESPACE).size());
    }

    @Test
    public void testRefreshOfOtherBundlesKeepsTheFrameworkRunning() throws Exception {
        startFramework();
        Bundle extension = install("extension", extensionHeaders("system.bundle"), VersionedType.class);
        assertTrue(resolve(extension));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.EXPORT_PACKAGE, "some.pkg");
        Bundle exporter = install("exporter", headers, ExtensionActivator.class);
        headers = new LinkedHashMap<String, String>();
        headers.put(Constants.IMPORT_PACKAGE, "some.pkg, " + PACKAGE_NAME);
        Bundle importer = install("importer", headers, null);
        importer.start();

        // A removal pending bundle that is not an extension is refreshed as usual
        exporter.uninstall();
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkWiring.class).refreshBundles(null, new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(Bundle.ACTIVE, framework.getState());
        assertEquals(Bundle.RESOLVED, extension.getState());
        assertNotNull(framework.loadClass(TYPE_NAME));
    }

    @Test
    public void testExtensionAfterFrameworkUpdate() throws Exception {
        startFramework();
        Bundle extension = install("extension", activatorHeaders(), ExtensionActivator.class);
        assertTrue(resolve(extension));
        assertEquals(1, ExtensionActivator.getEvents().size());

        // The activator is stopped and started again with the framework, which updates on another thread
        framework.update();
        assertEquals(FrameworkEvent.STOPPED_UPDATE, framework.waitForStop(10000).getType());
        for (int i = 0; i < 100 && framework.getState() != Bundle.ACTIVE; i++)
            Thread.sleep(100);
        assertEquals(Bundle.ACTIVE, framework.getState());
        assertEquals(3, ExtensionActivator.getEvents().size());
        assertTrue(ExtensionActivator.getEvents().get(1).startsWith("stop:"));
        assertEquals(1, framework.adapt(BundleWiring.class).getProvidedWires(HostNamespace.HOST_NAMESPACE).size());
    }

    private Map<String, String> activatorHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.FRAGMENT_HOST, "system.bundle");
        headers.put(Constants.EXTENSION_BUNDLE_ACTIVATOR, ExtensionActivator.class.getName());
        return headers;
    }

    private void startFramework() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
    }

    private boolean resolve(Bundle bundle) {
        return framework.adapt(FrameworkWiring.class).resolveBundles(Arrays.asList(bundle));
    }

    private Map<String, String> extensionHeaders(String fragmentHost) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(Constants.FRAGMENT_HOST, fragmentHost);
        headers.put(Constants.EXPORT_PACKAGE, PACKAGE_NAME);
        return headers;
    }

    private Bundle install(String name, Map<String, String> headers, Class<?> content) throws Exception {
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(createBundle(name, headers, content)));
    }

    private byte[] createBundle(String name, Map<String, String> headers, Class<?> content) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, name.replace('-', '.'));
        for (Map.Entry<String, String> entry : headers.entrySet())
            attributes.putValue(entry.getKey(), entry.getValue());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(baos, manifest);
        if (content != null) {
            jos.putNextEntry(new JarEntry(content.getName().replace('.', '/') + ".class"));
            jos.write(getClassBytes(content));
            jos.closeEntry();
        }
        jos.close();
        return baos.toByteArray();
    }

    private byte[] getClassBytes(Class<?> clazz) throws IOException {
        InputStream input = clazz.getResourceAsStream(clazz.getSimpleName() + ".class");
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int len = input.read(buffer); len > 0; len = input.read(buffer))
                baos.write(buffer, 0, len);
            return baos.toByteArray();
        } finally {
            input.close();
        }
    }
}
