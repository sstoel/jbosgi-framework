package org.jboss.test.osgi.framework.xservice;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.jboss.modules.Module;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.xservice.moduleX.ModuleServiceX;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.EventListenerHook;
import org.osgi.framework.hooks.service.FindHook;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;

/**
 * Test the bundle context of a bundle that adapts a module.
 *
 * @since 11-Oct-2026
 */
public class ModuleBundleContextTestCase extends AbstractModuleIntegrationTest {

    private static final String FILTER = "(test=" + ModuleBundleContextTestCase.class.getSimpleName() + ")";

    private Module module;
    private XBundle bundle;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "moduleContext");
        archive.addClasses(ModuleServiceX.class);
        module = loadModule(archive);
        bundle = installResource(module).getBundle();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        removeModule(module);
    }

    @Test
    public void testServiceOwnership() throws Exception {
        BundleContext context = bundle.getBundleContext();
        Assert.assertNull(bundle.getRegisteredServices());
        Assert.assertNull(bundle.getServicesInUse());

        // A service that the module bundle registers belongs to it
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), getProperties());
        ServiceReference<Runnable> sref = sreg.getReference();
        Assert.assertSame(bundle, sref.getBundle());
        Assert.assertEquals(1, bundle.getRegisteredServices().length);
        Assert.assertEquals(sref, bundle.getRegisteredServices()[0]);
        Assert.assertEquals(1, getSystemContext().getServiceReferences(Runnable.class, FILTER).size());

        // A service that the module bundle uses
        ServiceRegistration<Runnable> sysreg = getSystemContext().registerService(Runnable.class, new TestService(), null);
        try {
            ServiceReference<Runnable> sysref = sysreg.getReference();
            Assert.assertNotNull(context.getService(sysref));
            Assert.assertEquals(1, bundle.getServicesInUse().length);
            Assert.assertEquals(Arrays.asList((Bundle) bundle), Arrays.asList(sysref.getUsingBundles()));

            // Stopping the bundle unregisters its services and releases the services it uses
            bundle.start();
            bundle.stop();
            Assert.assertNull(bundle.getRegisteredServices());
            Assert.assertNull(bundle.getServicesInUse());
            Assert.assertNull(sysref.getUsingBundles());
            Assert.assertTrue(getSystemContext().getServiceReferences(Runnable.class, FILTER).isEmpty());
        } finally {
            sysreg.unregister();
        }
    }

    @Test
    public void testServiceFindHook() throws Exception {
        final BundleContext context = bundle.getBundleContext();
        ServiceRegistration<Runnable> sreg = getSystemContext().registerService(Runnable.class, new TestService(), getProperties());

        // Hide all services from the module bundle
        FindHook hook = new FindHook() {
            public void find(BundleContext caller, String name, String filter, boolean allServices, Collection<ServiceReference<?>> references) {
                if (caller == context)
                    references.clear();
            }
        };
        try {
            Assert.assertEquals(1, context.getServiceReferences(Runnable.class, FILTER).size());

            ServiceRegistration<FindHook> hookReg = getSystemContext().registerService(FindHook.class, hook, null);
            try {
                Assert.assertTrue("Service hidden from the module bundle", context.getServiceReferences(Runnable.class, FILTER).isEmpty());
                Assert.assertEquals(1, getSystemContext().getServiceReferences(Runnable.class, FILTER).size());
            } finally {
                hookReg.unregister();
            }
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testBundleFindHook() throws Exception {
        final BundleContext context = bundle.getBundleContext();
        final Bundle systemBundle = getSystemContext().getBundle(0);
        org.osgi.framework.hooks.bundle.FindHook hook = new org.osgi.framework.hooks.bundle.FindHook() {
            public void find(BundleContext caller, Collection<Bundle> bundles) {
                if (caller == context)
                    bundles.remove(systemBundle);
            }
        };
        Assert.assertSame(systemBundle, context.getBundle(0));

        ServiceRegistration<org.osgi.framework.hooks.bundle.FindHook> hookReg = getSystemContext().registerService(org.osgi.framework.hooks.bundle.FindHook.class, hook, null);
        try {
            Assert.assertNull("Bundle hidden from the module bundle", context.getBundle(0));
            Assert.assertFalse(Arrays.asList(context.getBundles()).contains(systemBundle));
            Assert.assertSame(systemBundle, getSystemContext().getBundle(0));
        } finally {
            hookReg.unregister();
        }
    }

    @Test
    public void testEventListenerHook() throws Exception {
        final BundleContext context = bundle.getBundleContext();
        final List<Integer> events = new ArrayList<Integer>();
        ServiceListener listener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                events.add(event.getType());
            }
        };
        context.addServiceListener(listener, FILTER);

        getSystemContext().registerService(Runnable.class, new TestService(), getProperties()).unregister();
        Assert.assertEquals(Arrays.asList(ServiceEvent.REGISTERED, ServiceEvent.UNREGISTERING), events);

        // Hide the service events from the module bundle
        EventListenerHook hook = new EventListenerHook() {
            public void event(ServiceEvent event, Map<BundleContext, Collection<ListenerInfo>> listeners) {
                listeners.remove(context);
            }
        };
        ServiceRegistration<EventListenerHook> hookReg = getSystemContext().registerService(EventListenerHook.class, hook, null);
        try {
            getSystemContext().registerService(Runnable.class, new TestService(), getProperties()).unregister();
            Assert.assertEquals("No further events", 2, events.size());
        } finally {
            hookReg.unregister();
            context.removeServiceListener(listener);
        }
    }

    @Test
    public void testContextInvalidAfterUninstall() throws Exception {
        BundleContext context = bundle.getBundleContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), getProperties());
        Assert.assertNotNull(sreg);

        bundle.uninstall();
        Assert.assertEquals(Bundle.UNINSTALLED, bundle.getState());
        Assert.assertNull(bundle.getBundleContext());
        Assert.assertTrue("Service unregistered", getSystemContext().getServiceReferences(Runnable.class, FILTER).isEmpty());
        try {
            context.getBundle();
            Assert.fail("IllegalStateException expected");
        } catch (IllegalStateException ex) {
            // expected
        }
    }

    private Hashtable<String, Object> getProperties() {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("test", ModuleBundleContextTestCase.class.getSimpleName());
        return props;
    }

    static class TestService implements Runnable {
        public void run() {
        }
    }
}
