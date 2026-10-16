/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.FrameworkWiring;

/**
 * A start level change that is pending when the framework stops (queued before {@code Framework.stop()}, or requested
 * during the shutdown) runs after the shutdown reached start level 0 and starts the bundles again: after
 * {@code waitForStop} returned STOPPED the framework is RESOLVED but its bundles are ACTIVE and their activators are never
 * stopped.
 *
 * <h3>Severity</h3>
 * major. The framework reports a completed stop while bundle activators are running in it: their threads, sockets,
 * timers and registered resources are never released ({@code BundleActivator.stop} is not called again), non-daemon
 * threads they started keep the JVM alive, and the start level of the dead framework reads 2/5 instead of 0. Any
 * management agent that sets the framework start level (a launcher's "move to the configured start level", the WildFly
 * {@code /subsystem=osgi:write-attribute(name=startlevel)} operation, a bundle restoring its configured level) racing
 * with a stop triggers it; the request is accepted silently. Once the WildFly integration stops the framework on server
 * shutdown (connect/27 fixed), a start level write during the server stop leaves the bundles running.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.StartLevelManagerImpl}
 * ({@code setFrameworkStartLevel}, {@code setFrameworkStartLevelInternal}, {@code increaseFrameworkStartLevel},
 * {@code executeTask}) and {@code BundleManagerPlugin.shutdownManager} (executor shutdown).
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round), exploratory scenario {@code ShutdownExplore1TestCase.e02} (setStartLevel from
 * BundleActivator.stop during the shutdown), Equinox/Felix differential {@code SdDiff d2, d5, d6}.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.stop()} javadoc (osgi.core 8.0.0): "2. All installed bundles must be
 * stopped without changing each bundle's persistent autostart setting. The start level of this Framework is moved to start
 * level zero (0) ... 5. This Framework's state is set to RESOLVED. 6. All resources held by this Framework are released.
 * This includes threads, ..."<br>
 * OSGi Core R8 9.3 "The Concept of a Start Level": "A start level of 0 (zero) is the state in which the Framework has
 * either not been launched or has completed shutdown ... In this state, no bundles are running."<br>
 * 9.3.3 "Shutdown Sequence": "When the Framework shuts down, the requested start level must be set to zero. The
 * Framework must then follow the process described in Changing the Active Start Level to make the active start level
 * equal to zero." (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html)
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java} (WF41-osgi-r8 d7b20d08):
 * {@code setFrameworkStartLevelInternal} (line 97) queues the increase on the single "StartLevel Thread" executor
 * ({@code executeTask}, line 335, checks only {@code executorService.isShutdown()}); {@code increaseFrameworkStartLevel}
 * (line 139, loop at line 154) starts the bundles of every level without looking at the framework state.
 * {@code shutdownFramework} (line 93) moves to level 0 synchronously in the stopping thread, but a request that is queued
 * (or that a bundle makes from {@code BundleActivator.stop} during that move, re-entering the monitor) is executed
 * afterwards: {@code BundleManagerPlugin.shutdownManager} (core/.../BundleManagerPlugin.java:971-980) only calls
 * {@code executor.shutdown()}, which lets the queued tasks run, and waits for them. Unchanged from WF41.
 *
 * <h3>Root cause</h3>
 * Start level change requests are neither rejected nor discarded once the framework is stopping, and the increase does
 * not re-check the framework state; the stop's move to level 0 is just one more request in the sequence, not a final
 * one.<br>
 * Related: connect/20 (StartLevelRequestsOutOfOrderIssueTestCase: requests block or are applied out of order because
 * the direction is chosen at request time under the shared monitor). Its suggested fix (one queued "move to level L"
 * task per request) does not remove this defect: the shutdown's move to 0 runs synchronously in the stopping thread, and
 * a request made during or after it (second test method) would still be queued and executed after it. The fix site here
 * is the framework-state check (discard/reject while STOPPING), which connect/20's fix does not add.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=StartLevelChangeDuringShutdownIssueTestCase
 * </pre>
 * testQueuedStartLevelChangeRestartsBundlesAfterStop: bundle C is started on the start level thread (its bundle start
 * level is lowered to 1) and its activator returns only once the shutdown has begun (A's activator stop called);
 * {@code setStartLevel(2)} is queued behind it, then {@code Framework.stop()} is called. Deterministic: the queued
 * increase waits for the start level monitor that the shutdown holds and runs right after the move to level 0.
 * testStartLevelChangeFromActivatorStopRestartsBundlesAfterStop: A's {@code BundleActivator.stop} calls
 * {@code FrameworkStartLevel.setStartLevel(5)} during the shutdown.
 *
 * <h3>Observed</h3>
 * <pre>
 * testQueuedStartLevelChangeRestartsBundlesAfterStop: A start level change requested before Framework.stop() must not
 *   start bundles after the framework has stopped (framework state 4): a stopped framework has no running bundles (start
 *   level 0), but these are running: [issues.shutdown.sl.c state 32, issues.shutdown.sl.a state 32, issues.shutdown.sl.b
 *   state 32]; activator calls: [issues.shutdown.sl.a.start [main], issues.shutdown.sl.c.start [StartLevel Thread],
 *   issues.shutdown.sl.a.stop [main], issues.shutdown.sl.c.stop [main], issues.shutdown.sl.c.start [StartLevel Thread],
 *   issues.shutdown.sl.a.start [StartLevel Thread], issues.shutdown.sl.b.start [StartLevel Thread]]
 * testStartLevelChangeFromActivatorStopRestartsBundlesAfterStop: ... but these are running: [issues.shutdown.sl.a state
 *   32, issues.shutdown.sl.b state 32]; activator calls: [..., issues.shutdown.sl.b.stop [main], issues.shutdown.sl.a.stop
 *   [main], issues.shutdown.sl.a.start [StartLevel Thread], issues.shutdown.sl.b.start [StartLevel Thread]]
 * </pre>
 * (waitForStop returned STOPPED in both.)
 *
 * <h3>Expected</h3>
 * After the stop no bundle is STARTING/ACTIVE and every activator that was started has been stopped: a pending or
 * concurrent start level increase is discarded (or rejected) once the framework is stopping.
 *
 * <h3>Reference behaviour</h3>
 * Queued request ({@code SdDiff d6}, same orchestration): Equinox 3.21.0 and Felix 7.0.5 stop all bundles (no restart).
 * Request from {@code BundleActivator.stop} or another thread during the shutdown ({@code SdDiff d2/d5}): Equinox throws
 * {@code IllegalStateException: The system has not be activated yet.} and stops all bundles; Felix 7.0.5 behaves like
 * jbosgi here (restarts them: same deviation).
 *
 * <h3>Suggested fix</h3>
 * Make the shutdown final: in {@code increaseFrameworkStartLevel} stop raising when the framework is no longer
 * STARTING/ACTIVE (and do not start bundles from the {@code setBundleStartLevel} runner then); optionally reject
 * {@code setStartLevel} with IllegalStateException while stopping, as Equinox does. Verified as a trial fix (d7b20d08 +
 * a manager-state check at the top of the increase loop): both methods pass, StartLevelTestCase and
 * FrameworkShutdownTestCase still pass.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: both methods fail the same way on origin/WF41 5a017edc).<br>
 * Coordinator note (consolidation, measured by sd-verify): shutdown/08's fix.diff (UserBundleState: refuse to start a
 * bundle while the framework is stopping) also makes both methods of this test pass, while this issue's own fix leaves
 * shutdown/08 failing. Counts as one root cause with shutdown/08 (main issue); this test is a further trigger.
 */
public class StartLevelChangeDuringShutdownIssueTestCase {

    /** System property key of the callback the bundle activators call (a JDK type, visible to the bundle class loaders) */
    static final String HOOK = "issues.shutdown.startlevel.hook";

    private final Map<String, BiConsumer<String, BundleContext>> behaviour = new ConcurrentHashMap<String, BiConsumer<String, BundleContext>>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private Framework framework;

    @Before
    public void setUp() {
        System.getProperties().put(HOOK, new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                String bsn = context.getBundle().getSymbolicName();
                calls.add(bsn + "." + what + " [" + Thread.currentThread().getName() + "]");
                BiConsumer<String, BundleContext> action = behaviour.get(bsn);
                if (action != null)
                    action.accept(what, context);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
        // Stop what the defect left running, so that later tests do not see these activators
        if (framework != null) {
            for (Bundle bundle : bundles) {
                if (bundle.getState() == Bundle.ACTIVE)
                    System.out.println("left ACTIVE after framework stop: " + bundle);
            }
        }
        System.getProperties().remove(HOOK);
    }

    private final List<Bundle> bundles = new ArrayList<Bundle>();

    /**
     * A start level change that was requested before {@code Framework.stop()}, while the start level thread was busy,
     * is executed after the shutdown moved the framework to start level 0: it starts the bundles again.
     */
    @Test
    public void testQueuedStartLevelChangeRestartsBundlesAfterStop() throws Exception {
        framework = newFramework();
        framework.start();
        BundleContext syscontext = framework.getBundleContext();
        Bundle bundleC = install(syscontext, "issues.shutdown.sl.c");
        Bundle bundleA = install(syscontext, "issues.shutdown.sl.a");
        Bundle bundleB = install(syscontext, "issues.shutdown.sl.b");
        // Resolve up front: starting an unresolved bundle takes the framework-wide wiring lock (concurrency/05)
        assertTrue(framework.adapt(FrameworkWiring.class).resolveBundles(null));
        bundleA.start();
        bundleB.adapt(BundleStartLevel.class).setStartLevel(2);
        bundleB.start();
        bundleC.adapt(BundleStartLevel.class).setStartLevel(3);
        bundleC.start();

        // Keep the start level thread busy: moving C to level 1 starts it on that thread; C's activator returns only
        // when the shutdown has begun to stop the bundles (A's activator stop is called)
        final CountDownLatch stopOfAStarted = new CountDownLatch(1);
        final CountDownLatch startOfCEntered = new CountDownLatch(1);
        behaviour.put("issues.shutdown.sl.a", new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                if (what.equals("stop"))
                    stopOfAStarted.countDown();
            }
        });
        behaviour.put("issues.shutdown.sl.c", new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                if (what.equals("start") && startOfCEntered.getCount() > 0) {
                    startOfCEntered.countDown();
                    await(stopOfAStarted);
                }
            }
        });
        bundleC.adapt(BundleStartLevel.class).setStartLevel(1);
        assertTrue("the start of C on the start level thread must begin", startOfCEntered.await(30, TimeUnit.SECONDS));

        // A management agent asks for start level 2; the request is queued behind the start of C
        framework.adapt(FrameworkStartLevel.class).setStartLevel(2);

        // Now the framework is stopped
        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(60000);
        Thread.sleep(1000);

        assertEquals("waitForStop must report STOPPED", FrameworkEvent.STOPPED, stopped.getType());
        assertNoBundleRunning("A start level change requested before Framework.stop()");
    }

    /**
     * A start level change requested by a bundle while it is stopped during the shutdown is executed after the shutdown
     * moved the framework to start level 0.
     */
    @Test
    public void testStartLevelChangeFromActivatorStopRestartsBundlesAfterStop() throws Exception {
        framework = newFramework();
        framework.start();
        BundleContext syscontext = framework.getBundleContext();
        Bundle bundleA = install(syscontext, "issues.shutdown.sl.a");
        Bundle bundleB = install(syscontext, "issues.shutdown.sl.b");
        bundleB.adapt(BundleStartLevel.class).setStartLevel(3);
        bundleA.start();
        bundleB.start();
        final CountDownLatch atFive = new CountDownLatch(1);
        framework.adapt(FrameworkStartLevel.class).setStartLevel(5, new org.osgi.framework.FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                atFive.countDown();
            }
        });
        assertTrue(atFive.await(30, TimeUnit.SECONDS));
        assertEquals(Bundle.ACTIVE, bundleB.getState());

        // A's activator stop requests start level 5 again (e.g. a management bundle that restores its configured level)
        behaviour.put("issues.shutdown.sl.a", new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                if (what.equals("stop"))
                    context.getBundle(0).adapt(FrameworkStartLevel.class).setStartLevel(5);
            }
        });

        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(60000);
        Thread.sleep(1000);

        assertEquals("waitForStop must report STOPPED", FrameworkEvent.STOPPED, stopped.getType());
        assertNoBundleRunning("A start level change requested from BundleActivator.stop during Framework.stop()");
    }

    private void assertNoBundleRunning(String what) {
        List<String> running = new ArrayList<String>();
        for (Bundle bundle : bundles) {
            if ((bundle.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0)
                running.add(bundle.getSymbolicName() + " state " + bundle.getState());
        }
        assertTrue(what + " must not start bundles after the framework has stopped (framework state " + framework.getState()
                + "): a stopped framework has no running bundles (start level 0), but these are running: " + running + "; activator calls: " + calls,
                running.isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Bundle install(BundleContext context, final String bsn) throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, bsn);
        archive.addClasses(Activator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(bsn);
                builder.addBundleActivator(Activator.class);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        Bundle bundle = context.installBundle(bsn, archive.as(ZipExporter.class).exportAsInputStream());
        bundles.add(bundle);
        return bundle;
    }

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/startlevel-change").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }

    /** Packed into the test bundles; forwards to the callback in the system properties */
    public static class Activator implements BundleActivator {

        @SuppressWarnings("unchecked")
        private static void call(String what, BundleContext context) {
            Object hook = System.getProperties().get(HOOK);
            if (hook != null)
                ((BiConsumer<String, BundleContext>) hook).accept(what, context);
        }

        @Override
        public void start(BundleContext context) {
            call("start", context);
        }

        @Override
        public void stop(BundleContext context) {
            call("stop", context);
        }
    }
}
