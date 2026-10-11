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

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogLevel;

/**
 * An entry in the log.
 */
final class LogEntryImpl implements LogEntry {

    private final String loggerName;
    private final Bundle bundle;
    private final ServiceReference<?> reference;
    private final LogLevel logLevel;
    private final int level;
    private final String message;
    private final Throwable exception;
    private final long time;
    private final long sequence;
    private final String threadInfo;
    private final StackTraceElement location;

    LogEntryImpl(String loggerName, Bundle bundle, ServiceReference<?> reference, LogLevel logLevel, int level, String message, Throwable exception, long sequence,
            StackTraceElement location) {
        this.loggerName = loggerName;
        this.bundle = bundle;
        this.reference = reference;
        this.logLevel = logLevel;
        this.level = level;
        this.message = message;
        this.exception = exception;
        this.time = System.currentTimeMillis();
        this.sequence = sequence;
        this.threadInfo = Thread.currentThread().getName();
        this.location = location;
    }

    @Override
    public Bundle getBundle() {
        return bundle;
    }

    @Override
    public ServiceReference<?> getServiceReference() {
        return reference;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public Throwable getException() {
        return exception;
    }

    @Override
    public long getTime() {
        return time;
    }

    @Override
    public LogLevel getLogLevel() {
        return logLevel;
    }

    @Override
    public String getLoggerName() {
        return loggerName;
    }

    @Override
    public long getSequence() {
        return sequence;
    }

    @Override
    public String getThreadInfo() {
        return threadInfo;
    }

    @Override
    public StackTraceElement getLocation() {
        return location;
    }

    @Override
    public String toString() {
        return "LogEntry[" + logLevel + "," + loggerName + "," + message + "]";
    }
}
