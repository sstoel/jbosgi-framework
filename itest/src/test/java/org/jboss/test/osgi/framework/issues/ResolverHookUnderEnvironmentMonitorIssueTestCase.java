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

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * ResolverHook.filterMatches is called while the framework holds the Java monitor of its resolver environment
 * (AbstractEnvironment.findProviders is synchronized), the monitor that BundleContext.getBundle(long), getBundles(),
 * installBundle and uninstall also need: these calls on any other thread block until the hook returns, and a hook
 * that waits for such a thread hangs forever.
 *
 * <h3>Severity</h3>
 * major. Every resolve (Bundle.start, resolveBundles, dynamic imports at class load time) with a registered
 * ResolverHook blocks all bundle lookups of the framework while the hook runs; a hook that hands work to another
 * thread (an executor, a region/isolation model updated by another component, a management agent) and waits for it
 * deadlocks permanently (Java monitor, no timeout) as soon as that thread calls getBundle(id), getBundles() or installs a
 * bundle. Equinox 3.21 and Felix 7.0.5 do not block getBundles()/getBundle(id) during filterMatches.
 *
 * <h3>Component</h3>
 * jbosgi-resolver, api, {@code org.jboss.osgi.resolver.spi.AbstractEnvironment} ({@code synchronized findProviders}
 * calling {@code ResolverHookProcessor.filterMatches}); jbosgi-framework, core, {@code BundleManagerPlugin.getBundleById}
 * / {@code getBundles} ({@code XEnvironment.getResourceById/getResources}, synchronized on the same environment).
 *
 * <h3>Found by</h3>
 * concurrency workstream: lock order recorder (bytecode agent, {@code agents/concurrency/lockrec}): callout
 * {@code ResolverHook.filterMatches @ResolverHookProcessor.filterMatches:267 held=[FrameworkWiringLock, bundle lock,
 * FrameworkResolverImpl monitor, AbstractEnvironment monitor]} and edges AbstractEnvironment monitor -&gt; ServiceStateImpl
 * monitor (the hook registration's getService inside findProviders); Equinox/Felix differential
 * {@code explore/concurrency/diff/Diff.java c10}.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.3 Synchronization Pitfalls (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * "Generally, a bundle that calls a listener should not hold any Java monitors. This means that neither the Framework
 * nor the originator of a synchronous event should be in a monitor when a callback is initiated." ... "Java monitors do
 * not have time-outs. Therefore, the code will hang forever until the system is reset."; 53.2.3 Preparing Handlers:
 * "A Resolver Hook Factory must be thread-safe and allow the creation of independent Resolver Hook objects that can be
 * active on multiple threads."; 55.7.5 (service hooks, the same principle): "It is fully allowed to call the
 * framework from any of the hook methods."; BundleContext (10.1.7): "Concurrency Thread-safe" - getBundle(long) and getBundles()
 * have no blocking semantics.
 *
 * <h3>Code location</h3>
 * jbosgi-resolver {@code api/src/main/java/org/jboss/osgi/resolver/spi/AbstractEnvironment.java:217}
 * ({@code public synchronized List<Capability> findProviders(Requirement req)}) and {@code :294}
 * ({@code hookregs.filterMatches((BundleRequirement) req, bcaps)} inside it, commit 273e3a3, 2013); the same monitor in
 * {@code getResourceById:184}, {@code getResources:189-195}, {@code installResources:107},
 * {@code uninstallResources:145}; jbosgi-framework {@code BundleManagerPlugin.java:441-447} ({@code getBundleById}) and
 * {@code :426-436} ({@code getBundles}), reached from {@code AbstractBundleContext.getBundle(long):243} and
 * {@code getBundles():256}. Pre-existing (same code on WF41).
 *
 * <h3>Root cause</h3>
 * Foreign code (the ResolverHook, and the hook factory's ServiceFactory via getService) is called from inside the
 * environment's synchronized findProviders; the environment monitor is the framework-wide lock for the bundle registry.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework   (the jbosgi-resolver jars of the build are used)
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ResolverHookUnderEnvironmentMonitorIssueTestCase
 * </pre>
 * Bundle B imports a package of bundle A; a ResolverHook's first filterMatches call starts a thread that calls
 * getBundle(A's id) and getBundles() and waits for it up to 5 s, then the test resolves B. About 6 s.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: getBundle(long)/getBundles() on another thread must not wait for ResolverHook.filterMatches,
 * but after 5 s it was still blocked: BLOCKED on org.jboss.osgi.resolver.spi.AbstractEnvironment@... owned by "main"
 * at org.jboss.osgi.resolver.spi.AbstractEnvironment.getResourceById(AbstractEnvironment.java:184)
 * expected:&lt;[lookups done]&gt; but was:&lt;[blocked: ...]&gt;
 * </pre>
 * The test prints both stacks (run.log): "concurrency08-lookup" BLOCKED in AbstractEnvironment.getResourceById &lt;-
 * BundleManagerPlugin.getBundleById &lt;- AbstractBundleContext.getBundle; "main" in the hook &lt;-
 * ResolverHookProcessor.filterMatches &lt;- AbstractEnvironment.findProviders (holding the monitor) &lt;- Felix
 * ResolverImpl &lt;- FrameworkResolverImpl.resolveAndApply. 2 of 2 runs failed (JDK 25).
 *
 * <h3>Expected</h3>
 * The lookups complete while the hook runs.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5 (Diff c10, JDK 25): getBundles() and getBundle(id) complete during filterMatches
 * (Felix blocks installBundle on its global resolve lock, Equinox does not block it either); jbosgi blocks all three on
 * the AbstractEnvironment monitor.
 *
 * <h3>Suggested fix</h3>
 * In AbstractEnvironment.findProviders compute the candidate capabilities under the monitor, leave it, and call the
 * resolver hooks (filterMatches) on the copy; obtain the hook services before entering the monitor. Alternatively use a
 * read-write lock for the environment so that lookups are not blocked by a resolve. Verified with a trial patch of
 * jbosgi-resolver (issues/concurrency/08-.../fix.diff: findProviders computes the candidates in a synchronized helper
 * and calls the hooks after leaving it): the test passes and the framework ResolverTestCase (30 tests) still passes.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * fuzz/16 (the resolver monitor is held at the same time), concurrency/05 (FrameworkWiringLock held across callouts).
 */
public class ResolverHookUnderEnvironmentMonitorIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testBundleLookupDuringFilterMatches() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency08-resolver-hook-monitor").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        final BundleContext context = framework.getBundleContext();

        final Bundle bundleA = context.installBundle("concurrency08-a", bundle("concurrency08.a", Constants.EXPORT_PACKAGE, "concurrency08.a"));
        Bundle bundleB = context.installBundle("concurrency08-b", bundle("concurrency08.b", Constants.IMPORT_PACKAGE, "concurrency08.a"));

        final List<String> result = Collections.synchronizedList(new ArrayList<String>());
        final AtomicBoolean armed = new AtomicBoolean(true);
        context.registerService(ResolverHookFactory.class, new ResolverHookFactory() {
            public ResolverHook begin(Collection<BundleRevision> triggers) {
                return new ResolverHook() {
                    public void filterResolvable(Collection<BundleRevision> candidates) {
                    }

                    public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                    }

                    public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                        if (armed.getAndSet(false))
                            lookupOnOtherThread(context, bundleA.getBundleId(), result);
                    }

                    public void end() {
                    }
                };
            }
        }, null);

        framework.adapt(FrameworkWiring.class).resolveBundles(Collections.singleton(bundleB));
        assertEquals("getBundle(long)/getBundles() on another thread must not wait for ResolverHook.filterMatches, but after 5 s it was still "
                + result, Collections.singletonList("lookups done"), new ArrayList<String>(result));
    }

    private static void lookupOnOtherThread(final BundleContext context, final long bundleId, List<String> result) {
        final AtomicBoolean done = new AtomicBoolean();
        Thread thread = new Thread(new Runnable() {
            public void run() {
                context.getBundle(bundleId);
                context.getBundles();
                done.set(true);
            }
        }, "concurrency08-lookup");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (done.get()) {
            result.add("lookups done");
        } else {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(new long[] { thread.getId() }, true, true)[0];
            StringBuilder sb = new StringBuilder("blocked: " + info.getThreadState() + " on " + info.getLockName() + " owned by \"" + info.getLockOwnerName() + "\"");
            for (StackTraceElement element : info.getStackTrace()) {
                if (element.getClassName().startsWith("org.jboss.osgi")) {
                    sb.append(" at ").append(element);
                    break;
                }
            }
            result.add(sb.toString());
            // both stacks for the report
            StringBuilder dump = new StringBuilder("---- blocked lookup thread ----\n");
            for (StackTraceElement element : info.getStackTrace())
                dump.append("\tat ").append(element).append('\n');
            dump.append("---- thread in ResolverHook.filterMatches ----\n");
            for (StackTraceElement element : Thread.currentThread().getStackTrace())
                dump.append("\tat ").append(element).append('\n');
            System.out.println(dump);
        }
    }

    private static InputStream bundle(String symbolicName, String header, String value) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attrs.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        attrs.putValue(header, value);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return new ByteArrayInputStream(baos.toByteArray());
    }
}
