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
package org.jboss.osgi.framework.internal.log;

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;

import java.util.HashMap;
import java.util.Map;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Posts the log entries to the Event Admin service (OSGi Compendium 101.7.4).
 *
 * The framework does not depend on the Event Admin API: the service is tracked by its class name and the event is
 * created with the classes of the bundle that registered it.
 */
final class EventAdminSupport {

    private static final String EVENT_ADMIN = "org.osgi.service.event.EventAdmin";
    private static final String EVENT = "org.osgi.service.event.Event";
    private static final String TOPIC_PREFIX = "org/osgi/service/log/LogEntry/";

    private final BundleContext context;
    private ServiceTracker<Object, Object> tracker;

    EventAdminSupport(BundleContext context) {
        this.context = context;
    }

    synchronized void open() {
        tracker = new ServiceTracker<Object, Object>(context, EVENT_ADMIN, null);
        tracker.open(true);
    }

    synchronized void close() {
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }
    }

    boolean isAvailable() {
        ServiceTracker<Object, Object> aux = tracker;
        return aux != null && aux.getServiceReference() != null;
    }

    /**
     * Post the event of the log entry to the Event Admin service, if there is one
     */
    void post(LogEntry entry) {
        ServiceTracker<Object, Object> aux = tracker;
        ServiceReference<Object> sref = aux != null ? aux.getServiceReference() : null;
        Object eventAdmin = sref != null ? aux.getService(sref) : null;
        Bundle owner = sref != null ? sref.getBundle() : null;
        if (eventAdmin == null || owner == null)
            return;
        try {
            Class<?> eventType = owner.loadClass(EVENT);
            Class<?> adminType = owner.loadClass(EVENT_ADMIN);
            Object event = eventType.getConstructor(String.class, Map.class).newInstance(getTopic(entry), getProperties(entry));
            adminType.getMethod("postEvent", eventType).invoke(eventAdmin, event);
        } catch (Exception ex) {
            LOGGER.debugf(ex, "Cannot post log entry to Event Admin: %s", entry);
        }
    }

    @SuppressWarnings("deprecation")
    private static String getTopic(LogEntry entry) {
        switch (entry.getLevel()) {
            case LogService.LOG_ERROR:
                return TOPIC_PREFIX + "LOG_ERROR";
            case LogService.LOG_WARNING:
                return TOPIC_PREFIX + "LOG_WARNING";
            case LogService.LOG_INFO:
                return TOPIC_PREFIX + "LOG_INFO";
            case LogService.LOG_DEBUG:
                return TOPIC_PREFIX + "LOG_DEBUG";
            default:
                return TOPIC_PREFIX + "LOG_OTHER";
        }
    }

    private static Map<String, Object> getProperties(LogEntry entry) {
        Map<String, Object> props = new HashMap<String, Object>();
        Bundle bundle = entry.getBundle();
        if (bundle != null) {
            props.put("bundle.id", bundle.getBundleId());
            if (bundle.getSymbolicName() != null)
                props.put("bundle.symbolicName", bundle.getSymbolicName());
            props.put("bundle", bundle);
        }
        props.put("log.level", entry.getLevel());
        props.put("log.loggername", entry.getLoggerName());
        if (entry.getThreadInfo() != null)
            props.put("log.threadinfo", entry.getThreadInfo());
        if (entry.getMessage() != null)
            props.put("message", entry.getMessage());
        props.put("timestamp", entry.getTime());
        props.put("log.entry", entry);
        Throwable exception = entry.getException();
        if (exception != null) {
            props.put("exception.class", exception.getClass().getName());
            if (exception.getMessage() != null)
                props.put("exception.message", exception.getMessage());
            props.put("exception", exception);
        }
        ServiceReference<?> sref = entry.getServiceReference();
        if (sref != null) {
            props.put("service", sref);
            props.put("service.id", sref.getProperty(Constants.SERVICE_ID));
            Object pid = sref.getProperty(Constants.SERVICE_PID);
            if (pid != null)
                props.put("service.pid", pid);
            props.put("service.objectClass", sref.getProperty(Constants.OBJECTCLASS));
        }
        return props;
    }
}
