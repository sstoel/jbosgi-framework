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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;

/**
 * A second ServiceRegistration.unregister() call made while the first one is still in progress does not throw
 * IllegalStateException: it runs the whole unregistration again and fires a second UNREGISTERING event.
 *
 * <h3>Severity</h3>
 * minor. Two parties that both unregister a registration (for example a component being deactivated while its
 * bundle stops) are not told that the service was already unregistered, and every ServiceListener gets the
 * UNREGISTERING event twice. With two threads started together, both calls return normally in most rounds
 * (189 of 200 in the run below).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.ServiceStateImpl#unregister / #unregisterInternal and
 * ServiceManagerImpl#unregisterService
 *
 * <h3>Found by</h3>
 * R8 verification, delivered test; committed for the R8 release check.
 * services workstream round 2 (concurrency of unregister), Equinox / Felix differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, ServiceRegistration.unregister()
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.ServiceRegistration.unregister--):
 * "@throws IllegalStateException If this ServiceRegistration object has already been unregistered." and step 2:
 * "A service event of type ServiceEvent.UNREGISTERING is fired" (once per unregistration).
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:371-381
 * ({@code unregister()} checks {@code assertNotUnregistered()} without a lock, {@code registration = null} is set only
 * after ServiceManagerImpl.unregisterService returned) and ServiceManagerImpl.java:286-290
 * ({@code synchronized (serviceState) { if (serviceState.isUnregistered()) return; ...}}, re-entrant for the same
 * thread, and silent instead of throwing). Original code, already on WF41.
 *
 * <h3>Root cause</h3>
 * The "unregistered" state becomes visible only at the very end; a second call during the unregistration passes
 * the check and either repeats the unregistration (same thread, the monitor is re-entrant) or waits for the monitor
 * and then returns silently or repeats it (another thread).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=DoubleUnregisterIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testUnregisterFromUnregisteringListener: The second unregister() must throw IllegalStateException, but it returned normally
 * testConcurrentUnregister: Exactly one of two concurrent unregister() calls must throw IllegalStateException;
 *   both returned normally in N of 200 rounds
 * </pre>
 *
 * <h3>Expected</h3>
 * The first call marks the registration as unregistering atomically; any further call throws
 * IllegalStateException; listeners get exactly one UNREGISTERING event.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5: the nested call throws IllegalStateException and one UNREGISTERING event is fired;
 * with two threads exactly one call throws in 200 of 200 rounds. JBoss OSGi: two UNREGISTERING events, no
 * exception; with two threads 40 of 200 rounds (no barrier, differential harness) to 189 of 200 rounds (with a
 * barrier, this test) without exception.
 *
 * <h3>Suggested fix</h3>
 * In {@code ServiceStateImpl.unregister()} test and set an "unregistering" flag under the ServiceState lock (throw
 * IllegalStateException if it was already set) before calling the service manager.
 *
 * <h3>Regression vs WF41</h3>
 * no (original code).
 */
public class DoubleUnregisterIssueTestCase extends OSGiFrameworkTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    public void testUnregisterFromUnregisteringListener() throws Exception {
        BundleContext context = getSystemContext();
        final List<Integer> events = new ArrayList<Integer>();
        final List<Throwable> nested = new ArrayList<Throwable>();
        final ServiceRegistration<?>[] sreg = new ServiceRegistration<?>[1];
        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
                events.add(event.getType());
                if (event.getType() == ServiceEvent.UNREGISTERING && events.size() == 1) {
                    try {
                        sreg[0].unregister();
                        nested.add(null);
                    } catch (IllegalStateException ex) {
                        nested.add(ex);
                    }
                }
            }
        };
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("double.unregister", "true");
        sreg[0] = context.registerService(Runnable.class, new TestService(), props);
        context.addServiceListener(listener, "(double.unregister=true)");
        try {
            sreg[0].unregister();
        } finally {
            context.removeServiceListener(listener);
        }
        assertEquals("The second unregister() was called once", 1, nested.size());
        if (nested.get(0) == null)
            fail("The second unregister() must throw IllegalStateException, but it returned normally (events: " + events + ")");
        assertEquals("Exactly one UNREGISTERING event", 1, events.size());
    }

    @Test
    public void testConcurrentUnregister() throws Exception {
        BundleContext context = getSystemContext();
        int bothReturned = 0;
        for (int round = 0; round < 200; round++) {
            final ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), null);
            final CyclicBarrier barrier = new CyclicBarrier(2);
            final AtomicInteger returned = new AtomicInteger();
            Thread[] threads = new Thread[2];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread() {
                    @Override
                    public void run() {
                        try {
                            barrier.await();
                            sreg.unregister();
                            returned.incrementAndGet();
                        } catch (IllegalStateException ex) {
                            // expected for one of the two calls
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    }
                };
                threads[i].start();
            }
            for (Thread thread : threads)
                thread.join(10000);
            if (returned.get() != 1)
                bothReturned++;
        }
        assertEquals("Exactly one of two concurrent unregister() calls must throw IllegalStateException; both returned normally in "
                + bothReturned + " of 200 rounds", 0, bothReturned);
    }
}
