package org.jboss.test.osgi.framework.bundle;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.bundle.FindHook;

/**
 * Test that a bundle {@link FindHook} hides bundles from other bundles, but not from the system bundle.
 *
 * @since 11-Oct-2026
 */
public class BundleFindHookTestCase extends OSGiFrameworkTest {

    @Test
    public void testFindHook() throws Exception {
        final Bundle hidden = installBundle(getBundleArchive("findhook-hidden"));
        Bundle client = installBundle(getBundleArchive("findhook-viewer"));
        client.start();

        final AtomicInteger hookCalls = new AtomicInteger();
        FindHook hook = new FindHook() {
            public void find(BundleContext context, Collection<Bundle> bundles) {
                hookCalls.incrementAndGet();
                bundles.remove(hidden);
            }
        };
        ServiceRegistration<FindHook> hookReg = getSystemContext().registerService(FindHook.class, hook, null);
        try {
            // The hook hides the bundle from the client
            BundleContext context = client.getBundleContext();
            assertNull(context.getBundle(hidden.getBundleId()));
            assertFalse(Arrays.asList(context.getBundles()).contains(hidden));
            try {
                context.installBundle(hidden.getLocation());
                fail("BundleException expected");
            } catch (BundleException ex) {
                assertEquals(BundleException.REJECTED_BY_HOOK, ex.getType());
            }

            // The hook is called for the system bundle context, but cannot hide the bundle
            int calls = hookCalls.get();
            BundleContext syscontext = getSystemContext();
            assertSame(hidden, syscontext.getBundle(hidden.getBundleId()));
            assertTrue(Arrays.asList(syscontext.getBundles()).contains(hidden));
            assertSame(hidden, syscontext.installBundle(hidden.getLocation()));
            assertEquals(calls + 3, hookCalls.get());
        } finally {
            hookReg.unregister();
            client.uninstall();
            hidden.uninstall();
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
