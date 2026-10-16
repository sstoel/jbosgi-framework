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
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * {@code Framework.waitForStop(timeout)} ignores a timeout shorter than 2 s: on a framework that is not stopping it
 * always blocks for 2 s plus the timeout (waitForStop(100) returns WAIT_TIMEDOUT after 2115 ms).
 *
 * <h3>Severity</h3>
 * minor. A launcher or watchdog that polls {@code waitForStop} with a short timeout (e.g. a loop of
 * {@code waitForStop(100)} that also checks a shutdown flag, or a health check) waits 20 times longer than asked; a
 * shutdown sequence that calls {@code waitForStop(t)} for several frameworks or several times adds 2 s each time.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.ShutdownContainer.awaitTermination}
 * (via {@code BundleManagerPlugin.waitForStop}, {@code FrameworkProxy.waitForStop}).
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round), exploratory scenario {@code ShutdownExplore1TestCase.e04}, Equinox/Felix
 * differential {@code SdDiff d3}.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.waitForStop(long)} javadoc (osgi.core 8.0.0): "@param timeout Maximum
 * number of milliseconds to wait until this Framework has completely stopped. A value of zero will wait indefinitely."
 * and "WAIT_TIMEDOUT - This method has timed out and returned before this Framework has stopped."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java} (WF41-osgi-r8 d7b20d08),
 * {@code ShutdownContainer.awaitTermination} lines 1127-1136: if the shutdown has not been initiated it first waits
 * {@code shutdownInitiated.wait(2000)} (a fixed "grace period", comment at line 1094-1096: "The TCK calls waitForStop
 * before it initiates the shutdown, in which case the ServiceContainer returns imediately from awaitTermination") and
 * only then calls {@code serviceContainer.awaitTermination(timeout, unit)} with the full timeout again. Unchanged from
 * WF41.
 *
 * <h3>Root cause</h3>
 * The grace period is not bounded by the caller's timeout and is added to it. It is also unnecessary with the MSC version
 * in use (jboss-msc 1.5.6.Final): {@code ServiceContainerImpl.awaitTermination(long, TimeUnit)} registers a
 * {@code TerminateListener} and waits for it, which also covers a shutdown that has not been initiated yet
 * ({@code addTerminateListener} queues the listener while {@code terminateInfo == null}).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=WaitForStopTimeoutIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * testWaitForStopOnActiveFramework: waitForStop(100) on a ACTIVE framework must return after about 100 ms (the given
 *   maximum), but it returned after 2115 ms
 * testWaitForStopOnInitializedFramework: waitForStop(100) on a STARTING (after init) framework must return after about
 *   100 ms (the given maximum), but it returned after 2119 ms
 * </pre>
 *
 * <h3>Expected</h3>
 * WAIT_TIMEDOUT after about 100 ms.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0: WAIT_TIMEDOUT after 108 ms; Felix 7.0.5: WAIT_TIMEDOUT after 102 ms ({@code SdDiff d3}).
 *
 * <h3>Suggested fix</h3>
 * Drop the 2 s pre-wait and rely on {@code serviceContainer.awaitTermination} (or bound the pre-wait by the remaining
 * timeout and subtract it). Verified as a trial fix (d7b20d08 with the pre-wait removed): both test methods pass, and the
 * existing launch tests (FrameworkShutdownTestCase, FrameworkExtensionTestCase, ConnectFrameworkTestCase,
 * FrameworkBuilderTestCase, FrameworkWiringTestCase) still pass.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: both methods fail the same way on origin/WF41 5a017edc).
 */
public class WaitForStopTimeoutIssueTestCase {

    private static final long TIMEOUT = 100;

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testWaitForStopOnActiveFramework() throws Exception {
        framework = newFramework();
        framework.start();
        assertTimeoutHonoured("ACTIVE");
    }

    @Test
    public void testWaitForStopOnInitializedFramework() throws Exception {
        framework = newFramework();
        framework.init();
        assertTimeoutHonoured("STARTING (after init)");
    }

    private void assertTimeoutHonoured(String state) throws Exception {
        long start = System.nanoTime();
        FrameworkEvent event = framework.waitForStop(TIMEOUT);
        long elapsed = (System.nanoTime() - start) / 1000000;
        assertEquals("waitForStop(" + TIMEOUT + ") on a framework that is not stopping must return WAIT_TIMEDOUT", FrameworkEvent.WAIT_TIMEDOUT, event.getType());
        assertTrue("waitForStop(" + TIMEOUT + ") on a " + state + " framework must return after about " + TIMEOUT + " ms (the given maximum), but it returned after "
                + elapsed + " ms", elapsed < TIMEOUT + 900);
    }

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/wait-for-stop").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
