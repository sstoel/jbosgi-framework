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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 * A Framework.update right after Framework.stop, or a stop of the system bundle during an update, stops the framework
 * twice or starts it again after the stop.
 *
 * <h2>Severity</h2>
 * minor. The bundles are stopped twice, or the framework that the caller stopped runs again.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code BundleManagerPlugin.stopFramework} and {@code shutdownManager},
 * {@code FrameworkProxy.update}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of shutdown/01 (fw-major-04).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.2.7: Framework.stop sets the state to STOPPING; update does nothing when the framework is not STARTING
 * or ACTIVE.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:1184-1196 (fa32e5ab).
 *
 * <h2>Root cause</h2>
 * The stop checked the state and set STOPPING later, on its own thread: a stop and an update that came in between both
 * passed the check.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkStopUpdateRaceIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The framework is stopped once. An update after a stop does nothing, a stop of the system bundle during an update
 * does not stop the framework a second time.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class FrameworkStopUpdateRaceIssueTestCase {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testUpdateRightAfterStop() throws Exception {
        framework = newFramework("stop-update");
        AtomicInteger stopping = countSystemBundleStopping();

        framework.stop();
        framework.update();
        FrameworkEvent event = framework.waitForStop(20000);
        assertEquals("The framework stopped", FrameworkEvent.STOPPED, event.getType());

        // An update thread that passed the state check would start the framework again
        for (int i = 0; i < 20 && framework.getState() == Bundle.RESOLVED; i++)
            Thread.sleep(50);
        assertEquals("The framework stays stopped", Bundle.RESOLVED, framework.getState());
        assertEquals("The framework is stopped once", 1, stopping.get());
    }

    @Test
    public void testSystemBundleStopDuringUpdate() throws Exception {
        framework = newFramework("update-stop");
        Bundle systemBundle = framework.getBundleContext().getBundle(0);
        AtomicInteger stopping = countSystemBundleStopping();

        framework.update();
        systemBundle.stop();
        FrameworkEvent event = framework.waitForStop(20000);
        assertEquals("The framework stopped for the update", FrameworkEvent.STOPPED_UPDATE, event.getType());
        for (int i = 0; i < 200 && framework.getState() != Bundle.ACTIVE; i++)
            Thread.sleep(50);
        assertEquals("The framework runs again after the update", Bundle.ACTIVE, framework.getState());
        assertEquals("The framework is stopped once", 1, stopping.get());
    }

    private AtomicInteger countSystemBundleStopping() {
        final AtomicInteger stopping = new AtomicInteger();
        framework.getBundleContext().addBundleListener(new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                if (event.getBundle().getBundleId() == 0 && event.getType() == BundleEvent.STOPPING)
                    stopping.incrementAndGet();
            }
        });
        return stopping;
    }

    private static Framework newFramework(String name) throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/shutdown-" + name).getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        Framework framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        return framework;
    }
}
