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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.connect.ConnectModule;
import org.osgi.framework.connect.ModuleConnector;
import org.osgi.framework.launch.Framework;

/**
 * An exception from the start method of the module connector's activator makes {@code Framework.init()} fail
 * instead of being broadcast as an ERROR event, and an exception from its stop method is broadcast without being
 * wrapped in a {@link BundleException}.
 *
 * <h3>Severity</h3>
 * minor. A connector activator that fails to start makes the whole framework unlaunchable (init() throws and the
 * framework is left in INSTALLED state), where the spec requires the framework to report the error and carry on.
 * The unwrapped stop exception is a cosmetic deviation (listeners that expect a BundleException get the raw one).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.startConnectActivator} and
 * {@code stopConnectActivator}, called from {@code FrameworkProxy.init} and {@code BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * connect workstream, review against the spec text plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 60.2.3.1 Start (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.connect.html):
 * "Any exception thrown by a module connector activator start method must be wrapped in a BundleException and
 * broadcast as an ERROR."<br>
 * 60.2.3.2 Shutdown: "Any exception thrown by a module connector activator stop method must be wrapped in a
 * BundleException and broadcast as an ERROR. ... A module connector activator that threw an exception during start
 * must not be called on shutdown."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java:891-905}
 * ({@code startConnectActivator} rethrows as {@code MESSAGES.cannotStartConnectActivator(ex)}, line 903, which
 * {@code FrameworkProxy.init} at line 178 propagates and turns into state INSTALLED);
 * {@code BundleManagerPlugin.java:914} ({@code stopConnectActivator} fires {@code FrameworkEvent.ERROR} with the raw
 * throwable). Introduced by 3680cfcc "Implement OSGi Core R8".
 *
 * <h3>Root cause</h3>
 * {@code startConnectActivator} throws instead of firing a framework ERROR event; {@code stopConnectActivator} fires
 * the event but passes the original throwable instead of a BundleException that wraps it.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConnectActivatorExceptionIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testStartExceptionIsBroadcast: The exception of the connector activator start method must be broadcast as an ERROR
 *   event, but init() threw org.osgi.framework.BundleException: JBOSGI011295: Cannot start the activator of the
 *   module connector
 * testStopExceptionIsWrapped: The ERROR event for the exception of the connector activator stop method must carry a
 *   BundleException that wraps it, but it carried java.lang.Exception: stop failure
 * </pre>
 *
 * <h3>Expected</h3>
 * init() succeeds (state STARTING), an ERROR FrameworkEvent whose throwable is a BundleException caused by the start
 * failure is delivered, and the failed activator's stop is not called; the stop failure is delivered as an ERROR
 * event with a BundleException that wraps it.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300: init() succeeds and fires an ERROR event for the start failure (Equinox passes the raw exception
 * there); the stop failure is fired as ERROR with a BundleException wrapping it ("Exception in ...stop() of bundle").
 *
 * <h3>Suggested fix</h3>
 * In {@code startConnectActivator} catch the exception, fire
 * {@code FrameworkEvent.ERROR} with {@code MESSAGES.cannotStartConnectActivator(ex)} and return without remembering
 * the activator; in {@code stopConnectActivator} wrap the throwable in a BundleException before firing the event.
 *
 * <h3>Regression vs WF41</h3>
 * yes (Connect is new in this branch).
 */
public class ConnectActivatorExceptionIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testStartExceptionIsBroadcast() throws Exception {
        final Exception failure = new Exception("start failure");
        final List<String> calls = new CopyOnWriteArrayList<String>();
        newFramework(new BundleActivator() {
            @Override
            public void start(BundleContext context) throws Exception {
                calls.add("start");
                throw failure;
            }

            @Override
            public void stop(BundleContext context) {
                calls.add("stop");
            }
        });

        final List<FrameworkEvent> events = new CopyOnWriteArrayList<FrameworkEvent>();
        try {
            framework.init(new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    events.add(event);
                }
            });
        } catch (BundleException ex) {
            fail("The exception of the connector activator start method must be broadcast as an ERROR event, but init() threw " + ex);
        }
        assertEquals("The framework must be initialized (STARTING) although the connector activator failed, but its state is " + framework.getState(),
                Bundle.STARTING, framework.getState());

        FrameworkEvent error = null;
        for (FrameworkEvent event : events) {
            if (event.getType() == FrameworkEvent.ERROR)
                error = event;
        }
        assertTrue("An ERROR event must be broadcast for the connector activator start failure, but the events were " + events, error != null);
        assertTrue("The ERROR event must carry a BundleException that wraps the start failure, but it carried " + error.getThrowable(),
                error.getThrowable() instanceof BundleException && error.getThrowable().getCause() == failure);

        framework.stop();
        framework.waitForStop(10000);
        assertEquals("The stop method of an activator whose start failed must not be called", 1, calls.size());
    }

    @Test
    public void testStopExceptionIsWrapped() throws Exception {
        final Exception failure = new Exception("stop failure");
        newFramework(new BundleActivator() {
            @Override
            public void start(BundleContext context) {
            }

            @Override
            public void stop(BundleContext context) throws Exception {
                throw failure;
            }
        });
        framework.start();

        final List<FrameworkEvent> events = new CopyOnWriteArrayList<FrameworkEvent>();
        framework.getBundleContext().addFrameworkListener(new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR)
                    events.add(event);
            }
        });
        framework.stop();
        framework.waitForStop(10000);

        assertEquals("One ERROR event must be broadcast for the connector activator stop failure, but the events were " + events, 1, events.size());
        Throwable throwable = events.get(0).getThrowable();
        assertTrue("The ERROR event for the exception of the connector activator stop method must carry a BundleException that wraps it, but it carried " + throwable,
                throwable instanceof BundleException && throwable.getCause() == failure);
    }

    private void newFramework(final BundleActivator activator) {
        ModuleConnector connector = new ModuleConnector() {
            @Override
            public void initialize(File storage, Map<String, String> configuration) {
            }

            @Override
            public Optional<ConnectModule> connect(String location) {
                return Optional.empty();
            }

            @Override
            public Optional<BundleActivator> newBundleActivator() {
                return Optional.of(activator);
            }
        };
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(ConnectFrameworkFactory.class).newFramework(props, connector);
    }
}
