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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Permanent deadlock when two services are unregistered concurrently and a ServiceListener that handles the
 * UNREGISTERING event of one service gets or releases (ungetService) the other one: the framework delivers
 * UNREGISTERING while it holds the Java monitor of the service being unregistered, and getService/ungetService take
 * the Java monitor of the other service.
 *
 * <h3>Severity</h3>
 * critical. Two Java monitors, no timeout: both unregistering threads hang forever. The pattern is the normal
 * Declarative Services / ServiceTracker reaction: a component that is bound to S1 and S2 is deactivated when S1 goes
 * away and releases everything it is bound to (ungetService of S2) inside the UNREGISTERING callback; if the provider
 * of S2 is stopped at the same time (parallel undeploy on MSC threads in WildFly, two bundles stopped by different
 * threads, a refresh while another bundle stops) the other thread does the same in the opposite direction. Every later
 * operation that touches one of the two services (getService, ungetService, unregister, the owning bundles' stop,
 * framework shutdown) blocks forever as well.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceManagerImpl.unregisterService} and
 * {@code org.jboss.osgi.framework.internal.ServiceStateImpl} (addUsingBundle, removeUsingBundle,
 * hasPrototypeValues, getScopedValue: {@code synchronized (this)}).
 *
 * <h3>Found by</h3>
 * concurrency workstream: lock order graph recorded by a bytecode agent over the framework core + itest suites
 * ({@code agents/concurrency/lockrec}): callout {@code ServiceListener.serviceChanged} at
 * {@code FrameworkEventsImpl.fireServiceEvent} with {@code ServiceStateImpl} monitor held (591 times), and the edge
 * ServiceStateImpl monitor -&gt; ServiceStateImpl monitor of a different instance; made deterministic with a latch
 * inside the listener.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.3 Synchronization Pitfalls
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "Generally, a bundle that calls a
 * listener should not hold any Java monitors. This means that neither the Framework nor the originator of a
 * synchronous event should be in a monitor when a callback is initiated." ... "Time-outs can be used to break
 * deadlocks, but Java monitors do not have time-outs. Therefore, the code will hang forever until the system is
 * reset". ServiceRegistration.unregister javadoc (10.1.30.3): the UNREGISTERING event is delivered synchronously
 * "before the service has completed unregistering" and the steps then release the service for every using bundle;
 * no blocking or exception for a concurrent unregistration of another service is specified. BundleContext.ungetService
 * / getService (10.1.7.28, 10.1.7.13) declare no blocking.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:287}
 * ({@code unregisterService}: {@code synchronized (serviceState)} around everything, including
 * {@code frameworkEvents.fireServiceEvent(serviceOwner, ServiceEvent.UNREGISTERING, serviceState)} at line 309, which
 * calls {@code ServiceListener.serviceChanged} at {@code FrameworkEventsImpl.java:646}); the listener's
 * {@code ungetService} -&gt; {@code ServiceManagerImpl.ungetService:338} -&gt;
 * {@code ServiceStateImpl.hasPrototypeValues:334} / {@code removeUsingBundle:468} ({@code synchronized (this)} of
 * the other service; {@code getService} -&gt; {@code addUsingBundle:458} likewise). Pre-existing (WF41 has the same
 * {@code synchronized (serviceState)} in unregisterService).
 *
 * <h3>Root cause</h3>
 * Callout under a Java monitor: the service's monitor is held while foreign code (ServiceListener, EventHook,
 * EventListenerHook, ServiceFactory.ungetService) runs, and that code may take the monitor of another service whose
 * unregistering thread does the same in the opposite order. Lock pair: ServiceStateImpl(S1) monitor and
 * ServiceStateImpl(S2) monitor (two instances of the same lock class).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=UnregisteringListenerCrossDeadlockIssueTestCase
 * </pre>
 * The test registers S1 and S2 (system bundle), a consumer bundle gets both and adds a ServiceListener that, on
 * UNREGISTERING of one of them, waits until the other unregistering thread is in the listener as well (a
 * CountDownLatch of 2, at most 5 s) and then releases the other service (ungetService) - what a DS component bound to
 * both services does when it is deactivated. Two daemon threads unregister S1 and S2. The test takes about 10 s; it
 * prints an in-process thread dump of the deadlocked threads (ThreadMXBean) when the threads hang. The framework is left running (it cannot be stopped once
 * deadlocked).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: two concurrent ServiceRegistration.unregister() calls must both return, but after 10 s
 * unregister S1 alive=true, unregister S2 alive=true; JVM monitor deadlock:
 *   "concurrency01-unregister-S1" waits for org.jboss.osgi.framework.internal.ServiceStateImpl@... held by
 *   "concurrency01-unregister-S2" at ...ServiceStateImpl.hasPrototypeValues(ServiceStateImpl.java:334)
 *   "concurrency01-unregister-S2" waits for org.jboss.osgi.framework.internal.ServiceStateImpl@... held by
 *   "concurrency01-unregister-S1" at ...ServiceStateImpl.hasPrototypeValues(ServiceStateImpl.java:334)
 * jstack: Found one Java-level deadlock: ... ServiceManagerImpl.unregisterService(ServiceManagerImpl.java:309)
 *   - locked &lt;...&gt; (a org.jboss.osgi.framework.internal.ServiceStateImpl)
 * </pre>
 * (full jstack in run.log)
 *
 * <h3>Expected</h3>
 * Both unregister() calls return; each listener call releases the other service normally.
 *
 * <h3>Reference behaviour</h3>
 * Equinox (ServiceRegistrationImpl.unregister: marks the registration UNREGISTERING under the registration lock,
 * then publishes the event and releases the service uses after leaving it) and Felix (ServiceRegistry.unregisterService
 * fires the event without holding a registry or registration lock) do not hold a lock while UNREGISTERING is
 * delivered. Differential (same scenario as a plain launcher program, explore/concurrency/diff/Diff.java c01, JDK 25):
 * Equinox 3.21.0 and Felix 7.0.5 return from both unregister() calls; jbosgi hangs with the JVM monitor deadlock.
 *
 * <h3>Suggested fix</h3>
 * In ServiceManagerImpl.unregisterService only test-and-set the unregistering state (and remove the service from
 * serviceContainer) under the service state monitor, then deliver UNREGISTERING and release the using bundles
 * (ungetService / ServiceFactory.ungetService, prototype values) outside the monitor. The same change removes the
 * ServiceStateImpl -&gt; listener monitor edge of static/06. Verified with a trial patch (issues/concurrency/01-.../fix.diff:
 * the unregistering state is set under the monitor, event delivery and release happen after leaving it): this test and
 * concurrency/02 pass, all org.jboss.test.osgi.framework.service tests still pass.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * static/06 ListenerHookUnregisterDeadlockIssueTestCase (other lock pair: ServiceStateImpl monitor vs the service
 * listener monitor; same fix site); concurrency/02 BundleMonitorServiceFactoryDeadlockIssueTestCase.
 */
public class UnregisteringListenerCrossDeadlockIssueTestCase extends OSGiTest {

    private Framework framework;
    private boolean deadlocked;

    @After
    public void tearDown() throws Exception {
        // A deadlocked framework cannot be stopped (stopping the bundles needs the monitors); leave it to the JVM exit
        if (framework != null && !deadlocked) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testConcurrentUnregisterWithCrossUngetInListener() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency01-unregister-deadlock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext syscontext = framework.getBundleContext();

        // S1 and S2, provided by the system bundle (any provider bundle behaves the same)
        final ServiceRegistration<Runnable> reg1 = syscontext.registerService(Runnable.class, new Noop(), props("S1"));
        final ServiceRegistration<Runnable> reg2 = syscontext.registerService(Runnable.class, new Noop(), props("S2"));
        final ServiceReference<Runnable> ref1 = reg1.getReference();
        final ServiceReference<Runnable> ref2 = reg2.getReference();

        // A consumer bundle that uses both services (like a DS component with two references)
        Bundle consumer = syscontext.installBundle("concurrency01-consumer", manifestOnlyBundle("concurrency01.consumer"));
        consumer.start();
        final BundleContext context = consumer.getBundleContext();
        context.getService(ref1);
        context.getService(ref2);

        // On UNREGISTERING of one service, release the other one (component deactivation). The latch makes both
        // unregistering threads enter the listener before either releases the other service.
        final CountDownLatch bothInListener = new CountDownLatch(2);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        context.addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                if (event.getType() != ServiceEvent.UNREGISTERING)
                    return;
                ServiceReference<?> other = event.getServiceReference().equals(ref1) ? ref2 : ref1;
                bothInListener.countDown();
                try {
                    bothInListener.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                context.ungetService(other);
            }
        }, "(objectClass=" + Runnable.class.getName() + ")");

        Thread t1 = unregisterThread("concurrency01-unregister-S1", reg1, failure);
        Thread t2 = unregisterThread("concurrency01-unregister-S2", reg2, failure);
        t1.start();
        t2.start();
        t1.join(10000);
        t2.join(1000);

        if (t1.isAlive() || t2.isAlive()) {
            String deadlock = describeMonitorDeadlock();
            deadlocked = deadlock != null;
            System.out.println(jstack());
            fail("two concurrent ServiceRegistration.unregister() calls must both return, but after 10 s unregister S1 alive="
                    + t1.isAlive() + ", unregister S2 alive=" + t2.isAlive() + (deadlock != null ? "; JVM monitor deadlock:" + deadlock : ""));
        }
        if (failure.get() != null)
            throw new AssertionError("unregister failed", failure.get());
    }

    private static Thread unregisterThread(String name, final ServiceRegistration<?> reg, final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    reg.unregister();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Dictionary<String, Object> props(String name) {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("name", name);
        return props;
    }

    static InputStream manifestOnlyBundle(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attrs.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return new ByteArrayInputStream(baos.toByteArray());
    }

    /** The JVM monitor deadlock (ThreadMXBean), or null if there is none */
    static String describeMonitorDeadlock() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.findDeadlockedThreads();
        if (ids == null)
            return null;
        StringBuilder info = new StringBuilder();
        for (ThreadInfo ti : mx.getThreadInfo(ids, true, true)) {
            info.append("\n  \"").append(ti.getThreadName()).append("\" waits for ").append(ti.getLockName()).append(" held by \"")
                    .append(ti.getLockOwnerName()).append("\" at ").append(ti.getStackTrace()[0]);
        }
        return info.toString();
    }

    /** A thread dump of the deadlocked threads of this JVM, taken in-process (an external jstack can hang) */
    static String jstack() {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids = mx.findDeadlockedThreads();
            StringBuilder sb = new StringBuilder("---- in-process thread dump " + ProcessHandle.current().pid() + " ----\n");
            if (ids == null) {
                sb.append("no Java-level deadlock found\n");
                return sb.toString();
            }
            sb.append("Found one Java-level deadlock:\n");
            for (ThreadInfo ti : mx.getThreadInfo(ids, true, true)) {
                if (ti == null)
                    continue;
                sb.append('"').append(ti.getThreadName()).append("\" ").append(ti.getThreadState())
                        .append(" waiting to lock ").append(ti.getLockName())
                        .append(" held by \"").append(ti.getLockOwnerName()).append("\"\n");
                for (StackTraceElement ste : ti.getStackTrace())
                    sb.append("\tat ").append(ste).append('\n');
            }
            return sb.toString();
        } catch (Exception ex) {
            return "thread dump failed: " + ex;
        }
    }

    static class Noop implements Runnable {
        public void run() {
        }
    }
}
