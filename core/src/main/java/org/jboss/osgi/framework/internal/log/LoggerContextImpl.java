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

import java.util.HashMap;
import java.util.Map;

import org.osgi.service.log.LogLevel;
import org.osgi.service.log.Logger;
import org.osgi.service.log.admin.LoggerContext;

/**
 * The log levels that are configured for the root context or for the bundles that a named context applies to.
 */
final class LoggerContextImpl implements LoggerContext {

    private final LoggerAdminImpl admin;
    private final String name;
    private volatile Map<String, LogLevel> logLevels = new HashMap<String, LogLevel>();

    LoggerContextImpl(LoggerAdminImpl admin, String name) {
        this.admin = admin;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public LogLevel getEffectiveLogLevel(String loggerName) {
        Map<String, LogLevel> levels = logLevels;
        if (levels.isEmpty() == false) {
            // The logger name and its ancestors
            String ancestor = loggerName;
            while (ancestor != null && ancestor.length() > 0) {
                LogLevel level = levels.get(ancestor);
                if (level != null)
                    return level;
                int index = ancestor.lastIndexOf('.');
                ancestor = index > 0 ? ancestor.substring(0, index) : null;
            }
            LogLevel level = levels.get(Logger.ROOT_LOGGER_NAME);
            if (level != null)
                return level;
        }
        if (name != null)
            return admin.getRootContext().getEffectiveLogLevel(loggerName);
        return admin.getDefaultLogLevel();
    }

    @Override
    public Map<String, LogLevel> getLogLevels() {
        return new HashMap<String, LogLevel>(logLevels);
    }

    @Override
    public void setLogLevels(Map<String, LogLevel> levels) {
        Map<String, LogLevel> copy = new HashMap<String, LogLevel>();
        if (levels != null) {
            for (Map.Entry<String, LogLevel> entry : levels.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null)
                    copy.put(entry.getKey(), entry.getValue());
            }
        }
        logLevels = copy;
    }

    @Override
    public void clear() {
        logLevels = new HashMap<String, LogLevel>();
    }

    @Override
    public boolean isEmpty() {
        return logLevels.isEmpty();
    }

    @Override
    public String toString() {
        return "LoggerContext[" + name + "," + logLevels + "]";
    }
}
