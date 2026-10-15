package org.jboss.test.osgi.framework.issues;
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

import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.FindHook;

/**
 * BundleContext.getServiceReferences throws IllegalStateException ("Service unregistered") when one of the
 * matching services is unregistered between the registry lookup and the creation of the result array.
 *
 * <h3>Severity</h3>
 * major. getServiceReferences may only throw IllegalStateException when the BundleContext is no longer valid. Any
 * caller that looks up services while another thread unregisters one (ServiceTracker.open, whiteboard
 * implementations, Declarative Services) can fail with an unexpected runtime exception. The stress test shows it
 * within a few seconds with two threads.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.AbstractBundleContext#getServiceReferences
 * (also the getRegisteredServices/getServicesInUse loops in AbstractBundleState and ServiceManagerImpl#processFindHooks
 * use the same throwing call)
 *
 * <h3>Found by</h3>
 * services workstream, concurrency stress test (register/unregister against lookups), made deterministic with a
 * FindHook that unregisters a candidate; Equinox / Felix differential of the FindHook scenario.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, BundleContext.getServiceReferences(String, String)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.BundleContext.getServiceReferences-String-String-):
 * "@throws InvalidSyntaxException If the specified filter contains an invalid filter expression that cannot be
 * parsed. @throws IllegalStateException If this BundleContext is no longer valid."
 * ServiceReference objects stay usable after unregistration (ServiceReference.getProperty: "This method must
 * continue to return property values after the service has been unregistered."; BundleContext.getService: "This
 * method will always return null when the service associated with the specified reference has been unregistered.").
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleContext.java:432, :447, :462
 * ({@code result.add(serviceState.getReference())}) with
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:365-367
 * ({@code getReference()} calls {@code assertNotUnregistered()}). Same pattern at AbstractBundleState.java:322, :340
 * and ServiceManagerImpl.java:375. Original code, already on WF41.
 *
 * <h3>Root cause</h3>
 * The registry returns a snapshot of ServiceState objects (filtered for {@code isUnregistered()} at lookup time).
 * Converting them to ServiceReference objects uses {@code ServiceState.getReference()}, which is the
 * ServiceRegistration.getReference contract and throws IllegalStateException for an unregistered service. A service
 * unregistered after the lookup (by another thread, or by a FindHook) makes the whole call fail.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=GetServiceReferencesConcurrentUnregisterIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testServiceUnregisteredDuringLookup: getServiceReferences must not throw while the BundleContext is valid, but got
 *   java.lang.IllegalStateException: JBOSGI011224: Service unregistered: ServiceState{...}
 *     at ...ServiceStateImpl.assertNotUnregistered(ServiceStateImpl.java:651)
 *     at ...ServiceStateImpl.getReference(ServiceStateImpl.java:366)
 *     at ...AbstractBundleContext.getServiceReferences(AbstractBundleContext.java:432)
 * testConcurrentUnregister: (same exception from the lookup thread)
 * </pre>
 *
 * <h3>Expected</h3>
 * No exception; the result contains the references that matched at lookup time (a reference to a service that has
 * been unregistered in the meantime is harmless: getService returns null for it) or omits them.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5 with the same FindHook: getServiceReferences returns normally (1 reference).
 *
 * <h3>Suggested fix</h3>
 * Use an accessor that returns the cached ServiceReference without the registration check (e.g. a package private
 * {@code getReferenceInternal()} returning the {@code reference} field) everywhere the framework converts registry
 * snapshots to references; keep the check only in ServiceRegistration.getReference.
 *
 * <h3>Regression vs WF41</h3>
 * no (original code).
 */
public class GetServiceReferencesConcurrentUnregisterIssueTestCase extends OSGiFrameworkTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    public void testServiceUnregisteredDuringLookup() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("lookup-unregister"));
        BundleContext syscontext = getSystemContext();
        final AtomicReference<ServiceRegistration<Runnable>> victim = new AtomicReference<ServiceRegistration<Runnable>>();
        // Simulates a service that another thread unregisters right after the registry lookup
        ServiceRegistration<FindHook> hookReg = syscontext.registerService(FindHook.class, new FindHook() {
            @Override
            public void find(BundleContext context, String name, String filter, boolean allServices, Collection<ServiceReference<?>> references) {
                ServiceRegistration<Runnable> sreg = victim.getAndSet(null);
                if (sreg != null)
                    sreg.unregister();
            }
        }, null);
        try {
            bundle.start();
            BundleContext context = bundle.getBundleContext();

            victim.set(syscontext.registerService(Runnable.class, new TestService(), null));
            try {
                context.getServiceReferences(Runnable.class.getName(), null);
            } catch (IllegalStateException ex) {
                fail("getServiceReferences(String,String) must not throw while the BundleContext is valid, but got " + ex);
            }

            victim.set(syscontext.registerService(Runnable.class, new TestService(), null));
            try {
                context.getServiceReferences(Runnable.class, null);
            } catch (IllegalStateException ex) {
                fail("getServiceReferences(Class,String) must not throw while the BundleContext is valid, but got " + ex);
            }
        } finally {
            hookReg.unregister();
            bundle.uninstall();
        }
    }

    @Test
    public void testConcurrentUnregister() throws Exception {
        final BundleContext context = getSystemContext();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicReference<Throwable> registrarFailure = new AtomicReference<Throwable>();
        Thread registrar = new Thread("registrar") {
            @Override
            public void run() {
                try {
                    while (done.get() == false) {
                        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), null);
                        sreg.unregister();
                    }
                } catch (Throwable th) {
                    registrarFailure.set(th);
                }
            }
        };
        registrar.start();
        try {
            long end = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < end) {
                try {
                    context.getServiceReferences(Runnable.class.getName(), null);
                } catch (IllegalStateException ex) {
                    fail("getServiceReferences must not throw while the BundleContext is valid, but got " + ex);
                }
            }
        } finally {
            done.set(true);
            registrar.join(10000);
        }
        if (registrarFailure.get() != null)
            throw new AssertionError("The registrar thread failed: " + registrarFailure.get());
    }

    private JavaArchive getBundleArchive(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
