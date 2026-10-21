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
 * The events of the log entries have no {@code log.loglevel} property, and with Apache Felix Event Admin, which posts
 * the log entries itself, every entry is posted twice.
 *
 * <h2>Severity</h2>
 * minor. Event handlers cannot tell AUDIT from TRACE entries, and get two events per entry with Felix Event Admin.
 *
 * <h2>Component</h2>
 * jbosgi-framework, core: {@code internal.log.EventAdminSupport}.
 *
 * <h2>Found by</h2>
 * R8 fix review, residual of services/10 (fw-minor-D02, D03).
 *
 * <h2>Spec reference</h2>
 * OSGi Compendium R8 101.8 Mapping of Log Entries to Events. The topic follows the integer level of the entry, as in
 * Equinox: AUDIT and TRACE entries go to {@code LogEntry/LOG_OTHER}.
 *
 * <h2>Code location</h2>
 * core/src/main/java/org/jboss/osgi/framework/internal/log/EventAdminSupport.java:95, :118 (dc5d2881).
 *
 * <h2>Root cause</h2>
 * The event had only the integer level. The framework posted the entries whatever Event Admin was installed.
 *
 * <h2>Reproduction</h2>
 * <pre>
 * cd jbosgi-framework
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=LogEventLevelAndFelixIssueTestCase
 * </pre>
 *
 * <h2>Expected</h2>
 * The event of an AUDIT entry has {@code log.loglevel=AUDIT}. An Event Admin of the bundle
 * {@code org.apache.felix.eventadmin} gets no events from the framework (its own adapter posts them).
 *
 * <h2>Regression vs WF41</h2>
 * no (new feature of dc5d2881).
 */
public class LogEventLevelAndFelixIssueTestCase extends OSGiFrameworkTest {

    /** An Event Admin that records the topic, message and log level of every event it gets */
    public static class LevelRecordingEventAdmin implements EventAdmin, BundleActivator {
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
            events.add(event.getTopic() + "|" + event.getProperty("message") + "|" + event.getProperty("log.loglevel"));
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
    public void testAuditEntryHasLogLevel() throws Exception {
        Bundle eventAdminBundle = installBundle(getEventAdminArchive("level-recording-eventadmin"));
        try {
            eventAdminBundle.start();
            Object eventAdmin = getSystemContext().getService(eventAdminBundle.getRegisteredServices()[0]);
            logAudit("audit entry with level");
            String expected = "org/osgi/service/log/LogEntry/LOG_OTHER|audit entry with level|AUDIT";
            List<String> events = awaitEvent(eventAdmin, expected);
            assertTrue("The AUDIT entry is posted with log.loglevel=AUDIT, Event Admin received: " + events, events.contains(expected));
        } finally {
            eventAdminBundle.uninstall();
        }
    }

    @Test
    public void testNoEventsForFelixEventAdmin() throws Exception {
        Bundle eventAdminBundle = installBundle(getEventAdminArchive("org.apache.felix.eventadmin"));
        try {
            eventAdminBundle.start();
            Object eventAdmin = getSystemContext().getService(eventAdminBundle.getRegisteredServices()[0]);
            logAudit("entry for felix");
            List<String> events = awaitEvent(eventAdmin, "org/osgi/service/log/LogEntry/LOG_OTHER|entry for felix|AUDIT");
            assertEquals("Felix Event Admin posts the log entries itself, the framework does not: " + events, 0, events.size());
        } finally {
            eventAdminBundle.uninstall();
        }
    }

    private void logAudit(String message) throws Exception {
        BundleContext context = getSystemContext();
        LoggerFactory loggerFactory = context.getService(context.getServiceReference(LoggerFactory.class));
        loggerFactory.getLogger("test.eventadmin.level").audit(message);
    }

    @SuppressWarnings("unchecked")
    private static List<String> awaitEvent(Object eventAdmin, String expected) throws Exception {
        List<String> events = null;
        for (int i = 0; i < 20; i++) {
            events = (List<String>) eventAdmin.getClass().getMethod("getEvents").invoke(eventAdmin);
            if (events.contains(expected))
                break;
            Thread.sleep(100);
        }
        return events;
    }

    private JavaArchive getEventAdminArchive(final String symbolicName) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, symbolicName);
        archive.addPackage(EventAdmin.class.getPackage());
        archive.addClasses(LevelRecordingEventAdmin.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(symbolicName);
                builder.addBundleActivator(LevelRecordingEventAdmin.class);
                builder.addExportPackages("org.osgi.service.event;version=1.4");
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return archive;
    }
}
