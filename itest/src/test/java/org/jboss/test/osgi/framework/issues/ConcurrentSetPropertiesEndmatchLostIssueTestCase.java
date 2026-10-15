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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Two ServiceRegistration.setProperties calls on different threads lose the MODIFIED_ENDMATCH event of the first
 * modification: the framework keeps the "previous properties" in one shared, unsynchronized field of the service and
 * reads it while it delivers the event, so a second modification during the delivery of the first one replaces the
 * previous properties of the first event.
 *
 * <h3>Severity</h3>
 * major. A listener whose filter matched the properties before the first modification and does not match afterwards
 * gets neither MODIFIED_ENDMATCH nor any other event: a ServiceTracker (or DS, which tracks with filters such as
 * {@code target} properties) keeps tracking and using a service that no longer matches its filter, until the service
 * is unregistered. Concurrent setProperties calls on one registration are normal (ServiceRegistration is specified as
 * thread-safe; e.g. configuration updates from Configuration Admin on one thread and status properties from a worker
 * thread), and the window is the whole synchronous delivery of the first event to all listeners.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceStateImpl} ({@code setProperties},
 * {@code getPreviousProperties}, fields {@code prevProperties}/{@code currProperties}) and
 * {@code org.jboss.osgi.framework.internal.FrameworkEventsImpl.fireServiceEvent}.
 *
 * <h3>Found by</h3>
 * concurrency workstream (data race review: fields read without the lock that writes them; prevProperties and
 * currProperties are written by setProperties without any lock and read during the event delivery); made deterministic
 * with a latch in a ServiceListener; Equinox/Felix differential
 * ({@code explore/concurrency/diff/Diff.java c04}).
 *
 * <h3>Spec reference</h3>
 * ServiceEvent.MODIFIED_ENDMATCH javadoc (OSGi Core R8, 10.1.23.2): "The properties of a registered service have been
 * modified and the new properties no longer match the listener's filter. This event is synchronously delivered after
 * the service properties have been modified. This event is only delivered to listeners which were added with a
 * non-null filter where the filter matched the service properties prior to the modification but the filter does not
 * match the modified service properties."; ServiceRegistration (10.1.30): "Concurrency Thread-safe"; 5.2.12 Services
 * and Concurrency / 4.7.3: events are delivered synchronously for each modification.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:409-427} ({@code setProperties}:
 * {@code prevProperties = currProperties; ... currProperties = new CaseInsensitiveDictionary(properties);
 * serviceManager.fireServiceEvent(ownerBundle, ServiceEvent.MODIFIED, this);} - no lock, plain fields) and
 * {@code :431-433} ({@code getPreviousProperties} returns the shared field);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkEventsImpl.java:645-655}
 * ({@code listenerReg.filter.match(serviceState)} and {@code listenerReg.filter.match(serviceState.getPreviousProperties())}
 * evaluated per listener while the delivery runs). Since commit 7dfb6fdc (2012); pre-existing on WF41.
 *
 * <h3>Root cause</h3>
 * The properties before and after a modification are not captured for the event: every listener check reads the
 * current values of two shared fields, which a concurrent setProperties overwrites (and which are not even safely
 * published between threads).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ConcurrentSetPropertiesEndmatchLostIssueTestCase
 * </pre>
 * A service with {@code b=1}; listener L1 {@code (a=*)} is added first and blocks in its first MODIFIED callback
 * until the test releases it; listener L2 {@code (b=1)} records its events. Thread A sets {@code b=2} (L2 must get
 * MODIFIED_ENDMATCH); while A's delivery is held in L1, thread B sets {@code b=3}; then L1 is released.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: listener (b=1) matched b=1 before thread A changed b to 2 and must get exactly one
 * MODIFIED_ENDMATCH, but got these events: [] expected:&lt;[8 (MODIFIED_ENDMATCH) from A]&gt; but was:&lt;[]&gt;
 * </pre>
 * 3 of 3 runs failed (JDK 25); the interleaving is forced, the test does not depend on timing.
 *
 * <h3>Expected</h3>
 * L2 receives one MODIFIED_ENDMATCH (for the change b=1 -&gt; b=2), delivered by thread A.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5 (same scenario, JDK 25, {@code Diff c04}): L2 receives MODIFIED_ENDMATCH from thread
 * A - both pass the previous properties of each modification with the event (Equinox ModifiedServiceEvent, Felix
 * EventDispatcher.fireServiceEvent(event, oldProps)).
 *
 * <h3>Suggested fix</h3>
 * Capture the old and new properties in setProperties (under a lock, e.g. the service state monitor, without
 * delivering under it) and pass them to fireServiceEvent, which should match the filters against these snapshots
 * instead of the live fields; make currProperties volatile (or read it under the lock).
 * Verified with a trial patch (issues/concurrency/03-.../fix.diff: the previous properties of the modification are
 * handed to the delivery): the test passes, ServiceListenerTestCase still passes.
 *
 * <h3>Regression vs WF41</h3>
 * no (pre-existing).
 *
 * <h3>Related</h3>
 * services/01 ModifiedEndmatchLeaksToOtherListenersIssueTestCase (single-threaded MODIFIED_ENDMATCH defect in the same
 * method; its fix does not remove this race).
 */
public class ConcurrentSetPropertiesEndmatchLostIssueTestCase extends OSGiTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testEndmatchOfFirstModificationWithConcurrentSetProperties() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/concurrency03-setproperties").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();
        BundleContext context = framework.getBundleContext();

        final ServiceRegistration<Runnable> reg = context.registerService(Runnable.class, new Runnable() {
            public void run() {
            }
        }, properties("1"));

        // L1 (added first, called first): holds the delivery of the first MODIFIED event until released
        final CountDownLatch inFirstDelivery = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        context.addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                if (event.getType() == ServiceEvent.MODIFIED && first.getAndSet(false)) {
                    inFirstDelivery.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "(a=*)");

        // L2: matches only b=1
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        context.addServiceListener(new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
                events.add(event.getType() + (event.getType() == ServiceEvent.MODIFIED_ENDMATCH ? " (MODIFIED_ENDMATCH)" : "") + " from "
                        + Thread.currentThread().getName());
            }
        }, "(b=1)");

        Thread threadA = new Thread(new Runnable() {
            public void run() {
                reg.setProperties(properties("2"));
            }
        }, "A");
        threadA.start();
        assertTrue("thread A must deliver its MODIFIED event to L1", inFirstDelivery.await(10, TimeUnit.SECONDS));

        Thread threadB = new Thread(new Runnable() {
            public void run() {
                reg.setProperties(properties("3"));
            }
        }, "B");
        threadB.start();
        threadB.join(10000);
        release.countDown();
        threadA.join(10000);

        List<String> expected = Collections.singletonList(ServiceEvent.MODIFIED_ENDMATCH + " (MODIFIED_ENDMATCH) from A");
        assertEquals("listener (b=1) matched b=1 before thread A changed b to 2 and must get exactly one MODIFIED_ENDMATCH, but got these events: "
                + events, expected, new ArrayList<String>(events));
    }

    private static Dictionary<String, Object> properties(String b) {
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put("a", "1");
        props.put("b", b);
        return props;
    }
}
