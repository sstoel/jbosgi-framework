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

import static org.jboss.osgi.framework.FrameworkLogger.LOGGER;
import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.ServiceManager;
import org.jboss.osgi.framework.spi.ServiceState;
import org.jboss.osgi.resolver.XBundle;

/**
 * The services that a bundle has registered and the services that it uses.
 *
 * Every bundle that has its own bundle context has one, also a bundle that adapts a module.
 *
 * @since 11-Oct-2026
 */
public final class BundleServices {

    private final List<ServiceState<?>> registeredServices = new CopyOnWriteArrayList<ServiceState<?>>();
    private final ConcurrentHashMap<ServiceState<?>, AtomicInteger> usedServices = new ConcurrentHashMap<ServiceState<?>, AtomicInteger>();
    private final Set<ServiceState<?>> usedPrototypeServices = ConcurrentHashMap.newKeySet();

    BundleServices() {
    }

    /**
     * Get the services of the given bundle
     */
    static BundleServices get(XBundle bundle) {
        BundleServices result;
        if (bundle instanceof AbstractBundleState) {
            result = ((AbstractBundleState<?>) bundle).getBundleServices();
        } else {
            result = bundle.adapt(BundleServices.class);
        }
        if (result == null)
            throw MESSAGES.illegalStateInvalidBundleContext(bundle);
        return result;
    }

    void addRegisteredService(ServiceState<?> serviceState) {
        registeredServices.add(serviceState);
    }

    void removeRegisteredService(ServiceState<?> serviceState) {
        registeredServices.remove(serviceState);
    }

    List<ServiceState<?>> getRegisteredServices() {
        return Collections.unmodifiableList(registeredServices);
    }

    /**
     * The services for which the bundle holds a service object
     */
    Set<ServiceState<?>> getServicesInUse() {
        Set<ServiceState<?>> result = new HashSet<ServiceState<?>>(usedServices.keySet());
        result.addAll(usedPrototypeServices);
        return Collections.unmodifiableSet(result);
    }

    boolean hasServiceUseCount(ServiceState<?> serviceState) {
        return usedServices.containsKey(serviceState);
    }

    void addPrototypeServiceInUse(ServiceState<?> serviceState) {
        usedPrototypeServices.add(serviceState);
    }

    void removePrototypeServiceInUse(ServiceState<?> serviceState) {
        usedPrototypeServices.remove(serviceState);
    }

    void addServiceInUse(ServiceState<?> serviceState) {
        // The count is changed within the map operation, a concurrent remove cannot drop the entry in between
        usedServices.compute(serviceState, (k, count) -> {
            if (count == null)
                count = new AtomicInteger();
            count.incrementAndGet();
            return count;
        });
    }

    /**
     * @return The remaining use count, -1 if the service is not in use
     */
    int removeServiceInUse(ServiceState<?> serviceState) {
        int[] result = new int[] { -1 };
        usedServices.computeIfPresent(serviceState, (k, count) -> {
            result[0] = count.decrementAndGet();
            return result[0] > 0 ? count : null;
        });
        return result[0];
    }

    /**
     * Unregister the services that the bundle has registered, release the services that it uses and remove its listeners.
     */
    void release(XBundle bundle, ServiceManager serviceManager, FrameworkEvents frameworkEvents) {
        LOGGER.tracef("Release services and listeners of: %s", bundle);
        for (ServiceState<?> serviceState : getRegisteredServices()) {
            serviceState.unregisterInternal();
        }
        for (ServiceState<?> serviceState : getServicesInUse()) {
            while (serviceManager.ungetService(bundle, serviceState)) {
            }
            ((ServiceStateImpl<?>) serviceState).releasePrototypeValues(bundle);
            serviceState.removeUsingBundle(bundle);
        }
        // Any listeners registered by the bundle must be removed (Bundle.stop step 7)
        frameworkEvents.removeBundleListeners(bundle);
        frameworkEvents.removeServiceListeners(bundle);
        frameworkEvents.removeFrameworkListeners(bundle);
    }
}
