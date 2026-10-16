/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 RTR
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * #L%
 */
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Permanent monitor deadlock between two {@code getService} calls of bundle-scoped services (ServiceFactory) whose
 * factories look up another bundle-scoped service with their own bundle's context - the way Declarative Services
 * activates a delayed component and binds its references. The framework calls every {@code ServiceFactory.getService}
 * while it holds the Java monitor of the whole requesting {@code Bundle} object
 * ({@code ServiceStateImpl$ServiceFactoryHolder.getService}, {@code synchronized (bundle)}), so nested factory calls
 * requested by bundle A and bundle B in opposite order deadlock, although they concern four different
 * (service, bundle) pairs that never need to wait for each other.
 *
 * <h3>Severity</h3>
 * critical (permanent JVM deadlock, no timeout: reachable by ordinary DS bundles whose components reference services
 * of components in other bundles; seen twice by the server fuzzer as a server BOOT that never completes, MSC thread
 * included; the same lock nesting exists in {@code ungetService} on component deactivation at shutdown).
 *
 * <h3>Component</h3>
 * jbosgi-framework core: {@code org.jboss.osgi.framework.internal.ServiceStateImpl.ServiceFactoryHolder.getService}
 * / {@code ungetService} (factory callouts under {@code synchronized (bundle)}).
 *
 * <h3>Found by</h3>
 * sd-srv-fuzz workstream (shutdown round), randomized server lifecycle fuzzer on the patched WildFly 41.0.1 + jbosgi
 * R8 (JDK 25), probe bundles with DS components (Felix SCR 2.1.30) that have multiple references to Runnable services
 * of other probe bundles plus worker threads using those services: twice the restart never reached WFLYSRV0025 (fuzz
 * f2 seed 104 iteration 35, fuzz f1 seed 4 iteration 7), dumps "Found one Java-level deadlock" with
 * <pre>
 * "fz-worker-fz.b5.s-2": ServiceStateImpl$ServiceFactoryHolder.getService(ServiceStateImpl.java:715)
 *       - waiting to lock UserBundleState@...0628   &lt;- SCR SingleRefPair.getServiceObject &lt;- DependencyManager.prebind
 *       &lt;- SingleComponentManager.getService &lt;- ServiceFactoryHolder.getService - locked UserBundleState@...8e40
 *       &lt;- SCR prebind &lt;- ServiceFactoryHolder.getService - locked UserBundleState@...7e80 &lt;- BundleContext.getService
 * "fz-worker-fz.b3.s-0": ServiceStateImpl$ServiceFactoryHolder.getService(ServiceStateImpl.java:715)
 *       - waiting to lock UserBundleState@...8e40   &lt;- SCR prebind &lt;- SingleComponentManager.getService
 *       &lt;- ServiceFactoryHolder.getService - locked UserBundleState@...0628 &lt;- BundleContext.getService
 * "MSC service thread 1-3" (boot: Activator$ScrExtension.start of a deployed bundle) and two more threads blocked
 *       behind them, for ever.
 * </pre>
 * This test is the minimal forced form of that cycle, without DS.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 {@code ServiceFactory.getService} javadoc (https://docs.osgi.org/specification/osgi.core/8.0.0/
 * framework.api.html): "The Framework caches the valid service object and will return the same service object on
 * any future call to BundleContext.getService(ServiceReference) for the specified bundle. This means the Framework
 * must not allow this method to be concurrently called for the specified bundle." - the serialisation follows from
 * the per (service, bundle) cache, so it is required per factory and bundle, not across all services the bundle uses. 4.7.3 Synchronization Pitfalls: "Generally, a bundle that calls a listener
 * should not hold any Java monitors. ... Time-outs can be used to break deadlocks, but Java monitors do not have
 * time-outs. Therefore, the code will hang forever until the system is reset".
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:710-726 ({@code getService}, :714
 * {@code synchronized (bundle) { factory.getService(bundle, getRegistration()) ... }}) and :733-745
 * ({@code ungetService}, :739 {@code synchronized (bundle) { factory.ungetService(...) }}). The Bundle-object lock is
 * old (present since 7dfb6fdc, 2012, and on WF41 at :602/:627).
 *
 * <h3>Root cause</h3>
 * The lock that serialises factory callbacks is the requesting Bundle object, shared by all services that bundle
 * uses, and it is held across the callout into foreign code. Lock pair: Bundle(A) monitor and Bundle(B) monitor
 * (thread 1: B requests SA -&gt; holds Bundle(B), the SA factory runs as A and requests SB1 -&gt; waits for Bundle(A);
 * thread 2: A requests SB2 -&gt; holds Bundle(A), the SB2 factory runs as B and requests SA2 -&gt; waits for Bundle(B)).
 * <p>
 * Related: concurrency/02 (BundleMonitorServiceFactoryDeadlockIssueTestCase) is the ServiceStateImpl &lt;-&gt;
 * Bundle monitor cycle of the same callout; its verified trial fix (concurrency/01's patch: release outside the
 * service state monitor) does not touch {@code synchronized (bundle)} and does NOT remove this Bundle &lt;-&gt; Bundle
 * cycle; the services/04 trial fix (holder-level synchronized) keeps {@code synchronized (bundle)} inside and does not
 * remove it either. Only the first half of concurrency/02's suggested fix ("Do not synchronize on the Bundle object")
 * does, which no delivered test checks. LOCK-GRAPH.md lists only C5 (ServiceStateImpl &lt;-&gt; Bundle).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=NestedServiceFactoryBundleMonitorDeadlockIssueTestCase
 * </pre>
 * Bundles A and B each register two bundle-scoped services (SA, SA2 and SB1, SB2). The SA factory, when B requests
 * SA, gets SB1 with A's context; the SB2 factory, when A requests SB2, gets SA2 with B's context. Two threads call
 * B.getService(SA) and A.getService(SB2); each factory waits (at most 5 s) until the other factory has been entered,
 * which forces the interleaving that the fuzzer hit by chance. The test takes about 15 s and prints an in-process
 * thread dump; the deadlocked framework is left to the JVM exit.
 *
 * <h3>Observed</h3>
 * JDK 25, WF41-osgi-r8 (jbosgi-framework d7b20d08), run.log:
 * <pre>
 * java.lang.AssertionError: Two getService calls of different bundle-scoped services (B gets SA, A gets SB2), whose
 *   factories get further different services (SB1, SA2) with their own bundle's context, must both return, but after
 *   15 s B-gets-SA alive=true, A-gets-SB2 alive=true; JVM monitor deadlock on the requesting Bundle objects:
 *   "nested-factory-B-gets-SA" waits for org.jboss.osgi.framework.internal.UserBundleState@35a0e495 held by
 *     "nested-factory-A-gets-SB2" at ...ServiceStateImpl$ServiceFactoryHolder.getService(ServiceStateImpl.java:714)
 *   "nested-factory-A-gets-SB2" waits for org.jboss.osgi.framework.internal.UserBundleState@37c41ec0 held by
 *     "nested-factory-B-gets-SA" at ...ServiceStateImpl$ServiceFactoryHolder.getService(ServiceStateImpl.java:714)
 * </pre>
 *
 * <h3>Expected</h3>
 * Both getService calls return their service objects (the four (service, bundle) factory calls are independent).
 *
 * <h3>Reference behaviour</h3>
 * Differential with the same program (explore/sd-srv-fuzz/diff/NFDiff2.java, JDK 25): Equinox 3.21.0 and Felix 7.0.5
 * both return from both calls without errors (they serialise per (registration, bundle): Equinox ServiceUse lock,
 * Felix UsageCount); jbosgi hangs with the JVM monitor deadlock on the two UserBundleState objects. (With a true
 * cycle on the same (service, bundle) pairs - explore/sd-srv-fuzz/diff/NFDiff.java - Felix hangs too and Equinox
 * breaks it with a ServiceException "Deadlock while trying to acquire use lock"; that variant is inherent to the
 * spec's per-bundle serialisation and is not what this test checks.)
 *
 * <h3>Suggested fix</h3>
 * Serialise factory calls per (service, bundle) with the ServiceFactoryHolder's own lock (or a per-holder
 * "in progress" future like Felix) instead of {@code synchronized (bundle)}, and ideally do not hold even that lock
 * while waiting for other holders (Equinox detects use-lock cycles and throws a ServiceException).
 * Trials (fix.diff in the issue folder, never applied to a checkout; jbosgi-framework d7b20d08, JDK 25):
 * {@code synchronized (this)} (the holder) instead of {@code synchronized (bundle)} in getService/ungetService: this
 * test passes. The concurrency/01 trial patch (which makes concurrency/01, /02 and static/06 pass): this test still
 * fails with the same deadlock. The services/04 trial patch (holder-level synchronized, Bundle lock kept inside):
 * still fails.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the same test in a jbosgi-framework worktree at origin/WF41 5a017edc, JDK 25: same failure, deadlock at
 * ServiceStateImpl$ServiceFactoryHolder.getService(ServiceStateImpl.java:603)).
 */
public class NestedServiceFactoryBundleMonitorDeadlockIssueTestCase extends OSGiTest {

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
    public void testNestedFactoriesOfDifferentServicesRequestedByTwoBundles() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/shutdown-nested-factory-deadlock").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext syscontext = framework.getBundleContext();

        Bundle bundleA = syscontext.installBundle("nested-factory-a", manifestOnlyBundle("nested.factory.a"));
        Bundle bundleB = syscontext.installBundle("nested-factory-b", manifestOnlyBundle("nested.factory.b"));
        bundleA.start();
        bundleB.start();
        final BundleContext contextA = bundleA.getBundleContext();
        final BundleContext contextB = bundleB.getBundleContext();

        final CountDownLatch inSA = new CountDownLatch(1);
        final CountDownLatch inSB2 = new CountDownLatch(1);
        final AtomicReference<ServiceReference<Runnable>> refSB1 = new AtomicReference<ServiceReference<Runnable>>();
        final AtomicReference<ServiceReference<Runnable>> refSA2 = new AtomicReference<ServiceReference<Runnable>>();
        final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();

        // SA (bundle A): its factory resolves a dependency, SB1 of bundle B, with A's own context (like DS binding)
        ServiceRegistration<Runnable> regSA = contextA.registerService(Runnable.class, new ServiceFactory<Runnable>() {
            public Runnable getService(Bundle requester, ServiceRegistration<Runnable> registration) {
                inSA.countDown();
                await(inSB2);
                if (contextA.getService(refSB1.get()) == null)
                    failures.add(new IllegalStateException("SA factory did not get SB1"));
                return new Noop();
            }

            public void ungetService(Bundle requester, ServiceRegistration<Runnable> registration, Runnable service) {
            }
        }, null);
        ServiceRegistration<Runnable> regSA2 = contextA.registerService(Runnable.class, new NoopFactory(), null);

        // SB2 (bundle B): its factory resolves a dependency, SA2 of bundle A, with B's own context
        ServiceRegistration<Runnable> regSB2 = contextB.registerService(Runnable.class, new ServiceFactory<Runnable>() {
            public Runnable getService(Bundle requester, ServiceRegistration<Runnable> registration) {
                inSB2.countDown();
                await(inSA);
                if (contextB.getService(refSA2.get()) == null)
                    failures.add(new IllegalStateException("SB2 factory did not get SA2"));
                return new Noop();
            }

            public void ungetService(Bundle requester, ServiceRegistration<Runnable> registration, Runnable service) {
            }
        }, null);
        ServiceRegistration<Runnable> regSB1 = contextB.registerService(Runnable.class, new NoopFactory(), null);
        refSB1.set(regSB1.getReference());
        refSA2.set(regSA2.getReference());
        final ServiceReference<Runnable> refSA = regSA.getReference();
        final ServiceReference<Runnable> refSB2 = regSB2.getReference();

        Thread bGetsSA = new Thread(new Runnable() {
            public void run() {
                try {
                    contextB.getService(refSA);
                } catch (Throwable th) {
                    failures.add(th);
                }
            }
        }, "nested-factory-B-gets-SA");
        Thread aGetsSB2 = new Thread(new Runnable() {
            public void run() {
                try {
                    contextA.getService(refSB2);
                } catch (Throwable th) {
                    failures.add(th);
                }
            }
        }, "nested-factory-A-gets-SB2");
        bGetsSA.setDaemon(true);
        aGetsSB2.setDaemon(true);
        bGetsSA.start();
        aGetsSB2.start();
        bGetsSA.join(15000);
        aGetsSB2.join(1000);

        if (bGetsSA.isAlive() || aGetsSB2.isAlive()) {
            String deadlock = describeMonitorDeadlock();
            deadlocked = deadlock != null;
            System.out.println(jstack());
            fail("Two getService calls of different bundle-scoped services (B gets SA, A gets SB2), whose factories get further different services (SB1, SA2) with their own bundle's context, must both return, but after 15 s "
                    + "B-gets-SA alive=" + bGetsSA.isAlive() + ", A-gets-SB2 alive=" + aGetsSB2.isAlive()
                    + (deadlock != null ? "; JVM monitor deadlock on the requesting Bundle objects:" + deadlock : ""));
        }
        if (!failures.isEmpty())
            throw new AssertionError("getService failed: " + failures, failures.get(0));
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
                sb.append('"').append(ti.getThreadName()).append("\" ").append(ti.getThreadState()).append(" waiting to lock ")
                        .append(ti.getLockName()).append(" held by \"").append(ti.getLockOwnerName()).append("\"\n");
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
