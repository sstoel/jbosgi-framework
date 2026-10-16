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
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * A bundle that is started while the framework is stopping (by another thread after the shutdown has stopped it, or
 * installed and started from a {@code BundleActivator.stop}) is activated and stays ACTIVE after the framework has
 * stopped: its activator's {@code stop} is never called.
 *
 * <h3>Severity</h3>
 * major. After {@code waitForStop} reported STOPPED, bundle activators keep running in the dead framework: threads,
 * sockets, timers and files they opened are never released (non-daemon threads they started keep the JVM alive), and the
 * bundle state reads ACTIVE although no class space, service registry or event delivery exists any more. Ordinary
 * triggers: an activator whose {@code stop} waits for a worker thread that (re)starts a dependency, a management
 * operation or listener that starts bundles while the stop runs, a provisioning agent that installs/starts bundles when
 * it is stopped, a refresh that restarts its closure (FrameworkWiringImpl.refreshBundlesInternal restarts with
 * START_TRANSIENT). Once the WildFly integration stops the framework on server shutdown (connect/27 fixed), a
 * {@code /subsystem=osgi/bundle=X:start} during the server stop does the same.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.UserBundleState.startInternalNow /
 * startLevelValidForStart}, {@code StartLevelManagerImpl.decreaseFrameworkStartLevel}.
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round): exploratory scenario {@code ShutdownExplore1TestCase.e08} (late bundle ACTIVE after
 * the stop), the randomized shutdown fuzzer {@code ShutdownFuzz} ("bundles running after stop" in 14 of 40 seeds on the branch, 2 of
 * them without any lock timeout, e.g. seed 33; 5 of the first 18 seeds with the known lock-order defects fuzz/16 and concurrency/05 patched out, e.g. seed 1002; this defect, shutdown/04 and the lock timeouts of concurrency/05-06 all contribute to that count), Equinox/Felix differential {@code SdDiff d7, d8}.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.stop()} javadoc (osgi.core 8.0.0): "2. All installed bundles must be stopped
 * without changing each bundle's persistent autostart setting. The start level of this Framework is moved to start level
 * zero (0) ... 5. This Framework's state is set to RESOLVED. 6. All resources held by this Framework are released."<br>
 * OSGi Core R8 9.3 "The Concept of a Start Level": "A start level of 0 (zero) is the state in which the Framework has
 * either not been launched or has completed shutdown ... In this state, no bundles are running."; 9.3.3 "Shutdown
 * Sequence": "When the Framework shuts down, the requested start level must be set to zero."<br>
 * {@code Bundle.start(int)} javadoc: "If the Framework's current start level is less than this bundle's start level ...
 * the Framework must set this bundle's autostart setting ... When the Framework's current start level becomes equal to or
 * more than this bundle's start level, this bundle will be started." - while the framework is moving to level 0 the
 * requested start level is already 0, so a start request can only record the autostart setting.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/UserBundleState.java} (WF41-osgi-r8 d7b20d08):
 * {@code startInternalNow} (line 547) activates the bundle when {@code startLevelValidForStart()} (line 800-803:
 * bundle start level &lt;= <em>active</em> framework start level) holds; it does not look at the framework state. During
 * the shutdown {@code StartLevelManagerImpl.decreaseFrameworkStartLevel} (line 186-219) stops the bundles of the current
 * level and only then decrements the active level, so while it runs the active level still equals the level of the
 * bundles being stopped and any start of such a bundle - one already passed, or one installed after the bundle list
 * was taken - succeeds. Nothing stops these bundles later: {@code BundleManagerPlugin.shutdownManager} shuts the service
 * container down without looking at bundle states. Unchanged from WF41.
 *
 * <h3>Root cause</h3>
 * Bundle activation is not tied to the framework state: a STOPPING framework accepts and performs bundle starts, and the
 * shutdown makes a single pass per start level.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=BundleStartedDuringShutdownIssueTestCase
 * </pre>
 * Deterministic: bundles A and B (B has the higher id and is stopped first); A's activator stop starts a worker that calls
 * {@code B.start()} and waits for it. Second method: A's activator stop installs and starts a new bundle.
 *
 * <h3>Observed</h3>
 * <pre>
 * testBundleRestartedByAnotherThreadDuringShutdown: A bundle started by another thread while the framework stops
 *   (Bundle.start() returned, state 32) must not be running after the framework has stopped (framework state 4), but
 *   these bundles are running and their activators were never stopped: [issues.shutdown.during.b state 32]; activator
 *   calls: [issues.shutdown.during.a.start [main], issues.shutdown.during.b.start [main], issues.shutdown.during.b.stop
 *   [main], issues.shutdown.during.a.stop [main], issues.shutdown.during.b.start [restarter]]
 * testBundleInstalledAndStartedFromActivatorStop: A bundle installed and started from a BundleActivator.stop during the
 *   shutdown (installBundle + start() returned, state 32) must not be running after the framework has stopped (framework
 *   state 4), but these bundles are running ...: [issues.shutdown.during.late state 32]
 * </pre>
 *
 * <h3>Expected</h3>
 * While the framework stops, {@code Bundle.start} only records the autostart setting (or fails); after the stop no
 * bundle is STARTING/ACTIVE.
 *
 * <h3>Reference behaviour</h3>
 * Same scenarios as plain launcher programs ({@code SdDiff d8 / d7}): Equinox 3.21.0 - {@code b.start()} returns, B stays
 * RESOLVED; the late bundle is installed but not started; all bundles stopped. Felix 7.0.5 - {@code b.start()} returns, B
 * stays RESOLVED; {@code installBundle} during the stop throws "BundleException: The framework has been shutdown."; all
 * bundles stopped. Only jbosgi leaves bundles ACTIVE.
 *
 * <h3>Suggested fix</h3>
 * Treat a STOPPING framework as start level 0 for activation: in {@code startLevelValidForStart} (or
 * {@code startInternalNow}) do not activate when the framework manager state is STOPPING (autostart setting is still
 * recorded), and/or let the shutdown stop every bundle that is still active after the level pass. Verified as a trial fix
 * (d7b20d08 + "return false if the manager state is STOPPING" in {@code startLevelValidForStart}): both methods pass; the
 * launch, startlevel and bundle test packages still pass (173 tests).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: both methods fail the same way on origin/WF41 5a017edc).<br>
 * Related: shutdown/04 (StartLevelChangeDuringShutdownIssueTestCase: a start level <em>change</em> after the move to
 * level 0) is a further trigger of the same root cause: measured by sd-verify, this issue's fix.diff also makes both
 * methods of shutdown/04 pass (shutdown/04's own fix does not fix this one). By code reading the fix also covers
 * connect/15. Counts as one root cause: shutdown/08 (main), shutdown/04 and connect/15 (triggers).
 */
public class BundleStartedDuringShutdownIssueTestCase {

    /** System property key of the callback the bundle activators call (a JDK type, visible to the bundle class loaders) */
    static final String HOOK = "issues.shutdown.startduring.hook";

    private final Map<String, BiConsumer<String, BundleContext>> behaviour = new ConcurrentHashMap<String, BiConsumer<String, BundleContext>>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private final List<Bundle> bundles = Collections.synchronizedList(new ArrayList<Bundle>());
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
        System.getProperties().remove(HOOK);
    }

    /**
     * While the framework stops, another thread (a worker that A's activator waits for, a management operation, a
     * listener) starts bundle B, which the shutdown has already stopped.
     */
    @Test
    public void testBundleRestartedByAnotherThreadDuringShutdown() throws Exception {
        framework = newFramework();
        framework.start();
        BundleContext syscontext = framework.getBundleContext();
        install(syscontext, "issues.shutdown.during.a").start();
        final Bundle bundleB = install(syscontext, "issues.shutdown.during.b");
        bundleB.start();

        // The shutdown stops B (higher bundle id) first, then A; A's activator stop lets a worker start B and waits for it
        final AtomicReference<String> restart = new AtomicReference<String>("not attempted");
        behaviour.put("issues.shutdown.during.a", new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                if (!what.equals("stop"))
                    return;
                Thread worker = new Thread("restarter") {
                    @Override
                    public void run() {
                        try {
                            bundleB.start();
                            restart.set("Bundle.start() returned, state " + bundleB.getState());
                        } catch (Exception ex) {
                            restart.set("Bundle.start() threw " + ex);
                        }
                    }
                };
                worker.start();
                try {
                    worker.join(30000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(60000);
        assertEquals("waitForStop must report STOPPED", FrameworkEvent.STOPPED, stopped.getType());
        assertNoBundleRunning("A bundle started by another thread while the framework stops (" + restart.get() + ")");
    }

    /**
     * A's activator stop installs and starts a new bundle during the shutdown (e.g. a provisioning agent that
     * reacts to the stop).
     */
    @Test
    public void testBundleInstalledAndStartedFromActivatorStop() throws Exception {
        framework = newFramework();
        framework.start();
        install(framework.getBundleContext(), "issues.shutdown.during.a").start();
        final AtomicReference<String> late = new AtomicReference<String>("not attempted");
        behaviour.put("issues.shutdown.during.a", new BiConsumer<String, BundleContext>() {
            @Override
            public void accept(String what, BundleContext context) {
                if (!what.equals("stop"))
                    return;
                try {
                    Bundle bundle = install(context, "issues.shutdown.during.late");
                    bundle.start();
                    late.set("installBundle + start() returned, state " + bundle.getState());
                } catch (Exception ex) {
                    late.set("installBundle/start threw " + ex);
                }
            }
        });

        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(60000);
        assertEquals("waitForStop must report STOPPED", FrameworkEvent.STOPPED, stopped.getType());
        assertNoBundleRunning("A bundle installed and started from a BundleActivator.stop during the shutdown (" + late.get() + ")");
    }

    private void assertNoBundleRunning(String what) {
        List<String> running = new ArrayList<String>();
        synchronized (bundles) {
            for (Bundle bundle : bundles) {
                if ((bundle.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0)
                    running.add(bundle.getSymbolicName() + " state " + bundle.getState());
            }
        }
        assertTrue(what + " must not be running after the framework has stopped (framework state " + framework.getState()
                + "), but these bundles are running and their activators were never stopped: " + running + "; activator calls: " + calls, running.isEmpty());
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
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/started-during").getAbsolutePath());
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
