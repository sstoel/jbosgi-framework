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

import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.await;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hook;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.hookedBundle;
import static org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport.threadsNamed;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.testing.OSGiTest;
import org.jboss.test.osgi.framework.issues.hooks.ReleaseCheckSupport;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.launch.Framework;

/**
 * An activator that stops the framework while Framework.start raises the start level: start sets the framework ACTIVE
 * when it returns, while the stop is in progress. A second Framework.stop then starts a second shutdown next to the
 * first one, and the framework can end ACTIVE after the stop, with nothing running.
 *
 * <h2>Severity</h2>
 * minor. Only a framework that is launched through its Framework object (not in the server); the double stop is new.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkProxy.start}, {@code BundleManagerPlugin.setManagerState}.
 *
 * <h2>Found by</h2>
 * R8 release check, FW-02 (residual of shutdown/01).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.2.6 Framework.stop: a stop of a framework that is stopping returns immediately; Framework.start sets
 * the state ACTIVE only for a framework that is starting.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:277 (84ac5c2e) sets ACTIVE unconditionally,
 * after the raise of the start level in which the activator stopped the framework.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -pl itest test -Dtest=FrameworkStopDuringStartIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The framework is STOPPING when start returns, a second stop runs no second shutdown, the system bundle fires one
 * STOPPING event and the framework ends RESOLVED.
 *
 * <h2>Regression vs WF41</h2>
 * the ACTIVE state during the stop: no; the second shutdown: yes (3093973d removed the check of the running stop thread).
 */
public class FrameworkStopDuringStartIssueTestCase extends OSGiTest {

    private static final String STOP_THREAD = "Framework stop thread";
    private final CountDownLatch inStopOfB2 = new CountDownLatch(1);
    private final CountDownLatch releaseStopOfB2 = new CountDownLatch(1);
    private Framework framework;

    @After
    public void tearDown() throws Exception {
        releaseStopOfB2.countDown();
        ReleaseCheckSupport.stop(framework);
        ReleaseCheckSupport.clearHooks();
    }

    @Test
    public void testActivatorStopsFrameworkDuringStart() throws Exception {
        File storage = ReleaseCheckSupport.storage("stop-during-start");
        ReleaseCheckSupport.delete(storage);
        framework = ReleaseCheckSupport.newFramework(storage, true);
        framework.init();
        BundleContext context = framework.getBundleContext();
        final AtomicInteger stoppingEvents = new AtomicInteger();
        context.addBundleListener(new SynchronousBundleListener() {
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().getBundleId() == 0 && event.getType() == BundleEvent.STOPPING)
                    stoppingEvents.incrementAndGet();
            }
        });

        // The raise of the start level activates B2, then B1, which stops the framework; the stop of B2 waits
        hook("stop", "stop.during.start.b2", ctx -> {
            inStopOfB2.countDown();
            try {
                releaseStopOfB2.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        hook("start", "stop.during.start.b1", ctx -> {
            try {
                ctx.getBundle(0).stop();
            } catch (BundleException ex) {
                throw new IllegalStateException(ex);
            }
        });
        Bundle b2 = context.installBundle("stop-during-start-b2", hookedBundle("stop.during.start.b2"));
        Bundle b1 = context.installBundle("stop-during-start-b1", hookedBundle("stop.during.start.b1"));
        b2.start();
        b1.start();

        Thread starter = new Thread(() -> {
            try {
                framework.start();
            } catch (BundleException ex) {
                ex.printStackTrace();
            }
        }, "stop-during-start");
        starter.start();
        await(inStopOfB2, "the stop of the framework stops B2");
        starter.join(20000);
        assertEquals("Precondition: Framework.start returned", false, starter.isAlive());
        int stateAfterStart = framework.getState();

        // A stop of a framework that is stopping does not start a second shutdown
        framework.stop();
        Thread.sleep(200);
        int stopThreads = threadsNamed(STOP_THREAD).size();
        int events = stoppingEvents.get();

        releaseStopOfB2.countDown();
        FrameworkEvent stopped = framework.waitForStop(30000);
        assertEquals("The framework is stopping when start returns (STOPPING=16, ACTIVE=32)", Bundle.STOPPING, stateAfterStart);
        assertEquals("One shutdown runs", 1, stopThreads);
        assertEquals("The system bundle is stopped once", 1, events);
        assertEquals(FrameworkEvent.STOPPED, stopped.getType());
        assertEquals("The framework is stopped", Bundle.RESOLVED, framework.getState());
    }
}
