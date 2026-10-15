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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.osgi.framework.spi.BundleManager;
import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.framework.spi.ServiceManager;
import org.jboss.osgi.framework.spi.ServiceState;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.spi.RemoveOnlyCollection;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.service.FindHook;
import org.osgi.framework.hooks.service.ListenerHook;
import org.osgi.framework.hooks.service.ListenerHook.ListenerInfo;

/**
 * A plugin that manages OSGi services
 *
 * @author thomas.diesler@jboss.com
 * @since 18-Aug-2009
 */
public final class ServiceManagerImpl implements ServiceManager {

    private final FrameworkEvents frameworkEvents;
    private final BundleManager bundleManager;
    private final Map<String, List<ServiceState<?>>> serviceContainer = new HashMap<String, List<ServiceState<?>>>();
    // The services that are being obtained in this thread, by the bundle that obtains them
    private final ThreadLocal<Set<Map.Entry<XBundle, ServiceState<?>>>> getServiceInProgress = new ThreadLocal<Set<Map.Entry<XBundle, ServiceState<?>>>>();
    private final AtomicLong identityGenerator = new AtomicLong();

    public ServiceManagerImpl(FrameworkEvents frameworkEvents) {
        this(frameworkEvents, null);
    }

    public ServiceManagerImpl(FrameworkEvents frameworkEvents, BundleManager bundleManager) {
        this.frameworkEvents = frameworkEvents;
        this.bundleManager = bundleManager;
    }

    // The hooks are used by the framework, as the system bundle
    private XBundle getHookUser(XBundle bundle) {
        XBundle systemBundle = bundleManager != null ? bundleManager.getSystemBundle() : null;
        return systemBundle != null ? systemBundle : bundle;
    }

    @Override
    public void fireFrameworkEvent(XBundle bundle, int type, ServiceException ex) {
        frameworkEvents.fireFrameworkEvent(bundle, type, ex);
    }

    @Override
    public void fireServiceEvent(XBundle bundle, int type, ServiceState<?> serviceState) {
        frameworkEvents.fireServiceEvent(bundle, type, serviceState);
    }

    private long getNextServiceId() {
        return identityGenerator.incrementAndGet();
    }

    /**
     * Registers the specified service object with the specified properties under the specified class names into the Framework.
     * A <code>ServiceRegistration</code> object is returned. The <code>ServiceRegistration</code> object is for the private use
     * of the bundle registering the service and should not be shared with other bundles. The registering bundle is defined to
     * be the context bundle.
     *
     * @param classNames The class names under which the service can be located.
     * @param serviceValue The service object or a <code>ServiceFactory</code> object.
     * @param properties The properties for this service.
     * @return A <code>ServiceRegistration</code> object for use by the bundle registering the service
     */
    @Override
    @SuppressWarnings({ "rawtypes" })
    public ServiceState registerService(XBundle bundle, String[] classNames, final Object serviceValue, Dictionary properties) {
        assert classNames != null && classNames.length > 0 : "Null service classes";

        ServiceState.ValueProvider<Object> valueProvider = new ServiceState.ValueProvider<Object>() {
            @Override
            public boolean isFactoryValue() {
                return serviceValue instanceof ServiceFactory;
            }

            @Override
            public Object getValue() {
                return serviceValue;
            }
        };

        long serviceId = getNextServiceId();
        BundleServices bundleServices = BundleServices.get(bundle);
        ServiceState<?> serviceState = new ServiceStateImpl<Object>(this, bundle, serviceId, classNames, valueProvider, properties);
        LOGGER.debugf("Register service: %s", serviceState);

        synchronized (serviceContainer) {
            for (String className : classNames) {
                List<ServiceState<?>> serviceStates = serviceContainer.get(className);
                if (serviceStates != null) {
                    serviceStates.add(serviceState);
                } else {
                    serviceStates = new CopyOnWriteArrayList<ServiceState<?>>();
                    serviceStates.add(serviceState);
                    serviceContainer.put(className, serviceStates);
                }
            }
        }
        bundleServices.addRegisteredService(serviceState);

        // Immediately after registration of a {@link ListenerHook}, the ListenerHook.added() method will be called
        // to provide the current collection of service listeners which had been added prior to the hook being registered.
        // The hook is known by its registered class name, it may be registered as a ServiceFactory
        if (Arrays.asList(classNames).contains(ListenerHook.class.getName())) {
            XBundle hookUser = getHookUser(bundle);
            Collection<ListenerInfo> listenerInfos = frameworkEvents.getServiceListenerInfos(null);
            Object hook = getService(hookUser, serviceState);
            if (hook instanceof ListenerHook) {
                try {
                    ((ListenerHook) hook).added(listenerInfos);
                } catch (RuntimeException ex) {
                    LOGGER.errorProcessingServiceListenerHook(ex, (ListenerHook) hook);
                } finally {
                    ungetService(hookUser, serviceState);
                }
            }
        }

        // This event is synchronously delivered after the service has been registered with the Framework.
        frameworkEvents.fireServiceEvent(bundle, ServiceEvent.REGISTERED, serviceState);

        return serviceState;
    }

    /**
     * Returns a <code>ServiceReference</code> object for a service that implements and was registered under the specified
     * class.
     *
     * @param clazz The class name with which the service was registered.
     * @return A <code>ServiceReference</code> object, or <code>null</code>
     */
    @Override
    public ServiceState<?> getServiceReference(XBundle bundle, String clazz) {
        assert clazz != null : "Null clazz";

        boolean checkAssignable = (bundle.getBundleId() != 0);
        List<ServiceState<?>> result = getServiceReferencesInternal(bundle, clazz, NoFilter.INSTANCE, checkAssignable);
        result = processFindHooks(bundle, clazz, null, true, result);
        if (result.isEmpty())
            return null;

        int lastIndex = result.size() - 1;
        return result.get(lastIndex);
    }

    /**
     * Returns an array of <code>ServiceReference</code> objects. The returned array of <code>ServiceReference</code> objects
     * contains services that were registered under the specified class, match the specified filter expression.
     *
     * If checkAssignable is true, the packages for the class names under which the services were registered match the context
     * bundle's packages as defined in {@link ServiceReference#isAssignableTo(Bundle, String)}.
     *
     *
     * @param clazz The class name with which the service was registered or <code>null</code> for all services.
     * @param filterStr The filter expression or <code>null</code> for all services.
     * @return A potentially empty list of <code>ServiceReference</code> objects.
     */
    @Override
    public List<ServiceState<?>> getServiceReferences(XBundle bundle, String clazz, String filterStr, boolean checkAssignable) throws InvalidSyntaxException {
        Filter filter = NoFilter.INSTANCE;
        if (filterStr != null)
            filter = FrameworkUtil.createFilter(filterStr);

        List<ServiceState<?>> result = getServiceReferencesInternal(bundle, clazz, filter, checkAssignable);
        result = processFindHooks(bundle, clazz, filterStr, checkAssignable, result);
        return result;
    }

    private List<ServiceState<?>> getServiceReferencesInternal(final XBundle bundle, String className, Filter filter, boolean checkAssignable) {
        assert bundle != null : "Null bundleState";
        assert filter != null : "Null filter";

        Set<ServiceState<?>> initialSet = new HashSet<ServiceState<?>>();
        synchronized (serviceContainer) {
            if (className != null) {
                List<ServiceState<?>> list = serviceContainer.get(className);
                if (list != null) {
                    initialSet.addAll(list);
                }
            } else {
                for (List<ServiceState<?>> list : serviceContainer.values()) {
                    initialSet.addAll(list);
                }
            }
        }        

        if (initialSet.isEmpty())
            return Collections.emptyList();

        Set<ServiceState<?>> resultset = new HashSet<ServiceState<?>>();
        for (ServiceState<?> serviceState : initialSet) {
            if (isMatchingService(bundle, serviceState, className, filter, checkAssignable)) {
                resultset.add(serviceState);
            }
        }

        // Sort the result
        List<ServiceState<?>> resultList = new ArrayList<ServiceState<?>>(resultset);
        if (resultList.size() > 1)
            Collections.sort(resultList, ServiceReferenceComparator.getInstance());

        return Collections.unmodifiableList(resultList);
    }

    private static boolean isReleased(ServiceState<?> serviceState) {
        if (serviceState instanceof ServiceStateImpl)
            return ((ServiceStateImpl<?>) serviceState).isReleased();
        return serviceState.isUnregistered();
    }

    private boolean isMatchingService(XBundle bundle, ServiceState<?> serviceState, String clazzName, Filter filter, boolean checkAssignable) {
        if (serviceState.isUnregistered() || filter.match(serviceState) == false)
            return false;
        if (checkAssignable == false || clazzName == null)
            return true;

        return serviceState.isAssignableTo(bundle, clazzName);
    }

    /**
     * Returns the service object referenced by the specified <code>ServiceReference</code> object.
     *
     * @return A service object for the service associated with <code>reference</code> or <code>null</code>
     */
    @Override
    public <S> S getService(XBundle bundle, ServiceState<S> serviceState) {
        // If the service has been unregistered, null is returned. That is also the case once the UNREGISTERING
        // event was delivered: the framework releases the service objects
        if (isReleased(serviceState))
            return null;

        // If the ServiceFactory.getService method is called recursively for the same bundle and service, null is
        // returned and a Framework event of type FrameworkEvent.ERROR is fired
        Map.Entry<XBundle, ServiceState<?>> usage = new AbstractMap.SimpleImmutableEntry<XBundle, ServiceState<?>>(bundle, serviceState);
        Set<Map.Entry<XBundle, ServiceState<?>>> inProgress = getServiceInProgress.get();
        if (inProgress != null && inProgress.contains(usage)) {
            ServiceException sex = new ServiceException("Recursive getService for " + serviceState + " by " + bundle, ServiceException.FACTORY_RECURSION);
            frameworkEvents.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, sex);
            return null;
        }

        S value;
        boolean outermost = inProgress == null;
        if (outermost) {
            inProgress = new HashSet<Map.Entry<XBundle, ServiceState<?>>>();
            getServiceInProgress.set(inProgress);
        }
        inProgress.add(usage);
        try {
            // Add the given service ref to the list of used services
            BundleServices bundleServices = BundleServices.get(bundle);
            bundleServices.addServiceInUse(serviceState);
            serviceState.addUsingBundle(bundle);

            value = serviceState.getScopedValue(bundle);

            // If the factory returned an invalid value
            // restore the service usage counts
            if (value == null) {
                bundleServices.removeServiceInUse(serviceState);
                serviceState.removeUsingBundle(bundle);
            }

            // The service was released meanwhile, this use would not be released any more
            else if (isReleased(serviceState)) {
                ungetService(bundle, serviceState);
                value = null;
            }
        } finally {
            inProgress.remove(usage);
            if (outermost)
                getServiceInProgress.remove();
        }
        return value;
    }

    /**
     * Unregister the given service.
     *
     * Only the removal from the registry is done under the monitor of the service. The UNREGISTERING event and the
     * release of the service by its users call out to listeners, hooks and service factories, which may take the
     * monitor of another service that is unregistered at the same time. The service state lets only one thread
     * unregister the service.
     */
    @Override
    public void unregisterService(ServiceState<?> serviceState) {
        synchronized (serviceState) {

            if (serviceState.isUnregistered())
                return;

            synchronized (serviceContainer) {
                for (String className : serviceState.getClassNames()) {
                    LOGGER.debugf("Unregister service: %s", className);
                    try {
                        List<ServiceState<?>> serviceStates = serviceContainer.get(className);
                        if (serviceStates != null) {
                            serviceStates.remove(serviceState);
                        }
                    } catch (RuntimeException ex) {
                        LOGGER.errorCannotRemoveService(ex, className);
                    }
                }
            }
        }

        XBundle serviceOwner = serviceState.getServiceOwner();

        // This event is synchronously delivered before the service has completed unregistering.
        frameworkEvents.fireServiceEvent(serviceOwner, ServiceEvent.UNREGISTERING, serviceState);

        // Once delivery of the event is complete, the service objects cannot be obtained any more
        if (serviceState instanceof ServiceStateImpl)
            ((ServiceStateImpl<?>) serviceState).setReleased();

        // Remove from using bundles
        for (XBundle bundle : serviceState.getUsingBundlesInternal()) {
            while (ungetService(bundle, serviceState)) {
            }
            ((ServiceStateImpl<?>) serviceState).releasePrototypeValues(bundle);
            serviceState.removeUsingBundle(bundle);
        }
        // A bundle may have got a prototype service object in the meantime
        ((ServiceStateImpl<?>) serviceState).releaseAllPrototypeValues();

        // Remove from owner bundle
        BundleServices.get(serviceOwner).removeRegisteredService(serviceState);
    }

    /**
     * Releases the service object referenced by the specified <code>ServiceReference</code> object. If the context bundle's use
     * count for the service is zero, this method returns <code>false</code>. Otherwise, the context bundle's use count for the
     * service is decremented by one.
     *
     * @return <code>false</code> if the context bundle's use count for the service is zero or if the service has been
     *         unregistered; <code>true</code> otherwise.
     */
    @Override
    public boolean ungetService(XBundle bundle, ServiceState<?> serviceState) {
        serviceState.ungetScopedValue(bundle);
        int useCount = BundleServices.get(bundle).removeServiceInUse(serviceState);
        if (useCount == 0 && !((ServiceStateImpl<?>) serviceState).hasPrototypeValues(bundle))
            serviceState.removeUsingBundle(bundle);

        return useCount >= 0;
    }

    /*
     * The FindHook is called when a target bundle searches the service registry with the getServiceReference or
     * getServiceReferences methods. A registered FindHook service gets a chance to inspect the returned set of service
     * references and can optionally shrink the set of returned services. The order in which the find hooks are called is the
     * reverse compareTo ordering of their Service References.
     */
    private List<ServiceState<?>> processFindHooks(XBundle bundle, String clazz, String filterStr, boolean checkAssignable, List<ServiceState<?>> serviceStates) {

        // The hooks are also called when nothing was found
        List<ServiceState<?>> hookRefs = getServiceReferencesInternal(bundle, FindHook.class.getName(), NoFilter.INSTANCE, true);
        if (hookRefs.isEmpty())
            return serviceStates;

        // Event and Find Hooks can not be used to hide the services from the framework, which looks up the hooks
        // of the framework
        if (clazz != null && clazz.startsWith("org.osgi.framework.hooks."))
            return serviceStates;

        // The order in which the find hooks are called is the reverse compareTo ordering of
        // their ServiceReferences. That is, the service with the highest ranking number must be called first.
        List<ServiceState<?>> sortedHookRefs = new ArrayList<ServiceState<?>>(hookRefs);
        Collections.reverse(sortedHookRefs);

        Collection<ServiceReference<?>> hookParam = new ArrayList<ServiceReference<?>>();
        for (ServiceState<?> aux : serviceStates)
            hookParam.add(ServiceStateImpl.getReferenceInternal(aux));

        // The hooks are obtained by the framework and released after the call, they are called with the context of the
        // bundle that searches the registry
        BundleContext context = bundle.getBundleContext();
        XBundle hookUser = getHookUser(bundle);
        hookParam = new RemoveOnlyCollection<ServiceReference<?>>(hookParam);
        for (ServiceState<?> hookRef : sortedHookRefs) {
            Object hook = getService(hookUser, hookRef);
            if (hook instanceof FindHook) {
                try {
                    ((FindHook) hook).find(context, clazz, filterStr, !checkAssignable, hookParam);
                } catch (Exception ex) {
                    LOGGER.warnErrorWhileCallingFindHook(ex, (FindHook) hook);
                } finally {
                    ungetService(hookUser, hookRef);
                }
            }
        }

        // The hooks are called for the system bundle context, but they cannot hide services from it
        if (bundle.getBundleId() == 0)
            return serviceStates;

        List<ServiceState<?>> result = new ArrayList<ServiceState<?>>();
        for (ServiceReference<?> aux : hookParam) {
            ServiceState<?> serviceState = ServiceStateImpl.assertServiceState(aux);
            result.add(serviceState);
        }

        return result;
    }

}