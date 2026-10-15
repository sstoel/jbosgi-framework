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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.log.LoggerFactory;

/**
 * The framework's Log Service does not map log entries to Event Admin events: with an Event Admin service present,
 * no event with a topic org/osgi/service/log/LogEntry/LOG_* is posted for a logged entry.
 *
 * <h3>Severity</h3>
 * minor. Event-based log consumers (EventHandlers on org/osgi/service/log/LogEntry/*) see nothing unless the Event
 * Admin implementation happens to adapt the LogReaderService itself. Apache Felix EventAdmin does (its
 * LogEventAdapter), which hides the gap when Felix EventAdmin is deployed; any other Event Admin implementation
 * receives no log events.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.log.LogServices
 *
 * <h3>Found by</h3>
 * services workstream, review of the Log Service 1.5 implementation against 101.7 / 101.7.4 (there is no Event Admin
 * code in internal/log); Equinox comparison.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 101.7 Mapping of Events (https://docs.osgi.org/specification/osgi.core/8.0.0/service.log.html):
 * "Additionally, if the Event Admin service is present, implementations of a Log Service must map LogEntry objects
 * to events in Event Admin." 101.7.4 Log Events: "Log entries must be mapped into events by the Log Service
 * implementation and delivered asynchronously to the Event Admin service (if present). The following event topics
 * are used based upon the log level of the log entry: org/osgi/service/log/LogEntry/LOG_AUDIT ... LOG_ERROR ...
 * LOG_WARNING ... LOG_INFO ... LOG_DEBUG ... LOG_OTHER (TRACE)". Properties: bundle.id, bundle.symbolicName, bundle,
 * log.level, log.loggername, log.threadinfo, log.loglevel, message, timestamp, log.entry, exception.*, service.*.
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/LogServices.java:191-225 ({@code log} stores the entry
 * and hands it to the LogListeners only). New in commit 3680cfcc ("Implement OSGi Core R8").
 *
 * <h3>Root cause</h3>
 * The Event Admin mapping of 101.7.4 is not implemented.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=LogEntriesNotPostedToEventAdminIssueTestCase}
 * The test installs a minimal Event Admin bundle (the org.osgi.service.event API from the osgi.enterprise test
 * dependency plus a recording EventAdmin) and logs an ERROR entry.
 *
 * <h3>Observed</h3>
 * <pre>
 * testLogEntryPostedToEventAdmin: An ERROR log entry must be posted to Event Admin with the topic
 *   org/osgi/service/log/LogEntry/LOG_ERROR, but Event Admin received: []
 * </pre>
 *
 * <h3>Expected</h3>
 * An event with topic org/osgi/service/log/LogEntry/LOG_ERROR whose "message" property is the logged message and
 * whose "log.loggername" property is the logger name is posted (asynchronously) to the Event Admin service.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 implements the mapping in its log implementation (org.eclipse.osgi.internal.log.EventAdminAdapter /
 * EventAdminLogListener). With Apache Felix EventAdmin 1.6.4 installed, JBoss OSGi appears to work only because Felix
 * EventAdmin adapts the LogReaderService itself (verified: the event arrives on JBoss OSGi with Felix EventAdmin).
 *
 * <h3>Suggested fix</h3>
 * Track "org.osgi.service.event.EventAdmin" in LogServices (reflectively, like ConfigurationSupport does for
 * Configuration Admin) and post an Event with the topic and properties of 101.7.4 for every entry, from the log
 * delivery thread.
 *
 * <h3>Regression vs WF41</h3>
 * no (the Log Service is new in this branch).
 */
public class LogEntriesNotPostedToEventAdminIssueTestCase extends OSGiFrameworkTest {

    /**
     * An Event Admin that records the topic, message and logger name of every event it gets.
     */
    public static class RecordingEventAdmin implements EventAdmin, BundleActivator {
        private final List<String> events = new ArrayList<String>();

        @Override
        public void postEvent(Event event) {
            record(event);
        }

        @Override
        public void sendEvent(Event event) {
            record(event);
        }

        private synchronized void record(Event event) {
            events.add(event.getTopic() + "|" + event.getProperty("message") + "|" + event.getProperty("log.loggername"));
        }

        public synchronized List<String> getEvents() {
            return new ArrayList<String>(events);
        }

        @Override
        public void start(BundleContext context) {
            context.registerService(EventAdmin.class, this, null);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLogEntryPostedToEventAdmin() throws Exception {
        Bundle eventAdminBundle = installBundle(getEventAdminArchive());
        try {
            eventAdminBundle.start();
            BundleContext context = getSystemContext();
            ServiceReference<?> eventAdminRef = eventAdminBundle.getRegisteredServices()[0];
            Object eventAdmin = context.getService(eventAdminRef);
            assertNotNull("The Event Admin service is available", eventAdmin);

            LoggerFactory loggerFactory = context.getService(context.getServiceReference(LoggerFactory.class));
            loggerFactory.getLogger("test.eventadmin").error("posted to event admin");

            String expected = "org/osgi/service/log/LogEntry/LOG_ERROR|posted to event admin|test.eventadmin";
            List<String> events = null;
            for (int i = 0; i < 50; i++) {
                events = (List<String>) eventAdmin.getClass().getMethod("getEvents").invoke(eventAdmin);
                if (events.contains(expected))
                    break;
                Thread.sleep(100);
            }
            assertTrue("An ERROR log entry must be posted to Event Admin with the topic org/osgi/service/log/LogEntry/LOG_ERROR, but Event Admin received: "
                    + events, events.contains(expected));
        } finally {
            eventAdminBundle.uninstall();
        }
    }

    private JavaArchive getEventAdminArchive() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "recording-eventadmin");
        archive.addPackage(EventAdmin.class.getPackage());
        archive.addClasses(RecordingEventAdmin.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(RecordingEventAdmin.class);
                builder.addExportPackages("org.osgi.service.event;version=1.4");
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive;
    }
}
