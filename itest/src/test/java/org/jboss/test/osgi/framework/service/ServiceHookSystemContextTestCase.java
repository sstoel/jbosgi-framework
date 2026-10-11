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
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.EventListenerHook;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;

/**
 * Test that service hooks cannot hide service events from listeners of the system bundle.
 *
 * @since 11-Oct-2026
 */
public class ServiceHookSystemContextTestCase extends OSGiFrameworkTest {

    private static final String FILTER = "(test=" + ServiceHookSystemContextTestCase.class.getSimpleName() + ")";

    @Test
    public void testEventListenerHook() throws Exception {
        BundleContext syscontext = getSystemContext();
        Bundle bundle = installBundle(getBundleArchive("eventhook-listener"));
        bundle.start();

        final List<Integer> systemEvents = new ArrayList<Integer>();
        ServiceListener systemListener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                systemEvents.add(event.getType());
            }
        };
        final List<Integer> bundleEvents = new ArrayList<Integer>();
        ServiceListener bundleListener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                bundleEvents.add(event.getType());
            }
        };
        syscontext.addServiceListener(systemListener, FILTER);
        bundle.getBundleContext().addServiceListener(bundleListener, FILTER);

        final boolean[] hookCalled = new boolean[1];
        EventListenerHook hook = new EventListenerHook() {
            public void event(ServiceEvent event, Map<BundleContext, Collection<ListenerInfo>> listeners) {
                hookCalled[0] = true;
                listeners.clear();
            }
        };

        java.util.Hashtable<String, Object> props = new java.util.Hashtable<String, Object>();
        props.put("test", ServiceHookSystemContextTestCase.class.getSimpleName());
        Runnable service = new Runnable() {
            public void run() {
            }
        };

        // Without the hook both listeners get the event
        ServiceRegistration<Runnable> sreg = syscontext.registerService(Runnable.class, service, props);
        sreg.unregister();
        assertEquals(2, systemEvents.size());
        assertEquals(2, bundleEvents.size());

        ServiceRegistration<EventListenerHook> hookReg = syscontext.registerService(EventListenerHook.class, hook, null);
        try {
            sreg = syscontext.registerService(Runnable.class, service, props);
            sreg.unregister();
            assertTrue("Hook called", hookCalled[0]);
            assertEquals("System listener cannot be hidden", 4, systemEvents.size());
            assertEquals("Bundle listener is hidden", 2, bundleEvents.size());
        } finally {
            hookReg.unregister();
            syscontext.removeServiceListener(systemListener);
            bundle.uninstall();
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
}
