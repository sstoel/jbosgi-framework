package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Deadlock (permanent, no timeout) between {@code ServiceRegistration.unregister()} of a ListenerHook and a concurrent
 * {@code BundleContext.addServiceListener/removeServiceListener}: the unregistering thread holds the hook's service
 * state monitor and waits for the service listener monitor to deliver UNREGISTERING; the listener thread holds the
 * service listener monitor and waits for the hook's service state monitor in {@code getService} while it collects
 * the ListenerHooks.
 *
 * <h3>Severity</h3>
 * critical. Two Java monitors, no timeout: both threads hang forever, and with them every later service
 * registration, unregistration, addServiceListener/removeServiceListener and ServiceTracker open/close of the
 * framework (they all need the service listener monitor), i.e. bundle start/stop and in WildFly every OSGi
 * deployment operation. Trigger: a bundle that provides a ListenerHook (Remote Service Admin topology manager,
 * region/isolation hooks, on-demand providers) is stopped or unregisters its hook while any other thread adds or
 * removes a service listener (ServiceTracker.open/close, DS, Blueprint) - the hook registration is in the list the
 * listener thread is processing. A plain stress run (hook register/unregister loop against an add/remove-listener
 * loop, no orchestration) also ended in this deadlock.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl} (add/removeServiceListener,
 * getServiceListenerHooks) and {@code org.jboss.osgi.framework.internal.ServiceManagerImpl.unregisterService}.
 *
 * <h3>Found by</h3>
 * static workstream: SpotBugs 4.9.8 JLM_JSR166_UTILCONCURRENT_MONITORENTER (synchronization on the ConcurrentHashMap
 * listener maps of FrameworkEventsImpl) and IS2_INCONSISTENT_SYNC triage; a stress test written to confirm an
 * unsynchronized iteration of the same map deadlocked instead (jstack: "Found one Java-level deadlock"); this test
 * makes the interleaving deterministic.
 *
 * <h3>Spec reference</h3>
 * {@code ServiceRegistration.unregister()} and {@code BundleContext.addServiceListener()} (osgi.core 8.0.0) have no
 * blocking semantics; both must return. OSGi Core R8 4.7.3 Synchronization Pitfalls
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "Generally, a bundle that calls a
 * listener should not hold any Java monitors. This means that neither the Framework nor the originator of a
 * synchronous event should be in a monitor when a callback is initiated." ... "Time-outs can be used to break
 * deadlocks, but Java monitors do not have time-outs. Therefore, the code will hang forever until the system is
 * reset ... This type of deadlock is prevented by not calling the Framework (or other code that might cause
 * callbacks) in a synchronized block."; R8 55.7.5 Multi Threading: "It is fully allowed to call the framework from
 * any of the hook methods." - the framework itself calls ServiceFactory.getService and ListenerHook.added/removed
 * from inside a synchronized block.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:286} ({@code unregisterService}:
 * {@code synchronized (serviceState)} around the whole unregistration, including
 * {@code frameworkEvents.fireServiceEvent(serviceOwner, ServiceEvent.UNREGISTERING, serviceState)} at line 309, which
 * takes {@code synchronized (serviceListeners)} at FrameworkEventsImpl.java:574);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:211-241}
 * ({@code addServiceListener} holds {@code synchronized (serviceListeners)} while it calls
 * {@code getServiceListenerHooks()}, lines 312-331, which call {@code syscontext.getService(sref)} for every hook;
 * {@code ServiceManagerImpl.getService} -> {@code ServiceStateImpl.addUsingBundle} takes
 * {@code synchronized (this)} at ServiceStateImpl.java:458); the same in {@code removeServiceListener} (line 272) and
 * {@code removeServiceListeners}. Pre-existing: identical on WF41.
 *
 * <h3>Root cause</h3>
 * Lock order inversion: unregister takes service state -> service listeners, add/removeServiceListener takes
 * service listeners -> service state (and also calls the hooks' added/removed methods, i.e. foreign code, with the
 * listener monitor held).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ListenerHookUnregisterDeadlockIssueTestCase
 * </pre>
 * The test makes the interleaving deterministic with two ListenerHooks registered as ServiceFactory: the getService
 * callback of the hook that the listener thread looks up first runs inside addServiceListener with the listener
 * monitor held; it starts the unregistration of the other hook in another thread, waits until that thread is
 * BLOCKED, and returns; the listener thread then calls getService for the other hook. Both threads are daemon threads with their own
 * framework instance, which is left running (it cannot be stopped once deadlocked).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: unregister() of a ListenerHook and a concurrent addServiceListener() must both return,
 * but after 20 s addServiceListener alive=true, unregister alive=true, JVM monitor deadlock:
 *   "static06-unregister-hook" waits for java.util.concurrent.ConcurrentHashMap@5f117b3d held by
 *   "static06-add-listener" at org.jboss.osgi.framework.internal.FrameworkEventsImpl.fireServiceEvent(FrameworkEventsImpl.java:574)
 *   "static06-add-listener" waits for org.jboss.osgi.framework.internal.ServiceStateImpl@760245e1 held by
 *   "static06-unregister-hook" at org.jboss.osgi.framework.internal.ServiceStateImpl.addUsingBundle(ServiceStateImpl.java:458)
 * </pre>
 * The same deadlock occurred without any test orchestration (plain ListenerHooks registered and unregistered in a
 * loop while another thread added/removed a service listener) when that stress test was repeated; jstack "Found one Java-level
 * deadlock" with main in ServiceManagerImpl.unregisterService:309 -&gt; FrameworkEventsImpl.fireServiceEvent:574 and
 * the listener thread in FrameworkEventsImpl.removeServiceListener:272 -&gt; getServiceListenerHooks:330 -&gt;
 * ServiceStateImpl.addUsingBundle:458.
 * <pre>
 * </pre>
 *
 * <h3>Expected</h3>
 * Both calls return: addServiceListener completes (the second hook is either still called or skipped because it is
 * being unregistered) and unregister() completes.
 *
 * <h3>Reference behaviour</h3>
 * Equinox and Felix do not hold their service registry/listener locks while calling getService for hooks or while
 * delivering service events (Equinox: ServiceRegistry.notifyListenerHooks / publishServiceEvent run unlocked).
 * Not run as a differential: the deterministic interleaving depends on jbosgi's call structure.
 *
 * <h3>Suggested fix</h3>
 * In FrameworkEventsImpl collect the ListenerHook services (getServiceReferences + getService) and build the
 * ListenerInfo collections before entering {@code synchronized (serviceListeners)}, and call
 * {@code added/removed} after leaving it; in ServiceManagerImpl.unregisterService only mark the state unregistered
 * under the service state monitor and deliver UNREGISTERING outside it.
 *
 * <h3>Regression vs WF41</h3>
 * no (same locking on WF41).
 */
public class ListenerHookUnregisterDeadlockIssueTestCase extends OSGiTest {

    private Framework framework;
    private boolean deadlocked;

    @After
    public void tearDown() throws Exception {
        // A deadlocked framework cannot be stopped (stop needs the listener monitor); leave it to the JVM exit
        if (framework != null && !deadlocked) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUnregisterListenerHookWhileAddingServiceListener() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/listener-hook-deadlock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext context = framework.getBundleContext();

        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicReference<ServiceRegistration<?>> victim = new AtomicReference<ServiceRegistration<?>>();
        final CountDownLatch unregisterNow = new CountDownLatch(1);
        final AtomicReference<Thread> unregisterThread = new AtomicReference<Thread>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final ServiceRegistration<?>[] hooks = new ServiceRegistration<?>[2];

        // Two ListenerHooks registered as ServiceFactory. The first one whose getService is called by the listener
        // thread (which then holds the service listener monitor) starts the unregistration of the other one and
        // returns once that unregistration is blocked; the listener thread then calls getService for the other one.
        for (int i = 0; i < 2; i++) {
            final int other = 1 - i;
            hooks[i] = context.registerService(ListenerHook.class.getName(), new ServiceFactory<ListenerHook>() {
                public ListenerHook getService(Bundle bundle, ServiceRegistration<ListenerHook> registration) {
                    if (armed.getAndSet(false)) {
                        victim.set(hooks[other]);
                        unregisterNow.countDown();
                        // wait until the unregistering thread is blocked on the service listener monitor
                        long deadline = System.currentTimeMillis() + 5000;
                        while (System.currentTimeMillis() < deadline) {
                            Thread thread = unregisterThread.get();
                            if (thread != null && thread.getState() == Thread.State.BLOCKED)
                                break;
                            Thread.yield();
                        }
                    }
                    return new NoopListenerHook();
                }

                public void ungetService(Bundle bundle, ServiceRegistration<ListenerHook> registration, ListenerHook service) {
                }
            }, null);
        }

        // drop the system bundle's use of the hooks (if any) so that their factories are called again
        for (ServiceRegistration<?> hook : hooks) {
            while (context.ungetService(hook.getReference())) {
            }
        }

        Thread unregistering = new Thread(new Runnable() {
            public void run() {
                try {
                    if (unregisterNow.await(30, TimeUnit.SECONDS))
                        victim.get().unregister();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "static06-unregister-hook");
        unregistering.setDaemon(true);
        unregisterThread.set(unregistering);

        Thread adding = new Thread(new Runnable() {
            public void run() {
                try {
                    context.addServiceListener(new ServiceListener() {
                        public void serviceChanged(ServiceEvent event) {
                        }
                    }, "(objectClass=com.acme.static06.Foo)");
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "static06-add-listener");
        adding.setDaemon(true);

        unregistering.start();
        armed.set(true);
        adding.start();

        adding.join(15000);
        unregistering.join(5000);
        if (adding.isAlive() || unregistering.isAlive()) {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids = mx.findMonitorDeadlockedThreads();
            StringBuilder info = new StringBuilder();
            if (ids != null) {
                deadlocked = true;
                for (ThreadInfo ti : mx.getThreadInfo(ids, true, true)) {
                    info.append("\n  \"").append(ti.getThreadName()).append("\" waits for ").append(ti.getLockName())
                            .append(" held by \"").append(ti.getLockOwnerName()).append("\" at ").append(ti.getStackTrace()[0]);
                }
            }
            fail("unregister() of a ListenerHook and a concurrent addServiceListener() must both return, but after 20 s "
                    + "addServiceListener alive=" + adding.isAlive() + ", unregister alive=" + unregistering.isAlive()
                    + (ids != null ? ", JVM monitor deadlock:" + info : ""));
        }
        for (ServiceRegistration<?> hook : hooks) {
            if (hook != victim.get())
                hook.unregister();
        }
    }

    static class NoopListenerHook implements ListenerHook {
        public void added(Collection<ListenerHook.ListenerInfo> listeners) {
        }

        public void removed(Collection<ListenerHook.ListenerInfo> listeners) {
        }
    }
}
