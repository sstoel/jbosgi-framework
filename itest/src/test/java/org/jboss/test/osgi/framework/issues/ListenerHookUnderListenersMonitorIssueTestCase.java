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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;

/**
 * A ListenerHook is called while the framework holds the monitor of the service listeners: a hook that waits for
 * another thread which adds or removes a service listener waits until it gives up.
 *
 * <h2>Severity</h2>
 * minor. The hook and the other thread stall; with a hook that waits without a timeout they deadlock.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code FrameworkEventsImpl.addServiceListener}, {@code removeServiceListener} and
 * {@code removeServiceListeners}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of static/06 (fw-small, e7149ddf).
 *
 * <h2>Spec reference</h2>
 * OSGi Core R8 4.7.3 Synchronization Pitfalls: the framework must not hold a monitor when it calls out to a hook.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:215-242, 265-291, 296-310 (e7149ddf).
 *
 * <h2>Root cause</h2>
 * The hooks were obtained and called inside {@code synchronized (serviceListeners)}.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ListenerHookUnderListenersMonitorIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The other thread adds its listener while the hook waits for it.
 *
 * <h2>Regression vs WF41</h2>
 * no.
 */
public class ListenerHookUnderListenersMonitorIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testHookAddedWaitsForAnotherListener() throws Exception {
        assertOtherThreadAddsListener(true);
    }

    @Test
    public void testHookRemovedWaitsForAnotherListener() throws Exception {
        assertOtherThreadAddsListener(false);
    }

    private void assertOtherThreadAddsListener(final boolean onAdded) throws Exception {
        final BundleContext context = getSystemContext();
        final String markerFilter = "(issues.listenerhook.monitor=" + (onAdded ? "added" : "removed") + ")";
        final ServiceListener marker = new NoopListener();
        final ServiceListener other = new NoopListener();
        final AtomicReference<String> result = new AtomicReference<String>("hook not called");
        ServiceRegistration<ListenerHook> reg = context.registerService(ListenerHook.class, new ListenerHook() {
            @Override
            public void added(Collection<ListenerInfo> listeners) {
                if (onAdded)
                    waitForOtherListener(listeners);
            }

            @Override
            public void removed(Collection<ListenerInfo> listeners) {
                if (!onAdded)
                    waitForOtherListener(listeners);
            }

            private void waitForOtherListener(Collection<ListenerInfo> listeners) {
                for (ListenerInfo info : listeners) {
                    if (markerFilter.equals(info.getFilter()) && info.getBundleContext() == context) {
                        Thread thread = new Thread("listener-hook-other") {
                            @Override
                            public void run() {
                                try {
                                    context.addServiceListener(other, "(issues.listenerhook.monitor=other)");
                                } catch (Exception ex) {
                                    throw new IllegalStateException(ex);
                                }
                            }
                        };
                        thread.setDaemon(true);
                        thread.start();
                        try {
                            thread.join(5000);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        result.set(thread.isAlive() ? "blocked: " + thread.getState() : "added");
                    }
                }
            }
        }, null);
        try {
            context.addServiceListener(marker, markerFilter);
            if (!onAdded)
                context.removeServiceListener(marker);
            assertEquals("The other thread adds its listener while the hook waits for it", "added", result.get());
        } finally {
            reg.unregister();
            context.removeServiceListener(marker);
            context.removeServiceListener(other);
        }
    }

    static class NoopListener implements ServiceListener {
        @Override
        public void serviceChanged(ServiceEvent event) {
        }
    }
}
