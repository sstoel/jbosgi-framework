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
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerConsumer;

/**
 * A logger of a bundle that formats messages in the SLF4J style.
 */
class LoggerImpl implements Logger {

    private final LogServices services;
    private final Bundle bundle;
    private final String name;

    LoggerImpl(LogServices services, Bundle bundle, String name) {
        this.services = services;
        this.bundle = bundle;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    boolean isEnabled(LogLevel level) {
        return services.isEnabled(bundle, name, level);
    }

    /**
     * Format the message with the arguments that are not the exception or the service reference of the log entry.
     */
    String format(String format, Object[] arguments, int count) {
        if (format == null || count == 0)
            return format;

        StringBuilder result = new StringBuilder(format.length() + 16 * count);
        int next = 0;
        int position = 0;
        while (next < count) {
            int index = format.indexOf("{}", position);
            if (index < 0)
                break;
            boolean escaped = index > 0 && format.charAt(index - 1) == '\\';
            boolean escapedEscape = escaped && index > 1 && format.charAt(index - 2) == '\\';
            if (escaped && escapedEscape == false) {
                // A literal place holder
                result.append(format, position, index - 1).append("{}");
            } else {
                // An escaped backslash before the place holder leaves one backslash
                result.append(format, position, escapedEscape ? index - 1 : index).append(String.valueOf(arguments[next++]));
            }
            position = index + 2;
        }
        return result.append(format, position, format.length()).toString();
    }

    final void log(LogLevel level, String format, Object... arguments) {
        if (isEnabled(level) == false)
            return;

        // A trailing exception and service reference, in any order, belong to the log entry
        Throwable exception = null;
        ServiceReference<?> reference = null;
        int count = arguments != null ? arguments.length : 0;
        for (int i = 0; i < 2 && count > 0; i++) {
            Object last = arguments[count - 1];
            if (last instanceof Throwable && exception == null) {
                exception = (Throwable) last;
            } else if (last instanceof ServiceReference && reference == null) {
                reference = (ServiceReference<?>) last;
            } else {
                break;
            }
            count--;
        }
        services.log(name, bundle, reference, level, level.ordinal(), format(format, arguments, count), exception);
    }

    private <E extends Exception> void accept(LogLevel level, LoggerConsumer<E> consumer) throws E {
        if (isEnabled(level))
            consumer.accept(this);
    }

    @Override
    public boolean isTraceEnabled() {
        return isEnabled(LogLevel.TRACE);
    }

    @Override
    public void trace(String message) {
        log(LogLevel.TRACE, message);
    }

    @Override
    public void trace(String format, Object arg) {
        log(LogLevel.TRACE, format, arg);
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        log(LogLevel.TRACE, format, arg1, arg2);
    }

    @Override
    public void trace(String format, Object... arguments) {
        log(LogLevel.TRACE, format, arguments);
    }

    @Override
    public <E extends Exception> void trace(LoggerConsumer<E> consumer) throws E {
        accept(LogLevel.TRACE, consumer);
    }

    @Override
    public boolean isDebugEnabled() {
        return isEnabled(LogLevel.DEBUG);
    }

    @Override
    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    @Override
    public void debug(String format, Object arg) {
        log(LogLevel.DEBUG, format, arg);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        log(LogLevel.DEBUG, format, arg1, arg2);
    }

    @Override
    public void debug(String format, Object... arguments) {
        log(LogLevel.DEBUG, format, arguments);
    }

    @Override
    public <E extends Exception> void debug(LoggerConsumer<E> consumer) throws E {
        accept(LogLevel.DEBUG, consumer);
    }

    @Override
    public boolean isInfoEnabled() {
        return isEnabled(LogLevel.INFO);
    }

    @Override
    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    @Override
    public void info(String format, Object arg) {
        log(LogLevel.INFO, format, arg);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        log(LogLevel.INFO, format, arg1, arg2);
    }

    @Override
    public void info(String format, Object... arguments) {
        log(LogLevel.INFO, format, arguments);
    }

    @Override
    public <E extends Exception> void info(LoggerConsumer<E> consumer) throws E {
        accept(LogLevel.INFO, consumer);
    }

    @Override
    public boolean isWarnEnabled() {
        return isEnabled(LogLevel.WARN);
    }

    @Override
    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    @Override
    public void warn(String format, Object arg) {
        log(LogLevel.WARN, format, arg);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        log(LogLevel.WARN, format, arg1, arg2);
    }

    @Override
    public void warn(String format, Object... arguments) {
        log(LogLevel.WARN, format, arguments);
    }

    @Override
    public <E extends Exception> void warn(LoggerConsumer<E> consumer) throws E {
        accept(LogLevel.WARN, consumer);
    }

    @Override
    public boolean isErrorEnabled() {
        return isEnabled(LogLevel.ERROR);
    }

    @Override
    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    @Override
    public void error(String format, Object arg) {
        log(LogLevel.ERROR, format, arg);
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        log(LogLevel.ERROR, format, arg1, arg2);
    }

    @Override
    public void error(String format, Object... arguments) {
        log(LogLevel.ERROR, format, arguments);
    }

    @Override
    public <E extends Exception> void error(LoggerConsumer<E> consumer) throws E {
        accept(LogLevel.ERROR, consumer);
    }

    @Override
    public void audit(String message) {
        log(LogLevel.AUDIT, message);
    }

    @Override
    public void audit(String format, Object arg) {
        log(LogLevel.AUDIT, format, arg);
    }

    @Override
    public void audit(String format, Object arg1, Object arg2) {
        log(LogLevel.AUDIT, format, arg1, arg2);
    }

    @Override
    public void audit(String format, Object... arguments) {
        log(LogLevel.AUDIT, format, arguments);
    }

    @Override
    public String toString() {
        return "Logger[" + name + "," + bundle + "]";
    }
}
