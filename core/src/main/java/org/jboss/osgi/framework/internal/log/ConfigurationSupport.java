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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.LogLevel;
import org.osgi.service.log.admin.LoggerContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Configures the logger contexts from the Configuration Admin service, when there is one.
 *
 * The configuration with the PID org.osgi.service.log.admin has the log levels of the root logger context,
 * and the PID org.osgi.service.log.admin|name those of a named logger context. The framework does not have
 * the Configuration Admin API, the service is therefore used reflectively and a configuration listener is
 * registered with the API that the Configuration Admin service itself uses.
 */
final class ConfigurationSupport {

    private static final String CONFIGURATION_ADMIN = "org.osgi.service.cm.ConfigurationAdmin";
    private static final String CONFIGURATION_LISTENER = "org.osgi.service.cm.ConfigurationListener";
    private static final String CONFIGURATION_EVENT = "org.osgi.service.cm.ConfigurationEvent";
    private static final String CONFIGURATION = "org.osgi.service.cm.Configuration";
    private static final int CM_UPDATED = 1;
    private static final int CM_DELETED = 2;

    private final BundleContext context;
    private final LoggerAdminImpl loggerAdmin;
    private ServiceTracker<Object, ServiceRegistration<?>> tracker;

    ConfigurationSupport(BundleContext context, LoggerAdminImpl loggerAdmin) {
        this.context = context;
        this.loggerAdmin = loggerAdmin;
    }

    void open() {
        tracker = new ServiceTracker<Object, ServiceRegistration<?>>(context, CONFIGURATION_ADMIN, null) {
            @Override
            public ServiceRegistration<?> addingService(ServiceReference<Object> reference) {
                Object configAdmin = context.getService(reference);
                if (configAdmin == null)
                    return null;
                try {
                    ServiceRegistration<?> registration = registerListener(configAdmin);
                    loadConfigurations(configAdmin);
                    return registration;
                } catch (Throwable th) {
                    context.ungetService(reference);
                    return null;
                }
            }

            @Override
            public void removedService(ServiceReference<Object> reference, ServiceRegistration<?> registration) {
                try {
                    registration.unregister();
                } catch (IllegalStateException ex) {
                    // already unregistered
                }
                try {
                    context.ungetService(reference);
                } catch (IllegalStateException ex) {
                    // the system bundle context is no longer valid
                }
            }
        };
        tracker.open(true);
    }

    void close() {
        if (tracker != null) {
            try {
                tracker.close();
            } catch (IllegalStateException ex) {
                // the system bundle context is no longer valid
            }
            tracker = null;
        }
    }

    private ServiceRegistration<?> registerListener(final Object configAdmin) throws Exception {
        final ClassLoader loader = configAdmin.getClass().getClassLoader();
        Class<?> listenerType = loader.loadClass(CONFIGURATION_LISTENER);
        final Class<?> eventType = loader.loadClass(CONFIGURATION_EVENT);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[] { listenerType }, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("configurationEvent".equals(method.getName()) && args != null && args.length == 1) {
                    try {
                        String pid = (String) eventType.getMethod("getPid").invoke(args[0]);
                        int type = (Integer) eventType.getMethod("getType").invoke(args[0]);
                        configurationEvent(configAdmin, pid, type);
                    } catch (Throwable th) {
                        // the configuration cannot be applied
                    }
                    return null;
                }
                if ("hashCode".equals(method.getName()))
                    return System.identityHashCode(proxy);
                if ("equals".equals(method.getName()))
                    return proxy == args[0];
                if ("toString".equals(method.getName()))
                    return "LoggerContextConfigurationListener";
                return null;
            }
        });
        return context.registerService(CONFIGURATION_LISTENER, listener, null);
    }

    private static boolean isLoggerContextPid(String pid) {
        return pid != null && (pid.equals(LoggerContext.LOGGER_CONTEXT_PID) || pid.startsWith(LoggerContext.LOGGER_CONTEXT_PID + "|"));
    }

    private LoggerContextImpl getContext(String pid) {
        String prefix = LoggerContext.LOGGER_CONTEXT_PID + "|";
        return loggerAdmin.getContext(pid.startsWith(prefix) ? pid.substring(prefix.length()) : null);
    }

    private void configurationEvent(Object configAdmin, String pid, int type) throws Exception {
        if (isLoggerContextPid(pid) == false)
            return;

        if (type == CM_DELETED) {
            getContext(pid).clear();
        } else if (type == CM_UPDATED) {
            for (Object configuration : listConfigurations(configAdmin, "(service.pid=" + escape(pid) + ")"))
                applyConfiguration(configAdmin, configuration);
        }
    }

    private void loadConfigurations(Object configAdmin) throws Exception {
        for (Object configuration : listConfigurations(configAdmin, "(service.pid=" + LoggerContext.LOGGER_CONTEXT_PID + "*)"))
            applyConfiguration(configAdmin, configuration);
    }

    private Object[] listConfigurations(Object configAdmin, String filter) throws Exception {
        Class<?> adminType = configAdmin.getClass().getClassLoader().loadClass(CONFIGURATION_ADMIN);
        Object[] result = (Object[]) adminType.getMethod("listConfigurations", String.class).invoke(configAdmin, filter);
        return result != null ? result : new Object[0];
    }

    private void applyConfiguration(Object configAdmin, Object configuration) throws Exception {
        Class<?> configurationType = configAdmin.getClass().getClassLoader().loadClass(CONFIGURATION);
        String pid = (String) configurationType.getMethod("getPid").invoke(configuration);
        Dictionary<?, ?> properties = (Dictionary<?, ?>) configurationType.getMethod("getProperties").invoke(configuration);
        if (isLoggerContextPid(pid) == false || properties == null)
            return;

        // The properties that are not a log level are ignored
        Map<String, LogLevel> levels = new HashMap<String, LogLevel>();
        for (Enumeration<?> keys = properties.keys(); keys.hasMoreElements();) {
            Object key = keys.nextElement();
            Object value = properties.get(key);
            if (key instanceof String && value instanceof String) {
                try {
                    levels.put((String) key, LogLevel.valueOf((String) value));
                } catch (IllegalArgumentException ex) {
                    // not a log level
                }
            }
        }
        getContext(pid).setLogLevels(levels);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '*' || c == '(' || c == ')')
                result.append('\\');
            result.append(c);
        }
        return result.toString();
    }
}
