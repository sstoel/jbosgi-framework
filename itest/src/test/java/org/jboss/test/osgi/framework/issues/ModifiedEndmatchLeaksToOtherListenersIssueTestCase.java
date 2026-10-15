package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2012 JBoss by Red Hat
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

/**
 * A MODIFIED_ENDMATCH service event replaces the MODIFIED event for every listener that is called after the first
 * listener that receives MODIFIED_ENDMATCH, so later filtered listeners get no event at all and later unfiltered
 * listeners get MODIFIED_ENDMATCH instead of MODIFIED.
 *
 * <h3>Severity</h3>
 * major. A {@link ServiceTracker} with a filter (the usual way to track a service with given properties, also
 * used by Declarative Services implementations) is not told that a service stopped matching when another
 * filtered listener happened to be called first, so it keeps using a service that no longer matches its filter.
 * Listeners without a filter see an event type that they are not supposed to receive.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.FrameworkEventsImpl#fireServiceEvent
 *
 * <h3>Found by</h3>
 * services workstream, code review of the service event delivery, then an Equinox / Felix differential with the
 * same scenario (three listeners on the system bundle context).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, org.osgi.framework.ServiceEvent#MODIFIED_ENDMATCH
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.ServiceEvent.MODIFIED_ENDMATCH):
 * "This event is only delivered to listeners which were added with a non-null filter where the filter matched the
 * service properties prior to the modification but the filter does not match the modified service properties."
 * and 5.6.1 Service Event Types: "MODIFIED - The properties of a service have been modified. This event is
 * synchronously delivered after the service properties have been modified."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:598 creates the event once,
 * line 655 overwrites the shared local variable: {@code event = new ServiceEventImpl(ServiceEvent.MODIFIED_ENDMATCH, serviceState);}.
 * Introduced by commit 132c7fca (2013, "Add support for EventListenerHook"), already on WF41.
 *
 * <h3>Root cause</h3>
 * {@code fireServiceEvent} loops over all listeners with one {@code ServiceEvent event} variable. When a filtered
 * listener no longer matches, the code assigns the MODIFIED_ENDMATCH event to that variable. Every listener after it
 * in the loop is then handled with the MODIFIED_ENDMATCH event: a matching (or unfiltered) listener receives
 * MODIFIED_ENDMATCH, and for a non-matching filtered listener the check {@code ServiceEvent.MODIFIED == event.getType()}
 * is now false, so it receives nothing.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=ModifiedEndmatchLeaksToOtherListenersIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testEveryFilteredListenerGetsEndmatch: The second filtered listener must get MODIFIED_ENDMATCH (8) like the first one
 *   expected:&lt;[8]&gt; but was:&lt;[]&gt;
 * testUnfilteredListenerGetsModified: A listener without a filter must get MODIFIED (2), not MODIFIED_ENDMATCH (8)
 *   expected:&lt;[2]&gt; but was:&lt;[8]&gt;
 * testSecondFilteredTrackerDropsService: The second tracker must drop the service that no longer matches its filter
 *   expected null, but was:&lt;...&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * Each listener is evaluated independently: every listener whose filter matched before and not after the
 * modification gets MODIFIED_ENDMATCH, every listener whose filter matches (or that has no filter) gets MODIFIED.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5, same three listeners: both filtered listeners get MODIFIED_ENDMATCH (8), the
 * listener without a filter gets MODIFIED (2). JBoss OSGi: first filtered listener 8, second filtered listener
 * nothing, unfiltered listener 8.
 *
 * <h3>Suggested fix</h3>
 * Do not reassign {@code event}; create the MODIFIED_ENDMATCH event in a local variable of the else branch
 * (or once, lazily, in a separate variable) and pass that to the listener.
 *
 * <h3>Regression vs WF41</h3>
 * no (present since 2013).
 */
public class ModifiedEndmatchLeaksToOtherListenersIssueTestCase extends OSGiFrameworkTest {

    static class EventTypes implements ServiceListener {
        final List<Integer> types = new ArrayList<Integer>();

        @Override
        public synchronized void serviceChanged(ServiceEvent event) {
            types.add(event.getType());
        }

        synchronized List<Integer> get() {
            return new ArrayList<Integer>(types);
        }
    }

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    private static Hashtable<String, Object> props(String value) {
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("endmatch.test", value);
        return props;
    }

    @Test
    public void testEveryFilteredListenerGetsEndmatch() throws Exception {
        BundleContext context = getSystemContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), props("on"));
        EventTypes first = new EventTypes();
        EventTypes second = new EventTypes();
        context.addServiceListener(first, "(endmatch.test=on)");
        context.addServiceListener(second, "(endmatch.test=on)");
        try {
            sreg.setProperties(props("off"));
            assertEquals("The first filtered listener must get MODIFIED_ENDMATCH (8)", list(ServiceEvent.MODIFIED_ENDMATCH), first.get());
            assertEquals("The second filtered listener must get MODIFIED_ENDMATCH (8) like the first one", list(ServiceEvent.MODIFIED_ENDMATCH), second.get());
        } finally {
            context.removeServiceListener(first);
            context.removeServiceListener(second);
            sreg.unregister();
        }
    }

    @Test
    public void testUnfilteredListenerGetsModified() throws Exception {
        BundleContext context = getSystemContext();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, new TestService(), props("on"));
        EventTypes filtered = new EventTypes();
        EventTypes unfiltered = new EventTypes();
        context.addServiceListener(filtered, "(endmatch.test=on)");
        context.addServiceListener(unfiltered);
        try {
            sreg.setProperties(props("off"));
            assertEquals("The filtered listener must get MODIFIED_ENDMATCH (8)", list(ServiceEvent.MODIFIED_ENDMATCH), filtered.get());
            assertEquals("A listener without a filter must get MODIFIED (2), not MODIFIED_ENDMATCH (8)", list(ServiceEvent.MODIFIED), unfiltered.get());
        } finally {
            context.removeServiceListener(filtered);
            context.removeServiceListener(unfiltered);
            sreg.unregister();
        }
    }

    @Test
    public void testSecondFilteredTrackerDropsService() throws Exception {
        BundleContext context = getSystemContext();
        String filter = "(&(objectClass=" + Runnable.class.getName() + ")(endmatch.test=on))";
        ServiceTracker<Runnable, Runnable> first = new ServiceTracker<Runnable, Runnable>(context, context.createFilter(filter), null);
        ServiceTracker<Runnable, Runnable> second = new ServiceTracker<Runnable, Runnable>(context, context.createFilter(filter), null);
        first.open();
        second.open();
        TestService service = new TestService();
        ServiceRegistration<Runnable> sreg = context.registerService(Runnable.class, service, props("on"));
        try {
            assertSame("The first tracker tracks the service", service, first.getService());
            assertSame("The second tracker tracks the service", service, second.getService());

            sreg.setProperties(props("off"));
            assertNull("The first tracker must drop the service that no longer matches its filter", first.getService());
            assertNull("The second tracker must drop the service that no longer matches its filter", second.getService());
        } finally {
            first.close();
            second.close();
            sreg.unregister();
        }
    }

    private static List<Integer> list(int type) {
        List<Integer> result = new ArrayList<Integer>();
        result.add(type);
        return result;
    }
}
