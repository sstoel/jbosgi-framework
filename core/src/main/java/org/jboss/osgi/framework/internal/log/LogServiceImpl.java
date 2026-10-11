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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.FormatterLogger;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;

/**
 * The log service and logger factory of a bundle.
 */
final class LogServiceImpl implements LogService {

    private static final int RESOLVED_STATES = Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING;

    private final LogServices services;
    private final Bundle bundle;
    private final ConcurrentMap<String, LoggerImpl> loggers = new ConcurrentHashMap<String, LoggerImpl>();

    LogServiceImpl(LogServices services, Bundle bundle) {
        this.services = services;
        this.bundle = bundle;
    }

    @Override
    public Logger getLogger(String name) {
        return getLogger(name, Logger.class);
    }

    @Override
    public Logger getLogger(Class<?> clazz) {
        return getLogger(clazz.getName(), Logger.class);
    }

    @Override
    public <L extends Logger> L getLogger(String name, Class<L> loggerType) {
        return getLoggerInternal(bundle, name, loggerType);
    }

    @Override
    public <L extends Logger> L getLogger(Class<?> clazz, Class<L> loggerType) {
        return getLoggerInternal(bundle, clazz.getName(), loggerType);
    }

    @Override
    public <L extends Logger> L getLogger(Bundle loggerBundle, String name, Class<L> loggerType) {
        if (loggerBundle == null || (loggerBundle.getState() & RESOLVED_STATES) == 0)
            throw new IllegalArgumentException("Not a resolved bundle: " + loggerBundle);
        return getLoggerInternal(loggerBundle, name, loggerType);
    }

    private <L extends Logger> L getLoggerInternal(Bundle loggerBundle, String name, Class<L> loggerType) {
        if (name == null)
            throw new IllegalArgumentException("Null logger name");
        boolean formatter = FormatterLogger.class.equals(loggerType);
        if (formatter == false && Logger.class.equals(loggerType) == false)
            throw new IllegalArgumentException("Unsupported logger type: " + loggerType);

        String key = loggerBundle.getBundleId() + (formatter ? "|F|" : "|L|") + name;
        LoggerImpl logger = loggers.get(key);
        if (logger == null) {
            logger = formatter ? new FormatterLoggerImpl(services, loggerBundle, name) : new LoggerImpl(services, loggerBundle, name);
            LoggerImpl previous = loggers.putIfAbsent(key, logger);
            if (previous != null)
                logger = previous;
        }
        return loggerType.cast(logger);
    }

    @Override
    public void log(int level, String message) {
        log(null, level, message, null);
    }

    @Override
    public void log(int level, String message, Throwable exception) {
        log(null, level, message, exception);
    }

    @Override
    public void log(ServiceReference<?> reference, int level, String message) {
        log(reference, level, message, null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void log(ServiceReference<?> reference, int level, String message, Throwable exception) {
        LogLevel logLevel;
        switch (level) {
            case LOG_ERROR:
                logLevel = LogLevel.ERROR;
                break;
            case LOG_WARNING:
                logLevel = LogLevel.WARN;
                break;
            case LOG_INFO:
                logLevel = LogLevel.INFO;
                break;
            case LOG_DEBUG:
                logLevel = LogLevel.DEBUG;
                break;
            default:
                logLevel = LogLevel.TRACE;
        }
        String name = "LogService." + bundle.getSymbolicName();
        if (services.isEnabled(bundle, name, logLevel))
            services.log(name, bundle, reference, logLevel, level, message, exception);
    }
}
