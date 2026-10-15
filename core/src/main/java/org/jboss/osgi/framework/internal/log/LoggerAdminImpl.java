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
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.admin.LoggerAdmin;
import org.osgi.service.log.admin.LoggerContext;

/**
 * The logger contexts of the framework.
 */
final class LoggerAdminImpl implements LoggerAdmin {

    private final LoggerContextImpl rootContext = new LoggerContextImpl(this, null);
    private final ConcurrentMap<String, LoggerContextImpl> contexts = new ConcurrentHashMap<String, LoggerContextImpl>();
    private final LogLevel defaultLogLevel;

    LoggerAdminImpl(String defaultLevel) {
        LogLevel level = LogLevel.WARN;
        if (defaultLevel != null) {
            try {
                // Only the name of a LogLevel, any other value gives the default (101.8.2)
                level = LogLevel.valueOf(defaultLevel);
            } catch (IllegalArgumentException ex) {
                // the default applies
            }
        }
        defaultLogLevel = level;
    }

    LogLevel getDefaultLogLevel() {
        return defaultLogLevel;
    }

    LoggerContextImpl getRootContext() {
        return rootContext;
    }

    @Override
    public LoggerContext getLoggerContext(String name) {
        return getContext(name);
    }

    LoggerContextImpl getContext(String name) {
        if (name == null)
            return rootContext;
        LoggerContextImpl context = contexts.get(name);
        if (context == null) {
            context = new LoggerContextImpl(this, name);
            LoggerContextImpl previous = contexts.putIfAbsent(name, context);
            if (previous != null)
                context = previous;
        }
        return context;
    }

    /**
     * The logger context of a bundle is the most specific named context that is not empty, or the root context.
     */
    LoggerContextImpl getContext(Bundle bundle) {
        String symbolicName = bundle != null ? bundle.getSymbolicName() : null;
        if (symbolicName == null || contexts.isEmpty())
            return rootContext;

        String versionName = symbolicName + "|" + bundle.getVersion();
        LoggerContextImpl context = contexts.get(versionName + "|" + bundle.getLocation());
        if (context == null || context.isEmpty())
            context = contexts.get(versionName);
        if (context == null || context.isEmpty())
            context = contexts.get(symbolicName);
        if (context == null || context.isEmpty())
            context = rootContext;
        return context;
    }
}
