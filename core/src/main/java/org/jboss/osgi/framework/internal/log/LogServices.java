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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.osgi.framework.AllServiceListener;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;
import org.osgi.service.log.LoggerFactory;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

/**
 * The Log Service of the framework.
 *
 * The system bundle registers the {@link LogService}, which is also the {@link LoggerFactory}, the
 * {@link LogReaderService} and the {@link LoggerAdmin}. The bundle, service and framework events are logged.
 * Log entries are delivered to the log listeners in the order they were logged, by a thread of the log service.
 */
public final class LogServices {

    private static final int LOG_SIZE = 100;
    private static final String PACKAGE_PREFIX = LogServices.class.getPackage().getName() + ".";

    private final BundleContext context;
    private final LoggerAdminImpl loggerAdmin;
    private final List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();
    private final List<LogListener> listeners = new CopyOnWriteArrayList<LogListener>();
    private final Set<LogServiceImpl> logServices = ConcurrentHashMap.newKeySet();
    private final Deque<LogEntry> entries = new ArrayDeque<LogEntry>();
    private final AtomicLong sequence = new AtomicLong();
    private final EventLogger eventLogger = new EventLogger();
    private final ConfigurationSupport configurationSupport;
    private ExecutorService executor;

    public LogServices(BundleContext context) {
        this.context = context;
        this.loggerAdmin = new LoggerAdminImpl(context.getProperty(LoggerContext.LOGGER_CONTEXT_DEFAULT_LOGLEVEL));
        this.configurationSupport = new ConfigurationSupport(context, loggerAdmin);
    }

    /**
     * Register the services and start logging the events of the framework.
     */
    public synchronized void start() {
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "OSGi Log Service");
                thread.setDaemon(true);
                return thread;
            }
        });

        String[] logServiceNames = new String[] { LogService.class.getName(), LoggerFactory.class.getName() };
        ServiceRegistration<?> logService = context.registerService(logServiceNames, new ServiceFactory<LogService>() {
            @Override
            public LogService getService(Bundle bundle, ServiceRegistration<LogService> registration) {
                LogServiceImpl service = new LogServiceImpl(LogServices.this, bundle);
                logServices.add(service);
                return service;
            }

            @Override
            public void ungetService(Bundle bundle, ServiceRegistration<LogService> registration, LogService service) {
                logServices.remove(service);
            }
        }, null);
        registrations.add(logService);

        registrations.add(context.registerService(LogReaderService.class, new ServiceFactory<LogReaderService>() {
            @Override
            public LogReaderService getService(Bundle bundle, ServiceRegistration<LogReaderService> registration) {
                return new LogReaderServiceImpl(LogServices.this);
            }

            @Override
            public void ungetService(Bundle bundle, ServiceRegistration<LogReaderService> registration, LogReaderService service) {
                ((LogReaderServiceImpl) service).close();
            }
        }, null));

        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put(LoggerAdmin.LOG_SERVICE_ID, logService.getReference().getProperty(Constants.SERVICE_ID));
        registrations.add(context.registerService(LoggerAdmin.class, loggerAdmin, props));

        context.addBundleListener(eventLogger);
        context.addServiceListener(eventLogger);
        context.addFrameworkListener(eventLogger);
        configurationSupport.open();
    }

    /**
     * Unregister the services and stop logging.
     */
    public synchronized void stop() {
        configurationSupport.close();
        try {
            context.removeBundleListener(eventLogger);
            context.removeServiceListener(eventLogger);
            context.removeFrameworkListener(eventLogger);
        } catch (IllegalStateException ex) {
            // the system bundle context is no longer valid
        }
        for (ServiceRegistration<?> registration : registrations) {
            try {
                registration.unregister();
            } catch (IllegalStateException ex) {
                // already unregistered
            }
        }
        registrations.clear();
        listeners.clear();
        logServices.clear();
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    boolean isEnabled(Bundle bundle, String loggerName, LogLevel level) {
        return loggerAdmin.getContext(bundle).getEffectiveLogLevel(loggerName).implies(level);
    }

    void addLogListener(LogListener listener) {
        listeners.add(listener);
    }

    void removeLogListener(LogListener listener) {
        listeners.remove(listener);
    }

    /** The recent log entries, the most recent entry first */
    Enumeration<LogEntry> getLog() {
        synchronized (entries) {
            return Collections.enumeration(new ArrayList<LogEntry>(entries));
        }
    }

    /**
     * Add an entry to the log. The caller has checked that the level is enabled.
     */
    void log(String loggerName, Bundle bundle, ServiceReference<?> reference, LogLevel logLevel, int level, String message, Throwable exception) {
        final LogEntry entry;
        ExecutorService deliverer;
        synchronized (entries) {
            entry = new LogEntryImpl(loggerName, bundle, reference, logLevel, level, message, exception, sequence.incrementAndGet(), getLocation());
            entries.addFirst(entry);
            if (entries.size() > LOG_SIZE)
                entries.removeLast();

            // Submitted in the order of the sequence
            deliverer = executor;
            if (deliverer != null && listeners.isEmpty() == false) {
                try {
                    deliverer.execute(new Runnable() {
                        @Override
                        public void run() {
                            deliver(entry);
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    // the log service is stopped
                }
            }
        }
    }

    private void deliver(LogEntry entry) {
        for (LogListener listener : listeners) {
            try {
                listener.logged(entry);
            } catch (Throwable th) {
                // a listener must not disturb the others
            }
        }
    }

    /** The location of the first caller that is not part of the log service */
    private static StackTraceElement getLocation() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement element : stack) {
            if (element.getClassName().startsWith(PACKAGE_PREFIX) == false)
                return element;
        }
        return stack.length > 0 ? stack[stack.length - 1] : null;
    }

    /** Logs the events of the framework */
    private final class EventLogger implements SynchronousBundleListener, AllServiceListener, FrameworkListener {

        @Override
        public void bundleChanged(BundleEvent event) {
            Bundle bundle = event.getBundle();
            // A logger that another bundle got for this bundle must not keep it
            if (event.getType() == BundleEvent.UNINSTALLED) {
                for (LogServiceImpl service : logServices)
                    service.removeLoggers(bundle);
            }
            String loggerName = getLoggerName("Events.Bundle", bundle);
            if (isEnabled(bundle, loggerName, LogLevel.INFO))
                log(loggerName, bundle, null, LogLevel.INFO, LogLevel.INFO.ordinal(), "BundleEvent " + getBundleEventType(event.getType()), null);
        }

        @Override
        public void serviceChanged(ServiceEvent event) {
            ServiceReference<?> reference = event.getServiceReference();
            Bundle bundle = reference.getBundle();
            String loggerName = getLoggerName("Events.Service", bundle);
            if (bundle == null)
                bundle = context.getBundle();
            LogLevel level = event.getType() == ServiceEvent.MODIFIED ? LogLevel.DEBUG : LogLevel.INFO;
            if (isEnabled(bundle, loggerName, level))
                log(loggerName, bundle, reference, level, level.ordinal(), "ServiceEvent " + getServiceEventType(event.getType()), null);
        }

        @Override
        public void frameworkEvent(FrameworkEvent event) {
            Bundle bundle = event.getBundle();
            String loggerName = getLoggerName("Events.Framework", bundle);
            if (bundle == null)
                bundle = context.getBundle();
            LogLevel level = LogLevel.INFO;
            if (event.getType() == FrameworkEvent.ERROR)
                level = LogLevel.ERROR;
            else if (event.getType() == FrameworkEvent.WARNING)
                level = LogLevel.WARN;
            if (isEnabled(bundle, loggerName, level))
                log(loggerName, bundle, null, level, level.ordinal(), "FrameworkEvent " + getFrameworkEventType(event.getType()), event.getType() == FrameworkEvent.ERROR ? event.getThrowable() : null);
        }

        /** The logger name ends with the symbolic name of the bundle of the event, when there is one */
        private String getLoggerName(String prefix, Bundle bundle) {
            String symbolicName = bundle != null ? bundle.getSymbolicName() : null;
            return symbolicName != null ? prefix + "." + symbolicName : prefix;
        }

        private String getBundleEventType(int type) {
            switch (type) {
                case BundleEvent.INSTALLED:
                    return "INSTALLED";
                case BundleEvent.RESOLVED:
                    return "RESOLVED";
                case BundleEvent.LAZY_ACTIVATION:
                    return "LAZY_ACTIVATION";
                case BundleEvent.STARTING:
                    return "STARTING";
                case BundleEvent.STARTED:
                    return "STARTED";
                case BundleEvent.STOPPING:
                    return "STOPPING";
                case BundleEvent.STOPPED:
                    return "STOPPED";
                case BundleEvent.UPDATED:
                    return "UPDATED";
                case BundleEvent.UNRESOLVED:
                    return "UNRESOLVED";
                case BundleEvent.UNINSTALLED:
                    return "UNINSTALLED";
                default:
                    return String.valueOf(type);
            }
        }

        private String getServiceEventType(int type) {
            switch (type) {
                case ServiceEvent.REGISTERED:
                    return "REGISTERED";
                case ServiceEvent.MODIFIED:
                    return "MODIFIED";
                case ServiceEvent.MODIFIED_ENDMATCH:
                    return "MODIFIED ENDMATCH";
                case ServiceEvent.UNREGISTERING:
                    return "UNREGISTERING";
                default:
                    return String.valueOf(type);
            }
        }

        private String getFrameworkEventType(int type) {
            switch (type) {
                case FrameworkEvent.STARTED:
                    return "STARTED";
                case FrameworkEvent.ERROR:
                    return "ERROR";
                case FrameworkEvent.PACKAGES_REFRESHED:
                    return "PACKAGES REFRESHED";
                case FrameworkEvent.STARTLEVEL_CHANGED:
                    return "STARTLEVEL CHANGED";
                case FrameworkEvent.WARNING:
                    return "WARNING";
                case FrameworkEvent.INFO:
                    return "INFO";
                default:
                    return String.valueOf(type);
            }
        }
    }
}
