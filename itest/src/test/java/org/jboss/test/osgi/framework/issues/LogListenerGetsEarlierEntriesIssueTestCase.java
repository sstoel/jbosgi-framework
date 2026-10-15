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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;

/**
 * A LogListener that is added to the Log Reader Service receives log entries that were created before it was
 * added, when their asynchronous delivery had not happened yet.
 *
 * <h3>Severity</h3>
 * minor. A listener sees messages that were logged before it subscribed (for example messages of a previous
 * user of the Log Reader Service, or of the time before a log handler bundle was started); the order of the
 * messages is kept. Which earlier entries are seen depends on the timing of the delivery thread.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.log.LogServices#log / #deliver
 *
 * <h3>Found by</h3>
 * TCK workstream: OSGi Core R8 TCK org.osgi.test.cases.log 8.0.0,
 * {@code org.osgi.test.cases.log.junit.LogServiceTestCase.testLogServiceLogger} fails on JBoss OSGi (passes on
 * Equinox 3.18.600, JDK 25) with
 * {@code junit.framework.AssertionFailedError: expected:<null> but was:<ServiceState{service.id=9,
 * objectClass=[org.osgi.service.log.LogService, org.osgi.service.log.LoggerFactory], service.scope=bundle,
 * service.bundleid=0}>} at AbstractLogTestCase.assertLog(AbstractLogTestCase.java:367): the first entry that the
 * new LogReader of testLogServiceLogger receives is an entry logged (with the LogService reference) at the end of
 * the previous test, testMultipleListeners, before the new reader was added.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 101.5 Log Reader Service
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html): "addLogListener(LogListener) - This
 * method is used to subscribe to the Log Reader Service in order to receive log messages as they occur. Unlike the
 * previously recorded log entries, all log messages must be sent to subscribers of the Log Reader Service as they
 * are recorded. After a subscription to the Log Reader Service has been started, the subscriber's logged(LogEntry)
 * method must be called with a LogEntry object for the message each time a message is logged."
 * and 101.11.8 LogListener: "After the listener is registered, the logged method will be called for each LogEntry
 * object created."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/LogServices.java:191 ({@code log}) creates the entry and
 * submits {@code deliver(entry)} to the single "OSGi Log Service" thread (line 207); {@code deliver} (line 217-218)
 * iterates the listeners that are registered at delivery time: {@code for (LogListener listener : listeners)}.
 * New in commit 3680cfcc ("Implement OSGi Core R8").
 *
 * <h3>Root cause</h3>
 * The set of listeners that gets an entry is determined when the entry is delivered, not when it is created. An
 * entry that is still queued for the delivery thread when a listener is added is delivered to that listener too.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LogListenerGetsEarlierEntriesIssueTestCase}. The test blocks the delivery thread in a first
 * listener, logs an entry, adds a second listener, then unblocks the delivery thread.
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A listener added after an entry was logged must not receive that entry
 *   expected:&lt;[after-1be1f94c4987d]&gt; but was:&lt;[before-1be1f94c4987d, after-1be1f94c4987d]&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * The second listener only receives the entry that was logged after it was added.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.600 passes the TCK test. The same scenario run directly on the system bundle of each framework
 * (JDK 25, standalone program): Equinox 3.18.600: the late listener gets [after]; JBoss OSGi: [before, after].
 * (Felix 7.0.5 has no built-in Log Service.)
 *
 * <h3>Suggested fix</h3>
 * Take the snapshot of the listeners in {@code log()} (under the same lock that orders the entries) and pass it to
 * the delivery task: {@code final List<LogListener> targets = new ArrayList<>(listeners);
 * deliverer.execute(() -> deliver(entry, targets));}. A listener that is removed before delivery may still be
 * skipped by checking that it is still registered.
 *
 * <h3>Regression vs WF41</h3>
 * yes: the framework Log Service is new in the R8 commit 3680cfcc.
 */
public class LogListenerGetsEarlierEntriesIssueTestCase extends OSGiFrameworkTest {

    static class Recorder implements LogListener {
        private final String marker;
        private final List<String> messages = new ArrayList<String>();
        private final CountDownLatch afterReceived = new CountDownLatch(1);

        Recorder(String marker) {
            this.marker = marker;
        }

        @Override
        public void logged(LogEntry entry) {
            String message = entry.getMessage();
            if (message != null && message.endsWith(marker)) {
                synchronized (messages) {
                    messages.add(message);
                }
                if (message.startsWith("after-"))
                    afterReceived.countDown();
            }
        }

        List<String> get() {
            synchronized (messages) {
                return new ArrayList<String>(messages);
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testListenerDoesNotGetEntriesLoggedBeforeItWasAdded() throws Exception {
        BundleContext context = getSystemContext();
        ServiceReference<LogService> logRef = context.getServiceReference(LogService.class);
        ServiceReference<LogReaderService> readerRef = context.getServiceReference(LogReaderService.class);
        LogService log = context.getService(logRef);
        LogReaderService reader = context.getService(readerRef);

        final String marker = Long.toHexString(System.nanoTime());
        final CountDownLatch blocking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        // Holds the delivery thread while it delivers the "block" entry
        LogListener blocker = new LogListener() {
            @Override
            public void logged(LogEntry entry) {
                if (("block-" + marker).equals(entry.getMessage())) {
                    blocking.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        Recorder late = new Recorder(marker);
        reader.addLogListener(blocker);
        try {
            log.log(LogService.LOG_ERROR, "block-" + marker);
            assertTrue("The delivery thread must call the first listener", blocking.await(10, TimeUnit.SECONDS));

            // Logged while the second listener is not registered yet
            log.log(LogService.LOG_ERROR, "before-" + marker);
            reader.addLogListener(late);
            release.countDown();

            log.log(LogService.LOG_ERROR, "after-" + marker);
            assertTrue("The second listener must receive the entry logged after it was added", late.afterReceived.await(10, TimeUnit.SECONDS));

            List<String> expected = new ArrayList<String>();
            expected.add("after-" + marker);
            assertEquals("A listener added after an entry was logged must not receive that entry", expected, late.get());
        } finally {
            release.countDown();
            reader.removeLogListener(blocker);
            reader.removeLogListener(late);
            context.ungetService(readerRef);
            context.ungetService(logRef);
        }
    }
}
