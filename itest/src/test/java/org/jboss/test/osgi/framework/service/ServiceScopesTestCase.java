package org.jboss.test.osgi.framework.service;
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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * Test the service scopes and {@link ServiceObjects}.
 *
 * @since 11-Oct-2026
 */
public class ServiceScopesTestCase extends OSGiFrameworkTest {

    @Test
    public void testScopeProperty() throws Exception {
        BundleContext context = getSystemContext();

        // The framework sets the scope, a value given by the registrant is ignored
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put(Constants.SERVICE_SCOPE, Constants.SCOPE_PROTOTYPE);
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), props);
        try {
            assertEquals(Constants.SCOPE_SINGLETON, sreg.getReference().getProperty(Constants.SERVICE_SCOPE));

            // The scope is retained when the properties are replaced
            sreg.setProperties(new Hashtable<String, Object>());
            assertEquals(Constants.SCOPE_SINGLETON, sreg.getReference().getProperty(Constants.SERVICE_SCOPE));
        } finally {
            sreg.unregister();
        }

        ServiceFactory<Runnable> factory = new ServiceFactory<Runnable>() {
            public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
                return new TestService();
            }

            public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
            }
        };
        sreg = context.registerService(Runnable.class, factory, null);
        try {
            assertEquals(Constants.SCOPE_BUNDLE, sreg.getReference().getProperty(Constants.SERVICE_SCOPE));
        } finally {
            sreg.unregister();
        }

        sreg = context.registerService(Runnable.class, new TestPrototypeFactory(), null);
        try {
            assertEquals(Constants.SCOPE_PROTOTYPE, sreg.getReference().getProperty(Constants.SERVICE_SCOPE));
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testServiceObjectsSingleton() throws Exception {
        BundleContext context = getSystemContext();
        Runnable service = new TestService();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, service, null);
        ServiceReference<Runnable> sref = sreg.getReference();
        try {
            ServiceObjects<Runnable> objects = context.getServiceObjects(sref);
            assertNotNull(objects);
            assertEquals(sref, objects.getServiceReference());

            // The use count is shared with the bundle context
            assertSame(service, objects.getService());
            assertSame(service, context.getService(sref));
            objects.ungetService(service);
            assertTrue(context.ungetService(sref));

            assertSame(service, objects.getService());
            try {
                objects.ungetService(new TestService());
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ex) {
                // expected
            }
            objects.ungetService(service);
        } finally {
            sreg.unregister();
        }
        assertNull("No service objects for an unregistered service", context.getServiceObjects(sref));
    }

    @Test
    public void testServiceObjectsPrototype() throws Exception {
        BundleContext context = getSystemContext();
        TestPrototypeFactory factory = new TestPrototypeFactory();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, factory, null);
        try {
            ServiceReference<Runnable> sref = sreg.getReference();

            // The bundle context gives one service object per bundle
            Runnable service = context.getService(sref);
            assertNotNull(service);
            assertSame(service, context.getService(sref));
            assertEquals(1, factory.services.size());

            // The service objects give a new service object on every call
            ServiceObjects<Runnable> objects = context.getServiceObjects(sref);
            Runnable service1 = objects.getService();
            Runnable service2 = objects.getService();
            assertNotSame(service, service1);
            assertNotSame(service, service2);
            assertNotSame(service1, service2);
            assertEquals(3, factory.services.size());

            // The service object of the bundle context cannot be released through the service objects
            try {
                objects.ungetService(service);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ex) {
                // expected
            }

            objects.ungetService(service1);
            assertEquals(2, factory.services.size());
            try {
                objects.ungetService(service1);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ex) {
                // expected
            }
            objects.ungetService(service2);
            assertEquals(1, factory.services.size());

            assertTrue(context.ungetService(sref));
            assertTrue(context.ungetService(sref));
            assertEquals(0, factory.services.size());
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testPrototypeServiceObjectUseCount() throws Exception {
        BundleContext context = getSystemContext();
        final Runnable service = new TestService();
        final List<Runnable> released = new ArrayList<Runnable>();
        PrototypeServiceFactory<Runnable> factory = new PrototypeServiceFactory<Runnable>() {
            public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
                // The factory may return the same service object more than once
                return service;
            }

            public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
                released.add(service);
            }
        };
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, factory, null);
        try {
            ServiceObjects<Runnable> objects = context.getServiceObjects(sreg.getReference());
            assertSame(service, objects.getService());
            assertSame(service, objects.getService());
            assertSame(service, objects.getService());

            // The service object is handed back when its use count drops to zero
            objects.ungetService(service);
            objects.ungetService(service);
            assertTrue(released.isEmpty());
            objects.ungetService(service);
            assertEquals(1, released.size());
            assertSame(service, released.get(0));

            try {
                objects.ungetService(service);
                fail("IllegalArgumentException expected");
            } catch (IllegalArgumentException ex) {
                // expected
            }
            assertEquals(1, released.size());
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testReleaseOnBundleStop() throws Exception {
        TestPrototypeFactory factory = new TestPrototypeFactory();
        ServiceRegistration<Runnable> sreg = getSystemContext().registerService(Runnable.class, factory, null);
        Bundle bundle = installBundle(getBundleArchive("scopes-stop"));
        try {
            bundle.start();
            BundleContext context = bundle.getBundleContext();
            ServiceReference<Runnable> sref = context.getServiceReference(Runnable.class);
            assertNotNull(sref);

            assertNotNull(context.getService(sref));
            ServiceObjects<Runnable> objects = context.getServiceObjects(sref);
            assertNotNull(objects.getService());
            assertNotNull(objects.getService());
            assertEquals(3, factory.services.size());
            assertUsingBundles(sref, bundle);

            // Stopping the bundle releases all service objects it still holds
            bundle.stop();
            assertEquals(0, factory.services.size());
            assertUsingBundles(sref);
        } finally {
            bundle.uninstall();
            sreg.unregister();
        }
    }

    @Test
    public void testReleaseOnUnregister() throws Exception {
        TestPrototypeFactory factory = new TestPrototypeFactory();
        ServiceRegistration<Runnable> sreg = getSystemContext().registerService(Runnable.class, factory, null);
        Bundle bundle = installBundle(getBundleArchive("scopes-unregister"));
        try {
            bundle.start();
            BundleContext context = bundle.getBundleContext();
            ServiceReference<Runnable> sref = context.getServiceReference(Runnable.class);
            assertNotNull(sref);

            assertNotNull(context.getService(sref));
            ServiceObjects<Runnable> objects = context.getServiceObjects(sref);
            assertNotNull(objects.getService());
            assertNotNull(objects.getService());
            assertEquals(3, factory.services.size());

            // Unregistering the service releases all service objects
            sreg.unregister();
            sreg = null;
            assertEquals(0, factory.services.size());
            assertNull(objects.getService());
        } finally {
            bundle.uninstall();
            if (sreg != null)
                sreg.unregister();
        }
    }

    private JavaArchive getBundleArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive;
    }

    static class TestService implements Runnable {
        public void run() {
        }
    }

    static class TestPrototypeFactory implements PrototypeServiceFactory<Runnable> {

        final List<Runnable> services = new ArrayList<Runnable>();

        public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
            Runnable service = new TestService();
            services.add(service);
            return service;
        }

        public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
            assertTrue("Service object was provided by this factory", services.remove(service));
        }
    }
}
