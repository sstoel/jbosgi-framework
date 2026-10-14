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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Permanent deadlock between the unregistration of a bundle-scoped service (ServiceFactory) and a ServiceFactory
 * getService call for the same consumer bundle: the framework calls {@code ServiceFactory.getService/ungetService}
 * while it holds the Java monitor of the consumer's {@code Bundle} object, and {@code unregister()} takes the
 * service's monitor and then the consumer's Bundle monitor (to release the consumer's factory object), while the
 * factory code of another service, running under the consumer's Bundle monitor, gets the service being unregistered.
 *
 * <h3>Severity</h3>
 * critical. Two Java monitors, no timeout: the unregistering thread (typically a bundle stop or undeploy) and the
 * thread that asked for the other service hang forever, and with them every later getService/ungetService of any
 * ServiceFactory for that consumer bundle (all of them synchronize on the consumer's Bundle object), the consumer's
 * stop and framework shutdown. Trigger: a consumer bundle that uses a bundle-scoped service S1 (any ServiceFactory,
 * e.g. a DS component with servicefactory=true/scope=bundle, LogService, ConfigurationAdmin) gets another
 * bundle-scoped service S2 whose factory itself looks up S1 on first use (a delegating/proxying factory), while the
 * provider of S1 stops on another thread.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceStateImpl.ServiceFactoryHolder}
 * ({@code synchronized (bundle)} around {@code factory.getService} and {@code factory.ungetService}) and
 * {@code org.jboss.osgi.framework.internal.ServiceManagerImpl.unregisterService} ({@code synchronized (serviceState)}
 * around the release of the using bundles).
 *
 * <h3>Found by</h3>
 * concurrency workstream: lock order graph recorded by a bytecode agent over the framework core + itest suites
 * ({@code agents/concurrency/lockrec}): edge ServiceStateImpl monitor -&gt; ServiceFactoryHolder.bundle (Bundle
 * monitor) from unregisterService, and callout {@code ServiceFactory.getService} with the Bundle monitor held;
 * the reverse edge (Bundle monitor -&gt; ServiceStateImpl monitor) follows from any getService/ungetService inside a
 * factory. Made deterministic with a ServiceFactory callback that waits until the unregistering thread is BLOCKED.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.3 Synchronization Pitfalls
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html): "Generally, a bundle that calls a
 * listener should not hold any Java monitors. This means that neither the Framework nor the originator of a
 * synchronous event should be in a monitor when a callback is initiated." ... "Java monitors do not have time-outs.
 * Therefore, the code will hang forever until the system is reset". ServiceFactory javadoc (10.1.25): "the Framework
 * must not allow this method to be concurrently called for the specified bundle" - this requires serialising the
 * calls of one factory for one bundle, not holding the monitor of the Bundle object (shared by all factories and by
 * any code that synchronizes on the Bundle) while foreign code runs. ServiceRegistration.unregister javadoc
 * (10.1.30.3) and BundleContext.getService (10.1.7.13) declare no blocking.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:714-725}
 * ({@code ServiceFactoryHolder.getService}: {@code synchronized (bundle) { factory.getService(bundle, ...) }}) and
 * {@code :739-742} ({@code ServiceFactoryHolder.ungetService}: {@code synchronized (bundle) { factory.ungetService(...) }});
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:287} ({@code unregisterService}:
 * {@code synchronized (serviceState)}, then {@code ungetService(bundle, serviceState)} at line 313 -&gt;
 * {@code ServiceStateImpl.ungetScopedValue} -&gt; {@code ServiceFactoryHolder.ungetService} -&gt;
 * {@code synchronized (bundle)}); the factory's {@code getService} of the unregistering service -&gt;
 * {@code ServiceManagerImpl.getService:266} -&gt; {@code ServiceStateImpl.addUsingBundle:458}
 * ({@code synchronized (this)}). {@code synchronized (bundle)} dates from commit 7dfb6fdc (2012, AS7-6056);
 * pre-existing on WF41.
 *
 * <h3>Root cause</h3>
 * Lock order inversion between the ServiceStateImpl monitor of S1 and the Bundle monitor of the consumer:
 * unregister(S1) takes S1 -&gt; Bundle(consumer); getService(S2) for the consumer takes Bundle(consumer) and calls
 * the S2 factory, which gets S1 (Bundle(consumer) -&gt; S1). Both orders exist because foreign code runs under the
 * Bundle monitor and the S1 monitor is held while the framework calls back (factory.ungetService).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=BundleMonitorServiceFactoryDeadlockIssueTestCase
 * </pre>
 * S1 and S2 are ServiceFactory services (registered by the system bundle); a consumer bundle got S1. On the test
 * thread the consumer gets S2; the S2 factory (running under the consumer's Bundle monitor) starts a thread that
 * unregisters S1, waits (at most 5 s) until that thread is BLOCKED, and then gets S1 through its provider's context,
 * as a delegating factory does. The test takes about 10 s and prints a jstack when the threads hang. The framework is
 * left running (it cannot be stopped once deadlocked).
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: getService of a bundle-scoped service and a concurrent unregister() of another one must
 * both return, but after 10 s getService alive=true, unregister alive=true; JVM monitor deadlock:
 *   "concurrency02-get-S2" waits for org.jboss.osgi.framework.internal.ServiceStateImpl@... held by
 *   "concurrency02-unregister-S1" at ...ServiceStateImpl.addUsingBundle(ServiceStateImpl.java:458)
 *   "concurrency02-unregister-S1" waits for org.jboss.osgi.framework.internal.UserBundleState@... held by
 *   "concurrency02-get-S2" at ...ServiceStateImpl$ServiceFactoryHolder.ungetService(ServiceStateImpl.java:739)
 * </pre>
 * (full jstack in run.log)
 *
 * <h3>Expected</h3>
 * Both calls return: the factory gets S1 (or null, if the unregistration has progressed far enough) and S1 is
 * unregistered.
 *
 * <h3>Reference behaviour</h3>
 * Equinox serialises factory calls with a per (registration, bundle) ServiceUse lock that it does not hold while
 * delivering UNREGISTERING and releasing other services, and detects lock cycles (ServiceException); Felix
 * (ServiceRegistry.getService/ungetService) uses a per-usage-count holder and does not lock the Bundle object.
 * Differential (explore/concurrency/diff/Diff.java c02, JDK 25): Equinox 3.21.0 and Felix 7.0.5 return from both calls
 * (their unregistration does not block, it has finished when the factory gets S1); jbosgi hangs with the JVM monitor
 * deadlock.
 *
 * <h3>Suggested fix</h3>
 * Do not synchronize on the Bundle object: serialise the factory calls per (service, bundle) with the holder's own
 * lock (the services/04 fix: ConcurrentHashMap of holders, holder-level lock), and release the using bundles in
 * unregisterService after leaving the service state monitor (see concurrency/01). Either change alone breaks this
 * cycle. Verified with the concurrency/01 trial patch (fix.diff, release outside the service state monitor): the test
 * passes.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * services/04 ConcurrentServiceFactoryGetIssueTestCase (same holder code, data race, not a deadlock);
 * concurrency/01 UnregisteringListenerCrossDeadlockIssueTestCase (ServiceStateImpl monitor held during callouts of
 * unregisterService).
 */
public class BundleMonitorServiceFactoryDeadlockIssueTestCase extends OSGiTest {

    private Framework framework;
    private boolean deadlocked;

    @After
    public void tearDown() throws Exception {
        // A deadlocked framework cannot be stopped; leave it to the JVM exit
        if (framework != null && !deadlocked) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUnregisterFactoryServiceWhileOtherFactoryGetsIt() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency02-bundle-monitor-deadlock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext syscontext = framework.getBundleContext();

        // S1: a bundle-scoped service
        final ServiceRegistration<Runnable> reg1 = syscontext.registerService(Runnable.class, new NoopFactory(), null);
        final ServiceReference<Runnable> ref1 = reg1.getReference();

        // The consumer bundle uses S1
        Bundle consumer = syscontext.installBundle("concurrency02-consumer", manifestOnlyBundle("concurrency02.consumer"));
        consumer.start();
        final BundleContext context = consumer.getBundleContext();
        if (context.getService(ref1) == null)
            fail("precondition: the consumer must get S1");

        // S2: a bundle-scoped service whose factory gets S1 on first use (a delegating factory)
        final AtomicBoolean armed = new AtomicBoolean(true);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final Thread unregistering = new Thread(new Runnable() {
            public void run() {
                try {
                    reg1.unregister();
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "concurrency02-unregister-S1");
        unregistering.setDaemon(true);
        ServiceRegistration<Runnable> reg2 = syscontext.registerService(Runnable.class, new ServiceFactory<Runnable>() {
            public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
                if (armed.getAndSet(false)) {
                    // the provider of S1 stops now: wait until its unregistration is blocked
                    unregistering.start();
                    long deadline = System.currentTimeMillis() + 5000;
                    while (unregistering.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline)
                        Thread.yield();
                    // the delegate
                    syscontext.getService(ref1);
                }
                return new Noop();
            }

            public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
            }
        }, null);
        final ServiceReference<Runnable> ref2 = reg2.getReference();

        Thread getting = new Thread(new Runnable() {
            public void run() {
                try {
                    context.getService(ref2);
                } catch (Throwable th) {
                    failure.compareAndSet(null, th);
                }
            }
        }, "concurrency02-get-S2");
        getting.setDaemon(true);
        getting.start();
        getting.join(10000);
        unregistering.join(1000);

        if (getting.isAlive() || unregistering.isAlive()) {
            String deadlock = describeMonitorDeadlock();
            deadlocked = deadlock != null;
            System.out.println(jstack());
            fail("getService of a bundle-scoped service and a concurrent unregister() of another one must both return, but after 10 s getService alive="
                    + getting.isAlive() + ", unregister alive=" + unregistering.isAlive() + (deadlock != null ? "; JVM monitor deadlock:" + deadlock : ""));
        }
        if (failure.get() != null)
            throw new AssertionError("getService/unregister failed", failure.get());
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

    static class NoopFactory implements ServiceFactory<Runnable> {
        public Runnable getService(Bundle bundle, ServiceRegistration<Runnable> registration) {
            return new Noop();
        }

        public void ungetService(Bundle bundle, ServiceRegistration<Runnable> registration, Runnable service) {
        }
    }
}
