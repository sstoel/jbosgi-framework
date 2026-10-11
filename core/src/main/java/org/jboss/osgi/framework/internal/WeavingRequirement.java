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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jboss.osgi.resolver.XPackageRequirement;

/**
 * A dynamic package requirement that a weaving hook has added to a bundle.
 *
 * The requirement is built from a temporary resource. This makes it a requirement of the host revision.
 *
 * @since 11-Oct-2026
 */
final class WeavingRequirement implements InvocationHandler {

    private final XPackageRequirement delegate;
    private final HostBundleRevision hostRev;

    private WeavingRequirement(XPackageRequirement delegate, HostBundleRevision hostRev) {
        this.delegate = delegate;
        this.hostRev = hostRev;
    }

    static XPackageRequirement create(XPackageRequirement delegate, HostBundleRevision hostRev) {
        Set<Class<?>> interfaces = new LinkedHashSet<Class<?>>();
        for (Class<?> clazz = delegate.getClass(); clazz != null; clazz = clazz.getSuperclass())
            addInterfaces(interfaces, clazz);
        ClassLoader classLoader = delegate.getClass().getClassLoader();
        return (XPackageRequirement) Proxy.newProxyInstance(classLoader, interfaces.toArray(new Class<?>[interfaces.size()]), new WeavingRequirement(delegate, hostRev));
    }

    private static void addInterfaces(Set<Class<?>> interfaces, Class<?> clazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (interfaces.add(iface))
                addInterfaces(interfaces, iface);
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int argCount = args != null ? args.length : 0;
        if (argCount == 0 && (name.equals("getResource") || name.equals("getRevision")))
            return hostRev;
        if (argCount == 0 && name.equals("hashCode"))
            return System.identityHashCode(proxy);
        if (argCount == 1 && name.equals("equals"))
            return proxy == args[0];

        Object result;
        try {
            result = method.invoke(delegate, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
        // Do not hand out the requirement of the temporary resource
        return result == delegate ? proxy : result;
    }
}
