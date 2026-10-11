package org.jboss.osgi.framework.internal;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;

/**
 * A copy of the properties of a service that is owned by the caller.
 *
 * Keys are matched case-insensitively and returned in the case they were added with.
 *
 * @see org.osgi.framework.ServiceReference#getProperties()
 */
final class MutableServiceProperties extends Dictionary<String, Object> {

    private final Map<String, Object> properties = new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);

    MutableServiceProperties(Dictionary<String, Object> source) {
        if (source != null) {
            Enumeration<String> keys = source.keys();
            while (keys.hasMoreElements()) {
                String key = keys.nextElement();
                properties.put(key, source.get(key));
            }
        }
    }

    @Override
    public synchronized int size() {
        return properties.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return properties.isEmpty();
    }

    @Override
    public synchronized Enumeration<String> keys() {
        return Collections.enumeration(new ArrayList<String>(properties.keySet()));
    }

    @Override
    public synchronized Enumeration<Object> elements() {
        return Collections.enumeration(new ArrayList<Object>(properties.values()));
    }

    @Override
    public synchronized Object get(Object key) {
        return key instanceof String ? properties.get(key) : null;
    }

    @Override
    public synchronized Object put(String key, Object value) {
        if (key == null || value == null)
            throw new NullPointerException();
        return properties.put(key, value);
    }

    @Override
    public synchronized Object remove(Object key) {
        return key instanceof String ? properties.remove(key) : null;
    }

    @Override
    public synchronized String toString() {
        return properties.toString();
    }
}
