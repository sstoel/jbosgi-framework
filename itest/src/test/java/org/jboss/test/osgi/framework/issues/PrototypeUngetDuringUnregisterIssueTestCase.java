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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceRegistration;

/**
 * ServiceObjects.ungetService throws IllegalArgumentException for a prototype service object that it provided
 * when the release races with the unregistration of the service.
 *
 * <h3>Severity</h3>
 * minor. A bundle that releases its prototype service objects from another thread while the providing bundle
 * stops (unregisters the service) gets an IllegalArgumentException ("Service object was not provided for this
 * service") for an object that was provided by this ServiceObjects object. The specification says the call returns
 * without doing anything once the service is unregistered. Found 11 times in 300 rounds of a 4-thread stress test.
 * (The objects themselves are released exactly once: the fix of commit d7b20d08 works.)
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.AbstractBundleContext.ServiceObjectsImpl#ungetService
 * and ServiceStateImpl#ungetPrototypeValue / ServiceManagerImpl#unregisterService
 *
 * <h3>Found by</h3>
 * services workstream, stress test of ServiceObjects.getService/ungetService against unregister (checking the claim
 * of commit d7b20d08 "release prototype service objects on unregister"), made deterministic for this test.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, ServiceObjects.ungetService(S)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.ServiceObjects.ungetService-S-):
 * "For a prototype scope service, the following steps are required to release a service object: 1. If the
 * associated service has been unregistered, this method returns without doing anything. ..."
 * "@throws IllegalArgumentException If the specified service object is null or was not provided by a
 * ServiceObjects object for the associated service."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/AbstractBundleContext.java, ServiceObjectsImpl.ungetService:
 * {@code if (serviceState.isUnregistered()) return; ... if (serviceState.ungetPrototypeValue(bundleState, service) == false) throw MESSAGES.illegalArgumentInvalidServiceObject(service);}
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:286-323 (unregisterService releases
 * the prototype objects while holding the ServiceState monitor; {@code registration} is only set to null afterwards,
 * ServiceStateImpl.java:377-381). The release on unregister comes from the R6 work and commit d7b20d08.
 *
 * <h3>Root cause</h3>
 * {@code isUnregistered()} is checked once, outside the ServiceState monitor. During unregistration
 * {@code releasePrototypeValues} removes the bundle's objects from the bookkeeping before
 * {@code isUnregistered()} becomes true. A release that passed the check and then finds its object already removed
 * is reported as "not provided by this ServiceObjects" instead of being ignored.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=PrototypeUngetDuringUnregisterIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testReleaseRacingWithUnregister: Releasing a provided object while the service is being unregistered must not
 *   throw, but got java.lang.IllegalArgumentException: JBOSGI011288: Service object was not provided for this service: ...
 * </pre>
 *
 * <h3>Expected</h3>
 * ungetService returns without doing anything (the framework releases the object as part of the unregistration).
 *
 * <h3>Reference behaviour</h3>
 * Not checked against Equinox/Felix for this exact interleaving (it needs a hook into the unregistration); the
 * specification text is explicit.
 *
 * <h3>Suggested fix</h3>
 * In ungetPrototypeValue, decide under the ServiceState monitor: if the service is unregistered or the prototype
 * objects were already released ({@code prototypeValuesReleased}), return "handled" instead of false, so that
 * ServiceObjectsImpl only throws for objects it really never provided.
 *
 * <h3>Regression vs WF41</h3>
 * no (the prototype release on unregister is part of the R6 code that WF41 already had; d7b20d08 kept the race).
 */
public class PrototypeUngetDuringUnregisterIssueTestCase extends OSGiFrameworkTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    public void testReleaseRacingWithUnregister() throws Exception {
        BundleContext context = getSystemContext();
        final AtomicReference<Thread> releaser = new AtomicReference<Thread>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicReference<ServiceObjects<Runnable>> objectsRef = new AtomicReference<ServiceObjects<Runnable>>();
        final AtomicReference<Runnable> second = new AtomicReference<Runnable>();

        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new PrototypeServiceFactory<Runnable>() {
            @Override
            public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
                return new TestService();
            }

            @Override
            public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
                // The framework releases the first object as part of the unregistration. At this moment another
                // thread of the consumer releases the second object it got from the same ServiceObjects.
                if (releaser.get() == null && service != second.get()) {
                    Thread thread = new Thread("releaser") {
                        @Override
                        public void run() {
                            try {
                                objectsRef.get().ungetService(second.get());
                            } catch (Throwable th) {
                                failure.set(th);
                            }
                        }
                    };
                    releaser.set(thread);
                    thread.start();
                    // Let the releaser get into ungetService before the unregistration completes
                    long end = System.currentTimeMillis() + 5000;
                    while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.currentTimeMillis() < end)
                        Thread.yield();
                }
            }
        }, null);

        ServiceObjects<Runnable> objects = context.getServiceObjects(sreg.getReference());
        objectsRef.set(objects);
        assertNotNull("First prototype object", objects.getService());
        second.set(objects.getService());
        assertNotNull("Second prototype object", second.get());

        sreg.unregister();
        Thread thread = releaser.get();
        assertNotNull("The factory was asked to release the first object", thread);
        thread.join(10000);
        if (failure.get() != null)
            fail("Releasing a provided object while the service is being unregistered must not throw, but got " + failure.get());
        assertNull("After unregistration no prototype object is provided", objects.getService());
    }
}
