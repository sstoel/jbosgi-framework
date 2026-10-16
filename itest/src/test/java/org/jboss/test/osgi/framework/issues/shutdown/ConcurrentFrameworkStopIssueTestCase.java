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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Two concurrent {@code Framework.stop()} calls both run the whole shutdown: one of them throws a
 * NullPointerException, the system bundle STOPPING event is fired twice and the framework is left in state STOPPING for
 * good (it can never be started again); in an unorchestrated race the second stop instead stalls 10 s and
 * {@code waitForStop} returns {@code null}.
 *
 * <h3>Severity</h3>
 * major. Two parties stopping the framework at the same time is ordinary: a launcher's shutdown hook and a management
 * thread, a bundle that stops the system bundle while the launcher stops the framework, a framework extension refresh
 * ("Framework refresh thread") during a stop, or {@code Framework.update()} racing with {@code stop()}. Depending on the
 * interleaving the outcome is (a) {@code stop()} throws an unchecked NullPointerException, the framework stays in
 * STOPPING forever, {@code init()} is a no-op in that state and {@code start()} throws "JBOSGI011247: Cannot start
 * Framework", i.e. the Framework object is dead and the process has to be restarted; or (b) the second {@code stop()}
 * blocks for the 10 s start-level-event timeout and every later {@code waitForStop()} returns {@code null} instead of a
 * FrameworkEvent (a launcher doing {@code waitForStop(0).getType()} dies with a NullPointerException). Once the WildFly
 * integration stops the framework on server shutdown (connect/27 fixed), a server stop concurrent with a bundle's
 * {@code getBundle(0).stop()} hits the same path.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.shutdownManager(int)} (called by
 * {@code FrameworkProxy.stop/update}, {@code SystemBundleState} stop and {@code FrameworkWiringImpl.refreshBundles} for
 * framework extensions).
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round), exploratory scenario "double stop" ({@code ShutdownExplore1TestCase.e07}: two
 * threads released by one latch call {@code stop()}: 10.5 s, {@code waitForStop} returned null), unorchestrated stress
 * {@code DoubleStopStressTestCase} (7 of 30 iterations: 10 s stall + {@code waitForStop} null), Equinox/Felix
 * differential {@code SdDiff d1}.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.stop()} javadoc (osgi.core 8.0.0): "Stop this Framework. The method returns
 * immediately to the caller after initiating the following steps to be taken on another thread. 1. This Framework's state
 * is set to STOPPING. ... 5. This Framework's state is set to RESOLVED. ... 7. Notify all threads that are waiting at
 * waitForStop that the stop operation has completed. After being stopped, this Framework may be discarded, initialized or
 * started."<br>
 * {@code Bundle.stop(int)} javadoc, which {@code Framework.stop} specialises: "If this bundle's state is UNINSTALLED then
 * an IllegalStateException is thrown. ... If this bundle is in the process of being activated or deactivated then this
 * method must wait for activation or deactivation to complete before continuing" (a second stop must not start a second
 * deactivation).<br>
 * {@code Framework.waitForStop(long)} javadoc: "@return A Framework Event indicating the reason this method returned."
 * (never {@code null}).<br>
 * OSGi Core R8 4.2.8 "Stopping a Framework",
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.stopping
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java} (WF41-osgi-r8 d7b20d08):
 * lines 935-943 check {@code getManagerState()} for STARTING/ACTIVE, then fire the system bundle STOPPING event
 * ({@code getSystemBundle().changeState(Bundle.STOPPING)}, line 942) and only afterwards set the manager state to
 * STOPPING (line 943): a non-atomic check-then-act. Line 956-957 dereferences {@code getSystemBundle()} (null once the
 * other caller has shut the service container down: NPE); line 961 waits up to 10 s for a STARTLEVEL_CHANGED event that
 * is never delivered when the other caller has already shut the event executor down; line 984
 * {@code cachedSystemBundle = getSystemBundle()} overwrites the cached system bundle with {@code null}, so
 * {@code waitForStop} (line 998-1008) has no event source and returns {@code null}. The pattern dates from
 * ee47a809 (2012, "[JBOSGI-606] Add support for SystemBundle.stop()"), unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * {@code shutdownManager} has no guard against concurrent invocation: the "is the framework STARTING or ACTIVE" check
 * and the transition to STOPPING are separate steps (and the STOPPING bundle event, which calls synchronous listeners, is
 * fired between them), so every caller that reads the state before the first caller set STOPPING runs the complete
 * shutdown again, against services that the first caller has already torn down. A caller that loses the race sets the
 * state back to STOPPING after the winner set RESOLVED, and if it then fails (NPE) nothing sets RESOLVED again.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=ConcurrentFrameworkStopIssueTestCase
 * </pre>
 * The test forces the interleaving deterministically: a SynchronousBundleListener receives the system bundle STOPPING
 * event of the first {@code stop()} (the framework state still reads ACTIVE at that point) and lets a second thread call
 * {@code stop()} to completion before it returns.
 *
 * <h3>Observed</h3>
 * <pre>
 * Two concurrent Framework.stop() calls must stop the framework once, both return normally and leave it RESOLVED and
 * restartable, but: [the first stop() threw java.lang.NullPointerException: Cannot invoke
 * "org.jboss.osgi.framework.internal.SystemBundleState.adapt(java.lang.Class)" because the return value of
 * "org.jboss.osgi.framework.internal.BundleManagerPlugin.getSystemBundle()" is null
 *     at org.jboss.osgi.framework.internal.BundleManagerPlugin.shutdownManager(BundleManagerPlugin.java:956)
 *     at org.jboss.osgi.framework.internal.FrameworkProxy.stop(FrameworkProxy.java:278)
 * , the system bundle STOPPING event was fired 2 times (expected once), after both stop() calls and waitForStop the
 * framework state is 16 (expected RESOLVED=4), the stopped framework cannot be started again: start() threw
 * org.osgi.framework.BundleException: JBOSGI011247: Cannot start Framework]
 * </pre>
 * Unorchestrated (two threads released by a latch, 30 iterations): 7 x the slower stop() returned after 10007-10010 ms
 * and {@code waitForStop(30000)} returned {@code null}; 23 x correct.
 *
 * <h3>Expected</h3>
 * Exactly one caller performs the shutdown; a {@code stop()} while the framework is already STOPPING returns at once
 * without side effects; one STOPPING event; the framework ends RESOLVED, {@code waitForStop} returns STOPPED, and the
 * Framework object can be initialized/started again.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5, same orchestration ({@code SdDiff d1}): one system bundle STOPPING event, no
 * exception, {@code waitForStop} = STOPPED (64), final state RESOLVED.
 *
 * <h3>Suggested fix</h3>
 * Claim the transition atomically before anything else, e.g.
 * {@code do { state = managerState.get(); if (state != STARTING && state != ACTIVE) return; } while
 * (!managerState.compareAndSet(state, Bundle.STOPPING));} and only then fire the system bundle STOPPING event (also
 * correct: a synchronous listener then reads STOPPING). Verified as a trial fix (d7b20d08 + this change): the test passes
 * in 0.4 s. {@code waitForStop} should additionally never return {@code null} (keep the cached system bundle once set, or
 * use the Framework proxy as event source).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the test fails the same way on origin/WF41 5a017edc, NPE at BundleManagerPlugin.java:844).
 */
public class ConcurrentFrameworkStopIssueTestCase {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testConcurrentStop() throws Exception {
        framework = newFramework();
        framework.start();

        final AtomicInteger systemStoppingEvents = new AtomicInteger();
        final AtomicReference<Throwable> secondStopFailure = new AtomicReference<Throwable>();
        final AtomicReference<Thread> secondStopper = new AtomicReference<Thread>();
        framework.getBundleContext().addBundleListener(new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().getBundleId() != 0 || event.getType() != BundleEvent.STOPPING)
                    return;
                if (systemStoppingEvents.incrementAndGet() != 1)
                    return;
                // The first stop() delivers the system bundle STOPPING event. A second thread (e.g. a shutdown hook,
                // a management operation) calls stop() now and is allowed to finish before the first stop continues.
                Thread second = new Thread("second-stopper") {
                    @Override
                    public void run() {
                        try {
                            framework.stop();
                            // If this call performed the whole shutdown, wait until the framework services are gone
                            if (framework.getState() == Bundle.RESOLVED)
                                framework.waitForStop(30000);
                        } catch (Throwable th) {
                            secondStopFailure.set(th);
                        }
                    }
                };
                secondStopper.set(second);
                second.start();
                try {
                    second.join(30000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        Throwable firstStopFailure = null;
        try {
            framework.stop();
        } catch (Throwable th) {
            firstStopFailure = th;
        }
        FrameworkEvent stopped = framework.waitForStop(30000);
        int state = framework.getState();
        System.out.println("first stop failure: " + stack(firstStopFailure));
        System.out.println("second stop failure: " + stack(secondStopFailure.get()));
        System.out.println("system bundle STOPPING events: " + systemStoppingEvents.get() + ", state " + state + ", waitForStop " + (stopped != null ? stopped.getType() : null));

        List<String> problems = new ArrayList<String>();
        if (firstStopFailure != null)
            problems.add("the first stop() threw " + stack(firstStopFailure));
        if (secondStopFailure.get() != null)
            problems.add("the second stop() threw " + stack(secondStopFailure.get()));
        if (systemStoppingEvents.get() != 1)
            problems.add("the system bundle STOPPING event was fired " + systemStoppingEvents.get() + " times (expected once)");
        if (stopped == null || stopped.getType() != FrameworkEvent.STOPPED)
            problems.add("waitForStop returned " + (stopped != null ? "event type " + stopped.getType() : "null") + " (expected STOPPED)");
        if (state != Bundle.RESOLVED)
            problems.add("after both stop() calls and waitForStop the framework state is " + state + " (expected RESOLVED=4)");
        try {
            framework.start();
            if (framework.getState() != Bundle.ACTIVE)
                problems.add("the stopped framework cannot be started again: state after start() is " + framework.getState() + " (expected ACTIVE=32)");
        } catch (Throwable th) {
            problems.add("the stopped framework cannot be started again: start() threw " + stack(th));
        }
        assertTrue("Two concurrent Framework.stop() calls must stop the framework once, both return normally and leave it RESOLVED and restartable, but: "
                + problems, problems.isEmpty());
    }

    private static String stack(Throwable th) {
        if (th == null)
            return "nothing";
        StringWriter sw = new StringWriter();
        th.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/concurrent-stop").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
