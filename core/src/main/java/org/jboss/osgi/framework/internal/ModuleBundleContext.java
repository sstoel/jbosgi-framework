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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.osgi.framework.spi.ServiceState;
import org.jboss.osgi.resolver.XBundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * The {@link BundleContext} of a bundle that adapts a module.
 *
 * It gives the bundle its own identity in the service registry and for the listeners and hooks,
 * like the bundle context of an installed bundle does.
 *
 * @since 11-Oct-2026
 */
public final class ModuleBundleContext extends AbstractBundleContext<XBundle> {

    private final BundleServices bundleServices = new BundleServices();

    private ModuleBundleContext(XBundle bundle, FrameworkState frameworkState) {
        super(bundle, frameworkState);
    }

    /**
     * Create the bundle context for the given bundle.
     *
     * @param systemContext The bundle context of the system bundle
     */
    public static ModuleBundleContext create(XBundle bundle, BundleContext systemContext) {
        FrameworkState frameworkState = AbstractBundleContext.assertBundleContext(systemContext).getFrameworkState();
        return new ModuleBundleContext(bundle, frameworkState);
    }

    public BundleServices getBundleServices() {
        return bundleServices;
    }

    /**
     * @return The references of the registered services, null if there are none
     */
    public ServiceReference<?>[] getRegisteredServices() {
        return toReferences(bundleServices.getRegisteredServices());
    }

    /**
     * @return The references of the services in use, null if there are none
     */
    public ServiceReference<?>[] getServicesInUse() {
        return toReferences(bundleServices.getServicesInUse());
    }

    private ServiceReference<?>[] toReferences(Collection<ServiceState<?>> serviceStates) {
        List<ServiceReference<?>> result = new ArrayList<ServiceReference<?>>();
        for (ServiceState<?> serviceState : serviceStates) {
            if (!serviceState.isUnregistered())
                result.add(serviceState.getReference());
        }
        return result.isEmpty() ? null : result.toArray(new ServiceReference<?>[result.size()]);
    }

    /**
     * Unregister the services that the bundle has registered, release the services that it uses and remove its listeners.
     */
    public void release() {
        XBundle bundle = getBundleState();
        FrameworkState frameworkState = getFrameworkState();
        bundleServices.release(bundle, frameworkState.getServiceManagerPlugin(), frameworkState.getFrameworkEvents());
        frameworkState.getFrameworkEvents().removeServiceListeners(bundle);
        frameworkState.getFrameworkEvents().removeFrameworkListeners(bundle);
    }

    /**
     * Release the services and listeners and invalidate this bundle context.
     */
    public void close() {
        release();
        destroy();
    }
}
