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
package org.jboss.osgi.framework.internal;

import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.osgi.framework.ServiceReference;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * There can only ever be one URLStreamHandlerFactory active in the system and it can only be set once, using
 * {@link URL#setURLStreamHandlerFactory()}.
 *
 * @author Thomas.Diesler@jboss.com
 * @since 10-Jan-2011
 */
final class OSGiStreamHandlerFactoryDelegate implements URLStreamHandlerFactory, BiConsumer<String, URLStreamHandlerFactory> {

    private URLStreamHandlerFactory delegate;
    // The URL handler factories of the frameworks that are loaded by other class loaders, by the prefix of their bundle
    // URLs. Only the factory of the first class loader becomes the factory of the JVM, the others register with it
    private final Map<String, URLStreamHandlerFactory> foreignFactories = new ConcurrentHashMap<String, URLStreamHandlerFactory>();

    /**
     * Register the factory of a framework of another class loader, or remove it when the factory is null. Called through
     * the JDK interface, across class loaders.
     */
    @Override
    public void accept(String prefix, URLStreamHandlerFactory factory) {
        if (factory != null)
            foreignFactories.put(prefix, factory);
        else
            foreignFactories.remove(prefix);
    }

    URLStreamHandlerFactory getForeignFactory(String prefix) {
        return prefix != null ? foreignFactories.get(prefix) : null;
    }

    Collection<URLStreamHandlerFactory> getForeignFactories() {
        return foreignFactories.values();
    }
    private Map<String, List<ServiceReference<URLStreamHandlerService>>> streamHandlers = new HashMap<String, List<ServiceReference<URLStreamHandlerService>>>();

    void setDelegateFactory(URLStreamHandlerFactory factory) {
        delegate = factory;
    }

    void addHandler(String protocol, ServiceReference<URLStreamHandlerService> reference) {
        synchronized (streamHandlers) {
            List<ServiceReference<URLStreamHandlerService>> list = streamHandlers.get(protocol);
            if (list == null) {
                list = new ArrayList<ServiceReference<URLStreamHandlerService>>();
                streamHandlers.put(protocol, list);
            }
            list.add(reference);
            Collections.sort(list, Collections.reverseOrder(ServiceReferenceComparator.getInstance()));
        }
    }

    /** A copy of the handler references of the protocol, the highest ranked first, or null */
    List<ServiceReference<URLStreamHandlerService>> getStreamHandlers(String protocol) {
        synchronized (streamHandlers) {
            List<ServiceReference<URLStreamHandlerService>> list = streamHandlers.get(protocol);
            return list != null ? new ArrayList<ServiceReference<URLStreamHandlerService>>(list) : null;
        }
    }

    void removeHandler(ServiceReference<URLStreamHandlerService> reference) {
        synchronized (streamHandlers) {
            for (List<ServiceReference<URLStreamHandlerService>> list : streamHandlers.values()) {
                for (Iterator<ServiceReference<URLStreamHandlerService>> it = list.iterator(); it.hasNext();) {
                    if (it.next().equals(reference)) {
                        it.remove();
                        break;
                    }
                }
            }
        }
    }

    void clearHandlers() {
        synchronized (streamHandlers) {
            for (List<ServiceReference<URLStreamHandlerService>> list : streamHandlers.values()) {
                list.clear();
            }
        }
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        URLStreamHandler handler = delegate != null ? delegate.createURLStreamHandler(protocol) : null;
        // The JVM caches the handler of a protocol: the proxy also serves the frameworks of other class loaders
        if (handler == null) {
            for (URLStreamHandlerFactory factory : foreignFactories.values()) {
                if (factory.createURLStreamHandler(protocol) != null)
                    return new URLHandlerSupportImpl.URLStreamHandlerProxy(protocol);
            }
        }
        return handler;
    }
}
