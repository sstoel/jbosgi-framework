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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;

/**
 * BundleContext.addServiceListener with an invalid filter for a listener that is already registered throws
 * InvalidSyntaxException but has already removed the existing registration: the listener silently stops receiving
 * events and ListenerHooks are told it was removed.
 *
 * <h3>Severity</h3>
 * minor - a failed call has a destructive side effect: code that tries to narrow the filter of a working listener
 * (e.g. with a filter built from configuration) and handles the InvalidSyntaxException keeps running without its
 * listener, so it misses every later service event.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl#addServiceListener}.
 *
 * <h3>Found by</h3>
 * fuzz-api workstream (fuzz round 2, target 4): service listener edge-case probe {@code fa.ProbeListener}, derived
 * from the stateful service-registry differential {@code fa.ServiceModel} (re-add of a listener with a new filter),
 * run against Felix 7.0.5 and Equinox 3.21: "events after invalid re-add" jbosgi [] vs Felix/Equinox [REGISTERED].
 *
 * <h3>Spec reference</h3>
 * BundleContext.addServiceListener(ServiceListener, String) javadoc (OSGi Core R8, 10.1.6.3,
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html): "If the context bundle's list of
 * listeners already contains a listener l such that (l==listener), then this method replaces that listener's filter
 * (which may be null) with the specified one (which may be null)." and "Throws InvalidSyntaxException - If filter
 * contains an invalid filter string that cannot be parsed." A call that is rejected because its argument cannot be
 * parsed replaces nothing; the existing filter stays in effect. ListenerHook.removed(Collection) javadoc (OSGi Core
 * R8, 55.9.5.2): "Removed listeners hook method. This method is called to provide the hook implementation with
 * information on newly removed service listeners." and @param listeners: "A collection of ListenerInfos for newly
 * removed service listeners which are no longer listening to service events." - called here for a listener that was
 * never removed by its bundle and is still listening.
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:222}
 * ({@code removeServiceListener(bundleState, listener);}) runs before {@code :225}
 * ({@code FrameworkUtil.createFilter(filterstr)}), which throws for the invalid filter. Since 7dfb6fdc (2012),
 * unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The old registration is removed (and the ListenerHooks notified) before the new filter has been parsed.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=AddServiceListenerInvalidFilterIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testListenerKeptAfterInvalidFilter: The listener added with (invalid.filter.test=1) must still receive the
 *   REGISTERED event after a failed addServiceListener with an invalid filter expected:&lt;[1]&gt; but was:&lt;[]&gt;
 * testListenerHookNotToldOfRemoval: A failed addServiceListener must not report the existing listener as removed to
 *   the ListenerHooks expected:&lt;[]&gt; but was:&lt;[(invalid.filter.test=1)]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * InvalidSyntaxException is thrown and nothing changes: the listener keeps its old filter and receives the event;
 * no ListenerHook.removed call.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21 and Felix 7.0.5 both keep the listener (it receives the REGISTERED event) and do not call
 * ListenerHook.removed.
 *
 * <h3>Suggested fix</h3>
 * In addServiceListener create the Filter first (let InvalidSyntaxException propagate), then remove the old
 * registration and add the new one.
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class AddServiceListenerInvalidFilterIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testListenerKeptAfterInvalidFilter() throws Exception {
        BundleContext context = getFramework().getBundleContext();
        final List<Integer> events = new ArrayList<Integer>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
                events.add(event.getType());
            }
        };
        context.addServiceListener(listener, "(invalid.filter.test=1)");
        ServiceRegistration<Runnable> reg = null;
        try {
            try {
                context.addServiceListener(listener, "(invalid.filter.test=1");
                fail("InvalidSyntaxException expected");
            } catch (InvalidSyntaxException ex) {
                // expected
            }
            Hashtable<String, Object> props = new Hashtable<String, Object>();
            props.put("invalid.filter.test", 1);
            reg = context.registerService(Runnable.class, new NoopRunnable(), props);
            List<Integer> expected = new ArrayList<Integer>();
            expected.add(ServiceEvent.REGISTERED);
            assertEquals("The listener added with (invalid.filter.test=1) must still receive the REGISTERED event after a failed addServiceListener with an invalid filter",
                    expected, events);
        } finally {
            if (reg != null)
                reg.unregister();
            context.removeServiceListener(listener);
        }
    }

    @Test
    public void testListenerHookNotToldOfRemoval() throws Exception {
        BundleContext context = getFramework().getBundleContext();
        final List<String> removed = new ArrayList<String>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
            }
        };
        context.addServiceListener(listener, "(invalid.filter.test=1)");
        ServiceRegistration<ListenerHook> hookReg = context.registerService(ListenerHook.class, new ListenerHook() {
            @Override
            public void added(Collection<ListenerInfo> listeners) {
            }

            @Override
            public void removed(Collection<ListenerInfo> listeners) {
                for (ListenerInfo info : listeners)
                    if (info.getFilter() != null && info.getFilter().contains("invalid.filter.test"))
                        removed.add(info.getFilter());
            }
        }, null);
        try {
            try {
                context.addServiceListener(listener, "(invalid.filter.test=1");
                fail("InvalidSyntaxException expected");
            } catch (InvalidSyntaxException ex) {
                // expected
            }
            assertEquals("A failed addServiceListener must not report the existing listener as removed to the ListenerHooks",
                    new ArrayList<String>(), removed);
        } finally {
            hookReg.unregister();
            context.removeServiceListener(listener);
        }
    }

    static class NoopRunnable implements Runnable {
        @Override
        public void run() {
        }
    }
}
