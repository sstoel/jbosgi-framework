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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * The service object of a bundle scope ServiceFactory is not cached safely: concurrent first calls of getService
 * from one bundle call the factory once per thread and hand out different service objects, and concurrent first
 * calls from different bundles corrupt the per-bundle cache; the extra service objects are never released.
 *
 * <h3>Severity</h3>
 * major. Every bundle scope service (and every Declarative Services component registered as a ServiceFactory) can
 * be instantiated several times for one bundle when the bundle's threads get it at the same time, e.g. during
 * startup or from a thread pool. The bundle uses different instances although it must see exactly one, and the
 * factory is told to release only one of them: the other instances are leaked (never passed to
 * {@code ServiceFactory.ungetService}, so components are never deactivated).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.ServiceStateImpl (getScopedValue, ServiceFactoryHolder)
 *
 * <h3>Found by</h3>
 * R8 verification, delivered test; committed for the R8 release check.
 * services workstream, concurrency stress test of get/unget, confirmed with an Equinox / Felix differential
 * (8 threads of one bundle get a bundle scope service at the same time).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, BundleContext.getService(ServiceReference)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.BundleContext.getService-ServiceReference-):
 * "If the context bundle's use count for the service is currently zero and the service has bundle or prototype
 * scope, the ServiceFactory.getService(Bundle, ServiceRegistration) method is called to supply the service object
 * for the context bundle. ... The supplied service object is cached by the Framework. While the context bundle's
 * use count for the service is greater than zero, subsequent calls to get the service object for the context
 * bundle will return the cached service object."
 * ServiceFactory.getService: "The Framework invokes this method the first time the specified bundle requests a
 * service object using the BundleContext.getService(ServiceReference) method. ... The Framework must not allow this
 * method to be concurrently called for the same bundle."
 * BundleContext.ungetService: "If the context bundle's use count for the service is now zero and the service has
 * bundle or prototype scope, the ServiceFactory.ungetService(Bundle, ServiceRegistration, Object) method is called to
 * release the service object for the context bundle."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:143-151 (unsynchronized lazy
 * {@code HashMap factoryValues}, check-then-put of the holder) and :710-730
 * ({@code ServiceFactoryHolder.getService}: {@code if (useCount.get() == 0) { synchronized (bundle) { value = factory.getService(...) } } useCount.incrementAndGet();}).
 * Code from the original implementation (commits faf3b793, 536f874e), already on WF41.
 *
 * <h3>Root cause</h3>
 * (1) {@code ServiceFactoryHolder.getService} tests {@code useCount == 0} outside the lock and increments the use
 * count only after leaving it, so every thread that saw 0 calls the factory in turn, overwrites {@code value} and
 * returns its own object. {@code ungetService} later releases only the last value, once.
 * (2) {@code getScopedValue} creates {@code factoryValues} lazily and puts holders into a plain HashMap without a
 * lock, so concurrent first gets from different bundles can lose a holder or see another bundle's half-initialised
 * one; the bundle then gets a second object from the factory on its next call.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=ConcurrentServiceFactoryGetIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testConcurrentGetInOneBundle: The threads of one bundle must all get the same cached service object
 *   expected:&lt;1&gt; but was:&lt;8&gt;
 * testConcurrentGetFromManyBundles: Round N: each of the 64 bundles must get one service object, but the factory was
 *   called 65 times, 1 service objects were never released, problems: [concurrent-factory-get-6 got two different
 *   service objects]   (or: called 64 times, 1 service objects were never released - a holder was lost between get
 *   and unget, so the object is leaked)
 * </pre>
 * testConcurrentGetInOneBundle fails deterministically (the factory sleeps, so all 8 threads pass the unlocked
 * {@code useCount == 0} check). testConcurrentGetFromManyBundles races on the unsynchronized HashMap (its lazy
 * creation and resizes), which no callback can hold open, so it cannot be forced; it is widened instead: 64 bundles
 * (several resizes per round), a fresh registration per round, up to 10000 rounds (-Dconcurrent.factory.rounds), and
 * it stops at the first bad round. JDK 25, 10 consecutive runs on the current code: 10 of 10 failed, the first bad
 * round was 0-14 in 9 runs and 761 in one. With the fix below all 10000 rounds pass in about 5 s.
 * (The earlier version, 16 bundles / 500 rounds, failed in only 2 of 3 runs elsewhere.)
 *
 * <h3>Expected</h3>
 * One factory call per bundle while its use count is above zero; all threads of the bundle get that object; after
 * the bundle released the service as often as it got it, the factory has released exactly the objects it created.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5, 8 threads of one bundle: 1 service object, 1 factory getService call, 1
 * factory ungetService call. JBoss OSGi: 8 objects, 8 factory getService calls, 1 ungetService call.
 *
 * <h3>Suggested fix</h3>
 * Make the per-bundle cache atomic: e.g. a ConcurrentHashMap of holders created with computeIfAbsent, and in the
 * holder do the {@code useCount == 0} check, the factory call and the increment under the holder's lock (keep the
 * lock per (service, bundle) rather than locking the Bundle object); the same lock in ungetService.
 *
 * Verified: a final ConcurrentHashMap factoryValues filled with computeIfAbsent, and synchronized
 * ServiceFactoryHolder.getService/ungetService, make both methods pass (10000 rounds without a problem).
 *
 * <h3>Regression vs WF41</h3>
 * no (original code).
 */
public class ConcurrentServiceFactoryGetIssueTestCase extends OSGiFrameworkTest {

    static class CountingFactory implements ServiceFactory<Object> {
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger ungets = new AtomicInteger();
        final Map<Object, Bundle> live = Collections.synchronizedMap(new IdentityHashMap<Object, Bundle>());

        @Override
        public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
            gets.incrementAndGet();
            try {
                // a factory that does some work, e.g. activates a component
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            Object service = new Object();
            live.put(service, bundle);
            return service;
        }

        @Override
        public void ungetService(Bundle bundle, ServiceRegistration<Object> registration, Object service) {
            ungets.incrementAndGet();
            live.remove(service);
        }
    }

    @Test
    public void testConcurrentGetInOneBundle() throws Exception {
        final BundleContext context = getSystemContext();
        final int threads = 8;
        CountingFactory factory = new CountingFactory();
        ServiceRegistration<?> sreg = context.registerService(Object.class.getName(), factory, null);
        final ServiceReference<?> sref = sreg.getReference();
        try {
            final CyclicBarrier barrier = new CyclicBarrier(threads);
            final Set<Object> seen = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<Void>> futures = new ArrayList<Future<Void>>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        barrier.await();
                        seen.add(context.getService(sref));
                        return null;
                    }
                }));
            }
            for (Future<Void> future : futures)
                future.get(30, TimeUnit.SECONDS);
            executor.shutdown();

            for (int i = 0; i < threads; i++)
                assertTrue("The use count is " + (threads - i) + " before this release", context.ungetService(sref));

            assertEquals("The threads of one bundle must all get the same cached service object", 1, seen.size());
            assertEquals("The factory must be called once for the bundle", 1, factory.gets.get());
            assertEquals("Every service object created by the factory must be released when the use count drops to zero", 0, factory.live.size());
        } finally {
            sreg.unregister();
        }
    }

    @Test
    public void testConcurrentGetFromManyBundles() throws Exception {
        BundleContext context = getSystemContext();
        // 64 bundles: several resizes of the per service HashMap per round, and 64 threads racing on its lazy creation
        final int count = 64;
        List<Bundle> bundles = new ArrayList<Bundle>();
        for (int i = 0; i < count; i++) {
            Bundle bundle = installBundle(getBundleArchive("concurrent-factory-get-" + i));
            bundle.start();
            bundles.add(bundle);
        }
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            // a fresh registration per round (its holder map starts out null); stop at the first bad round
            int rounds = Integer.getInteger("concurrent.factory.rounds", 10000);
            long start = System.currentTimeMillis();
            for (int round = 0; round < rounds; round++) {
                CountingFactory factory = new CountingFactory() {
                    @Override
                    public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
                        // no delay, the race is on the per bundle cache of the service
                        gets.incrementAndGet();
                        Object service = new Object();
                        live.put(service, bundle);
                        return service;
                    }
                };
                ServiceRegistration<?> sreg = context.registerService(Object.class.getName(), factory, null);
                final ServiceReference<?> sref = sreg.getReference();
                final CyclicBarrier barrier = new CyclicBarrier(count);
                List<Future<String>> futures = new ArrayList<Future<String>>();
                for (final Bundle bundle : bundles) {
                    futures.add(executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            BundleContext bundleContext = bundle.getBundleContext();
                            barrier.await();
                            Object first = bundleContext.getService(sref);
                            Object second = bundleContext.getService(sref);
                            bundleContext.ungetService(sref);
                            bundleContext.ungetService(sref);
                            return first == second ? null : bundle.getSymbolicName() + " got two different service objects";
                        }
                    }));
                }
                List<String> problems = new ArrayList<String>();
                for (Future<String> future : futures) {
                    String problem = future.get(30, TimeUnit.SECONDS);
                    if (problem != null)
                        problems.add(problem);
                }
                sreg.unregister();
                if (problems.isEmpty() == false || factory.gets.get() != count || factory.live.isEmpty() == false)
                    fail("Round " + round + ": each of the " + count + " bundles must get one service object, but the factory was called "
                            + factory.gets.get() + " times, " + factory.live.size() + " service objects were never released, problems: " + problems);
            }
            System.out.println("testConcurrentGetFromManyBundles: " + rounds + " rounds without a problem in " + (System.currentTimeMillis() - start) + " ms");
        } finally {
            executor.shutdownNow();
            for (Bundle bundle : bundles)
                bundle.uninstall();
        }
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
