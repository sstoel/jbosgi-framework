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


import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Framework.init()/start() and Framework.stop() clear the interrupt status of the calling thread: the
 * {@code InterruptedException} of an internal wait is swallowed (stop) or wrapped into a {@code BundleException}
 * (init/start fail with "Cannot initialize Framework"), and the interrupt request of the caller is lost.
 *
 * <h3>Severity</h3>
 * minor. A launcher or container thread that is interrupted (shutdown hook racing a start, Future.cancel(true) of a
 * task that starts or stops an embedded framework, an interrupted MSC/management thread) no longer sees the
 * interruption after the framework call returns, so its cancellation/shutdown logic does not run; init/start
 * additionally fail outright because of an interrupt that happened before the call.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkProxy.init/start} and
 * {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * static workstream: Error Prone 2.42.0 InterruptedExceptionSwallowed at FrameworkProxy.java:198 and :250, manual
 * review of the {@code catch (InterruptedException ex) { // ignore }} blocks at BundleManagerPlugin.java:962/978
 * (SpotBugs RV_RETURN_VALUE_IGNORED at :961 led there); Equinox/Felix differential.
 *
 * <h3>Spec reference</h3>
 * The OSGi API does not allow init/start/stop to fail or to consume the interruption because the calling thread was
 * interrupted: {@code Framework.init()} javadoc (osgi.core 8.0.0) lists "BundleException - If this Framework could
 * not be initialized." and {@code Framework.stop()} "The method returns immediately to the caller after initiating
 * the following steps to be taken on another thread." (no interruption semantics). Java SE 25
 * {@code java.lang.InterruptedException} javadoc: "Thrown when a thread is waiting, sleeping, or otherwise occupied,
 * and the thread is interrupted"; Java Concurrency in Practice 7.1.3: only code that implements a thread's
 * interruption policy may swallow an interruption request - library code must restore the status
 * ({@code Thread.currentThread().interrupt()}) when it cannot propagate {@code InterruptedException}. The framework's
 * own LogServices.stop() and FrameworkEventsImpl.awaitFrameworkEvents() do restore it.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:164}
 * ({@code serviceTracker.awaitCompletion()} throws InterruptedException when the caller is interrupted) caught by
 * {@code catch (Throwable ex)} at line 198 and rethrown as {@code MESSAGES.cannotInitializeFramework(ex)} without
 * restoring the status (same pattern in start(): lines 242/250);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:960-964}
 * ({@code latch.await(10, TimeUnit.SECONDS)} in {@code catch (InterruptedException ex) { // ignore }}, which also
 * skips the wait for the start level 0 event) and lines 976-980 (executor awaitTermination, same).
 * Pre-existing: identical on WF41 (BundleManagerPlugin.java:849-852 there).
 *
 * <h3>Root cause</h3>
 * InterruptedException is caught and neither rethrown nor the interrupt status restored.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkInterruptStatusLostIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testInitKeepsInterruptStatus: java.lang.AssertionError: The caller's interrupt status must survive
 *   Framework.init(), but it was cleared (init() threw org.osgi.framework.BundleException: JBOSGI011246: Cannot
 *   initialize Framework caused by java.lang.InterruptedException)
 *   (cause stack: CountDownLatch.await - ServiceTracker.awaitCompletion(ServiceTracker.java:188) -
 *   FrameworkProxy.init(FrameworkProxy.java:164))
 * testStopKeepsInterruptStatus: java.lang.AssertionError: The caller's interrupt status must survive
 *   Framework.stop(), but it was cleared
 * </pre>
 *
 * <h3>Expected</h3>
 * After init()/start()/stop() return (normally or with an exception) the calling thread is still interrupted.
 * Preferably init()/start() do not fail because of an interrupt that predates the call (Equinox).
 *
 * <h3>Reference behaviour</h3>
 * Standalone JDK 25 program (interrupt, then call): Equinox 3.21.0 - init() and start() succeed and the thread is
 * still interrupted; stop() keeps the interrupt status. Felix 7.0.5 - stop() keeps the interrupt status; init()
 * fails (IllegalStateException from its resolver) and loses it as well. (A resolve on an interrupted thread fails in
 * Equinox and Felix too - shared Felix resolver code - so that part is not reported.)
 *
 * <h3>Suggested fix</h3>
 * In FrameworkProxy.init/start catch InterruptedException separately (or check the cause) and call
 * {@code Thread.currentThread().interrupt()} before rethrowing; better, wait uninterruptibly for the MSC services and
 * restore the status afterwards. In BundleManagerPlugin.shutdownManager remember the interruption, keep waiting
 * (uninterruptibly, with the remaining timeout) and call {@code Thread.currentThread().interrupt()} at the end.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 *
 * <h3>Related</h3>
 * connect/08 FrameworkStopUpdateNotAsynchronousIssueTestCase: stop() runs on the caller's thread; once stop() runs on
 * its own thread, the stop part of this issue no longer affects the caller (the swallowed interrupt then only skips
 * the shutdown waits of that thread).
 */
public class FrameworkInterruptStatusLostIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        Thread.interrupted();
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
        Thread.interrupted();
    }

    @Test
    public void testInitKeepsInterruptStatus() throws Exception {
        framework = newFramework("init");
        String outcome;
        Thread.currentThread().interrupt();
        try {
            framework.init();
            outcome = "init() returned normally";
        } catch (BundleException ex) {
            outcome = "init() threw " + ex + " caused by " + ex.getCause();
        }
        boolean stillInterrupted = Thread.interrupted();
        assertTrue("The caller's interrupt status must survive Framework.init(), but it was cleared (" + outcome + ")", stillInterrupted);
    }

    @Test
    public void testStopKeepsInterruptStatus() throws Exception {
        framework = newFramework("stop");
        framework.start();
        Thread.currentThread().interrupt();
        framework.stop();
        boolean stillInterrupted = Thread.interrupted();
        framework.waitForStop(20000);
        assertTrue("The caller's interrupt status must survive Framework.stop(), but it was cleared", stillInterrupted);
    }

    private Framework newFramework(String name) {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/interrupt-status-" + name).getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
