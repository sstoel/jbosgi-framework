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

import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;

/**
 * An unchecked exception thrown by a BundleListener, SynchronousBundleListener or ServiceListener is only logged; the
 * framework does not publish the required FrameworkEvent.ERROR.
 *
 * <h3>Severity</h3>
 * minor. Management agents and diagnostics that rely on ERROR framework events (e.g. the log service's framework
 * event logging, WildFly's OSGi subsystem logging, monitoring tools) never learn that a listener failed.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl} (bundle and service event
 * dispatch).
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix framework.lifecycle#161), exploratory test plus a differential run against
 * Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 4.7.1 Listeners
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html#framework.lifecycle.listeners): "The
 * Framework must publish a FrameworkEvent.ERROR if a callback to an event listener generates an unchecked exception -
 * except when the callback happens while delivering a FrameworkEvent.ERROR (to prevent an infinite loop)."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:397-398} (synchronous bundle
 * listeners), {@code :415-416} (asynchronous bundle listeners) and {@code :659-660} (service listeners) catch the
 * Throwable and only call {@code LOGGER.warnErrorWhileFiring...}; only the framework listener path (lines 509-517)
 * publishes an ERROR event. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The ERROR publication was implemented for framework listeners only.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ListenerExceptionNotPublishedIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testBundleListenerException: An unchecked exception of a BundleListener must be published as FrameworkEvent.ERROR,
 *   but the framework listener got no ERROR event carrying it (events: [])
 * testSynchronousBundleListenerException: ... SynchronousBundleListener ... (events: [])
 * testServiceListenerException: ... ServiceListener ... (events: [])
 * </pre>
 *
 * <h3>Expected</h3>
 * One FrameworkEvent.ERROR per failed callback, carrying the listener's exception.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300 publishes FrameworkEvent.ERROR (type 2) with the RuntimeException for all three listener kinds.
 *
 * <h3>Suggested fix</h3>
 * In the three catch blocks fire {@code fireFrameworkEvent(bundle of the listener, FrameworkEvent.ERROR, th)} (as the
 * framework listener path already does), in addition to logging.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class ListenerExceptionNotPublishedIssueTestCase extends OSGiFrameworkTest {

    private final List<FrameworkEvent> errors = new CopyOnWriteArrayList<FrameworkEvent>();
    private final FrameworkListener errorListener = new FrameworkListener() {
        @Override
        public void frameworkEvent(FrameworkEvent event) {
            if (event.getType() == FrameworkEvent.ERROR)
                errors.add(event);
        }
    };

    @Before
    public void addErrorListener() throws Exception {
        getSystemContext().addFrameworkListener(errorListener);
    }

    @After
    public void removeErrorListener() throws Exception {
        getSystemContext().removeFrameworkListener(errorListener);
    }

    @Test
    public void testBundleListenerException() throws Exception {
        final RuntimeException failure = new RuntimeException("bundle listener failure");
        BundleListener listener = new BundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                throw failure;
            }
        };
        BundleContext context = getSystemContext();
        context.addBundleListener(listener);
        try {
            installAndUninstall();
            assertErrorPublished("BundleListener", failure);
        } finally {
            context.removeBundleListener(listener);
        }
    }

    @Test
    public void testSynchronousBundleListenerException() throws Exception {
        final RuntimeException failure = new RuntimeException("synchronous bundle listener failure");
        BundleListener listener = new SynchronousBundleListener() {
            @Override
            public void bundleChanged(BundleEvent event) {
                throw failure;
            }
        };
        BundleContext context = getSystemContext();
        context.addBundleListener(listener);
        try {
            installAndUninstall();
            assertErrorPublished("SynchronousBundleListener", failure);
        } finally {
            context.removeBundleListener(listener);
        }
    }

    @Test
    public void testServiceListenerException() throws Exception {
        final RuntimeException failure = new RuntimeException("service listener failure");
        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
                throw failure;
            }
        };
        BundleContext context = getSystemContext();
        context.addServiceListener(listener);
        try {
            ServiceRegistration<Runnable> registration = context.registerService(Runnable.class, new Runnable() {
                @Override
                public void run() {
                }
            }, null);
            registration.unregister();
            assertErrorPublished("ServiceListener", failure);
        } finally {
            context.removeServiceListener(listener);
        }
    }

    private void installAndUninstall() throws Exception {
        JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "listener-exception-bundle");
        archive.addAsManifestResource(new org.jboss.shrinkwrap.api.asset.StringAsset(
                "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: listener-exception-bundle\n"), "MANIFEST.MF");
        Bundle bundle = installBundle(archive);
        bundle.uninstall();
    }

    private void assertErrorPublished(String kind, Throwable failure) throws InterruptedException {
        for (int i = 0; i < 50 && hasError(failure) == false; i++)
            Thread.sleep(100);
        assertTrue("An unchecked exception of a " + kind + " must be published as FrameworkEvent.ERROR, but the framework listener got no ERROR event carrying it (events: " + errors + ")",
                hasError(failure));
    }

    private boolean hasError(Throwable failure) {
        for (FrameworkEvent event : errors) {
            Throwable th = event.getThrowable();
            while (th != null && th != failure)
                th = th.getCause();
            if (th == failure)
                return true;
        }
        return false;
    }
}
