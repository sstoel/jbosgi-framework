package org.jboss.test.osgi.framework.classloader;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.test.osgi.framework.classloader.support.a.A;
import org.jboss.test.osgi.framework.simple.bundleA.BeanA;
import org.jboss.test.osgi.framework.simple.bundleB.BeanB;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.hooks.weaving.WovenClassListener;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Test that a {@link WovenClassListener} is notified of the state changes of a {@link WovenClass}.
 *
 * @since 11-Oct-2026
 */
public class WovenClassListenerTestCase extends OSGiFrameworkTest {

    @Test
    public void testListenerStates() throws Exception {
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName())) {
                    assertEquals(WovenClass.TRANSFORMING, wovenClass.getState());
                    wovenClass.getDynamicImports().add("org.osgi.util.tracker");
                }
            }
        };
        TestListener listener = new TestListener();
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);
        Bundle bundle = installBundle(getBundleArchive("woven-states"));
        try {
            Class<?> clazz = bundle.loadClass(A.class.getName());
            assertEquals(Arrays.asList(WovenClass.TRANSFORMED, WovenClass.DEFINED), listener.states);
            assertEquals("No failures in the listener: " + listener.failures, 0, listener.failures.size());
            assertSame(clazz, listener.wovenClass.getDefinedClass());

            // The dynamic import is a requirement in the wiring of the bundle, but it is not declared by the bundle
            BundleRevision brev = bundle.adapt(BundleRevision.class);
            List<BundleRequirement> reqs = bundle.adapt(BundleWiring.class).getRequirements(PackageNamespace.PACKAGE_NAMESPACE);
            assertEquals(1, reqs.size());
            assertSame(brev, reqs.get(0).getRevision());
            assertSame(reqs.get(0), bundle.adapt(BundleWiring.class).getRequirements(PackageNamespace.PACKAGE_NAMESPACE).get(0));
            assertTrue(brev.getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE).isEmpty());
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
            hookReg.unregister();
        }
    }

    @Test
    public void testListenerStatesForSubclass() throws Exception {
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
            }
        };
        final List<String> events = new ArrayList<String>();
        WovenClassListener listener = new WovenClassListener() {
            public void modified(WovenClass wovenClass) {
                String className = wovenClass.getClassName();
                events.add(className.substring(className.lastIndexOf('.') + 1) + ":" + wovenClass.getState());
            }
        };
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);

        // The super class is loaded by the same class loader while the sub class is defined
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "woven-subclass");
        archive.addClasses(BeanA.class, BeanB.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        Bundle bundle = installBundle(archive);
        try {
            assertNotNull(bundle.loadClass(BeanA.class.getName()));
            assertEquals(Arrays.asList("BeanA:" + WovenClass.TRANSFORMED, "BeanB:" + WovenClass.TRANSFORMED, "BeanB:" + WovenClass.DEFINED, "BeanA:" + WovenClass.DEFINED), events);
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
            hookReg.unregister();
        }
    }

    @Test
    public void testListenerNotCalledWithoutHooks() throws Exception {
        TestListener listener = new TestListener();
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);
        Bundle bundle = installBundle(getBundleArchive("woven-nohooks"));
        try {
            assertNotNull(bundle.loadClass(A.class.getName()));
            assertTrue("Listener not called: " + listener.states, listener.states.isEmpty());
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
        }
    }

    @Test
    public void testListenerCalledWhenHookFails() throws Exception {
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName()))
                    throw new IllegalStateException("weaving failure");
            }
        };
        TestListener listener = new TestListener();
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);
        Bundle bundle = installBundle(getBundleArchive("woven-hookfails"));
        try {
            try {
                bundle.loadClass(A.class.getName());
                fail("Class load expected to fail");
            } catch (ClassFormatError err) {
                // expected
            }
            assertEquals(Arrays.asList(WovenClass.TRANSFORMING_FAILED), listener.states);
            assertEquals("No failures in the listener: " + listener.failures, 0, listener.failures.size());
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
            hookReg.unregister();
        }
    }

    @Test
    public void testListenerExceptionDoesNotFailClassLoad() throws Exception {
        WeavingHook hook = new WeavingHook() {
            public void weave(WovenClass wovenClass) {
            }
        };
        final List<Integer> states = new ArrayList<Integer>();
        WovenClassListener listener = new WovenClassListener() {
            public void modified(WovenClass wovenClass) {
                if (A.class.getName().equals(wovenClass.getClassName())) {
                    states.add(wovenClass.getState());
                    throw new IllegalStateException("listener failure");
                }
            }
        };
        ServiceRegistration<WeavingHook> hookReg = getSystemContext().registerService(WeavingHook.class, hook, null);
        ServiceRegistration<WovenClassListener> listenerReg = getSystemContext().registerService(WovenClassListener.class, listener, null);
        Bundle bundle = installBundle(getBundleArchive("woven-listenerfails"));
        try {
            assertNotNull(bundle.loadClass(A.class.getName()));
            assertEquals(Arrays.asList(WovenClass.TRANSFORMED, WovenClass.DEFINED), states);
        } finally {
            bundle.uninstall();
            listenerReg.unregister();
            hookReg.unregister();
        }
    }

    private JavaArchive getBundleArchive(String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.addClasses(A.class);
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

    /**
     * Records the states and verifies the woven class in each of them.
     * Failures are collected because the framework does not propagate listener exceptions.
     */
    static class TestListener implements WovenClassListener {

        final List<Integer> states = new ArrayList<Integer>();
        final List<Throwable> failures = new ArrayList<Throwable>();
        WovenClass wovenClass;

        public void modified(WovenClass wovenClass) {
            if (!A.class.getName().equals(wovenClass.getClassName()))
                return;

            this.wovenClass = wovenClass;
            states.add(wovenClass.getState());
            try {
                // The class bytes and the dynamic imports can no longer be changed
                try {
                    wovenClass.setBytes(new byte[0]);
                    fail("IllegalStateException expected");
                } catch (IllegalStateException ex) {
                    // expected
                }
                try {
                    wovenClass.getDynamicImports().add("org.osgi.service.url");
                    fail("UnsupportedOperationException expected");
                } catch (UnsupportedOperationException ex) {
                    // expected
                }

                boolean wiringUpdated = !wovenClass.getBundleWiring().getRequirements(PackageNamespace.PACKAGE_NAMESPACE).isEmpty();
                switch (wovenClass.getState()) {
                    case WovenClass.TRANSFORMED:
                        assertFalse(wovenClass.isWeavingComplete());
                        assertNull(wovenClass.getDefinedClass());
                        assertFalse("Dynamic imports not yet in the wiring", wiringUpdated);
                        break;
                    case WovenClass.DEFINED:
                        assertTrue(wovenClass.isWeavingComplete());
                        assertNotNull(wovenClass.getDefinedClass());
                        assertTrue("Dynamic imports in the wiring", wiringUpdated);
                        break;
                    case WovenClass.TRANSFORMING_FAILED:
                        assertTrue(wovenClass.isWeavingComplete());
                        assertNull(wovenClass.getDefinedClass());
                        break;
                    default:
                        fail("Unexpected state: " + wovenClass.getState());
                }
            } catch (Throwable th) {
                failures.add(th);
            }
        }
    }
}
