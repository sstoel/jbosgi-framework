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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
    import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.framework.spi.ServiceManager;
import org.jboss.osgi.framework.spi.ServiceState;
import org.jboss.osgi.metadata.CaseInsensitiveDictionary;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.spi.ConstantsHelper;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.dto.ServiceReferenceDTO;

/**
 * The service implementation.
 *
 * @author thomas.diesler@jboss.com
 * @author David Bosschaert
 * @since 29-Jun-2010
 */
@SuppressWarnings("rawtypes")
final class ServiceStateImpl<S> implements ServiceState<S> {

    private final ServiceManager serviceManager;
    private final XBundle ownerBundle;
    private final String[] classNames;
    private final long serviceId;
    private final ValueProvider<S> valueProvider;
    private final ServiceReference<S> reference;
    private ServiceRegistration<S> registration;
    // The registration that is passed to a prototype factory, also after the service is unregistered
    private final ServiceRegistration<S> factoryRegistration;
    private Set<XBundle> usingBundles;
    private Map<Long, ServiceFactoryHolder<S>> factoryValues;
    private Map<Long, Map<S, Integer>> prototypeValues;
    private Map<Long, XBundle> prototypeBundles;
    private boolean prototypeValuesReleased;
    // Only one thread unregisters the service, the others see it as unregistered
    private final AtomicBoolean unregistering = new AtomicBoolean();

    // The properties
    private CaseInsensitiveDictionary prevProperties;
    private CaseInsensitiveDictionary currProperties;

    @SuppressWarnings("unchecked")
    ServiceStateImpl(ServiceManager serviceManager, XBundle owner, long serviceId, String[] classNames, ValueProvider<S> valueProvider, Dictionary properties) {
        assert serviceManager != null : "Null serviceManager";
        assert owner != null : "Null owner";
        assert classNames != null && classNames.length > 0 : "Null clazzes";
        assert valueProvider != null : "Null valueProvider";

        this.serviceManager = serviceManager;
        this.ownerBundle = owner;
        this.serviceId = serviceId;
        this.valueProvider = valueProvider;
        this.classNames = classNames;

        if (!valueProvider.isFactoryValue() && !checkValidClassNames(owner, classNames, valueProvider.getValue()))
            throw MESSAGES.illegalArgumentInvalidObjectClass(Arrays.toString(classNames));

        if (properties == null)
            properties = new Hashtable();

        properties.put(Constants.SERVICE_ID, serviceId);
        properties.put(Constants.OBJECTCLASS, classNames);
        properties.put(Constants.SERVICE_BUNDLEID, owner.getBundleId());
        properties.put(Constants.SERVICE_SCOPE, getServiceScope());
        this.currProperties = new CaseInsensitiveDictionary(properties);

        // Create the {@link ServiceRegistration} and {@link ServiceReference}
        this.registration = new ServiceRegistrationWrapper(this);
        this.factoryRegistration = registration;
        this.reference = new ServiceReferenceWrapper(this);
    }

    static ServiceStateImpl assertServiceState(ServiceReference sref) {
        assert sref != null : "Null sref";
        if (sref instanceof ServiceReferenceWrapper) {
            sref = ((ServiceReferenceWrapper) sref).getServiceState();
        }
        return (ServiceStateImpl) sref;
    }

    @Override
    public long getServiceId() {
        return serviceId;
    }

    @Override
    public S getScopedValue(XBundle bundle) {

        // For non-factory services, return the value
        if (valueProvider.isFactoryValue() == false)
            return valueProvider.getValue();

        // Get the ServiceFactory value
        S result = null;
        try {
            if (factoryValues == null)
                factoryValues = new HashMap<Long, ServiceFactoryHolder<S>>();

            ServiceFactoryHolder<S> factoryHolder = getFactoryHolder(bundle);
            if (factoryHolder == null) {
                ServiceFactory factory = (ServiceFactory) valueProvider.getValue();
                factoryHolder = new ServiceFactoryHolder<S>(bundle, factory);
                factoryValues.put(bundle.getBundleId(), factoryHolder);
            }

            result = factoryHolder.getService();

            // If the service object returned by the ServiceFactory object is not an instanceof all the classes named
            // when the service was registered or the ServiceFactory object throws an exception,
            // null is returned and a Framework event of type {@link FrameworkEvent#ERROR}
            // containing a {@link ServiceException} describing the error is fired.
            if (result == null) {
                ServiceException sex = new ServiceException("Cannot get factory value", ServiceException.FACTORY_ERROR);
                serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, sex);
            }
        } catch (Throwable th) {
            ServiceException sex = new ServiceException("Cannot get factory value", ServiceException.FACTORY_EXCEPTION, th);
            serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, sex);
        }
        return result;
    }

    @Override
    public void ungetScopedValue(XBundle bundle) {
        if (valueProvider.isFactoryValue()) {
            ServiceFactoryHolder factoryHolder = getFactoryHolder(bundle);
            if (factoryHolder != null) {
                try {
                    factoryHolder.ungetService();
                } catch (RuntimeException rte) {
                    ServiceException sex = new ServiceException("Cannot unget factory value", ServiceException.FACTORY_EXCEPTION, rte);
                    serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.WARNING, sex);
                }
            }
        }
    }

    private String getServiceScope() {
        Object value = valueProvider.getValue();
        if (value instanceof PrototypeServiceFactory)
            return Constants.SCOPE_PROTOTYPE;
        if (valueProvider.isFactoryValue())
            return Constants.SCOPE_BUNDLE;
        return Constants.SCOPE_SINGLETON;
    }

    boolean isPrototypeScope() {
        return valueProvider.getValue() instanceof PrototypeServiceFactory;
    }

    /**
     * True if the given value is the service object that the bundle currently holds through {@link #getScopedValue(XBundle)}
     */
    boolean isScopedValueInUse(XBundle bundle, Object value) {
        if (valueProvider.isFactoryValue() == false)
            return value == valueProvider.getValue();

        synchronized (this) {
            ServiceFactoryHolder<S> factoryHolder = getFactoryHolder(bundle);
            return factoryHolder != null && factoryHolder.useCount.get() > 0 && factoryHolder.value == value;
        }
    }

    /**
     * Get a service object from the {@link PrototypeServiceFactory}
     *
     * The factory may return a service object that the bundle already holds. Service objects are therefore use counted.
     */
    @SuppressWarnings("unchecked")
    S getPrototypeValue(XBundle bundle) {
        PrototypeServiceFactory<S> factory = (PrototypeServiceFactory<S>) valueProvider.getValue();
        S result;
        try {
            result = factory.getService(bundle, factoryRegistration);
        } catch (Throwable th) {
            ServiceException sex = new ServiceException("Cannot get factory value", ServiceException.FACTORY_EXCEPTION, th);
            serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, sex);
            return null;
        }
        if (result == null || checkValidClassNames(ownerBundle, classNames, result) == false) {
            ServiceException sex = new ServiceException("Cannot get factory value", ServiceException.FACTORY_ERROR);
            serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.ERROR, sex);
            return null;
        }
        boolean unregistered = false;
        boolean heldValue = false;
        synchronized (this) {
            // The service may have been unregistered while the factory was called
            Map<S, Integer> values = prototypeValues != null ? prototypeValues.get(bundle.getBundleId()) : null;
            if (isUnregistered() || prototypeValuesReleased) {
                unregistered = true;
                heldValue = values != null && values.containsKey(result);
            } else {
                if (prototypeValues == null) {
                    prototypeValues = new HashMap<Long, Map<S, Integer>>();
                    prototypeBundles = new HashMap<Long, XBundle>();
                }

                if (values == null) {
                    values = new IdentityHashMap<S, Integer>();
                    prototypeValues.put(bundle.getBundleId(), values);
                    prototypeBundles.put(bundle.getBundleId(), bundle);
                }
                Integer useCount = values.get(result);
                values.put(result, useCount != null ? useCount + 1 : 1);
            }
        }
        if (unregistered) {
            if (heldValue == false)
                ungetPrototypeValueInternal(bundle, result);
            return null;
        }
        BundleServices.get(bundle).addPrototypeServiceInUse(this);
        addUsingBundle(bundle);
        return result;
    }

    /**
     * Release a service object that was obtained from {@link #getPrototypeValue(XBundle)}
     *
     * @return false if the given value is not a prototype service object held by the bundle
     */
    boolean ungetPrototypeValue(XBundle bundle, S value) {
        synchronized (this) {
            Map<S, Integer> values = prototypeValues != null ? prototypeValues.get(bundle.getBundleId()) : null;
            Integer useCount = values != null ? values.get(value) : null;
            if (useCount == null)
                return false;

            // The factory gets the service object back when the bundle no longer uses it
            if (useCount > 1) {
                values.put(value, useCount - 1);
                return true;
            }
            values.remove(value);
            if (values.isEmpty()) {
                prototypeValues.remove(bundle.getBundleId());
                prototypeBundles.remove(bundle.getBundleId());
            }
        }
        ungetPrototypeValueInternal(bundle, value);
        if (hasPrototypeValues(bundle) == false) {
            BundleServices bundleServices = BundleServices.get(bundle);
            bundleServices.removePrototypeServiceInUse(this);
            if (bundleServices.hasServiceUseCount(this) == false)
                removeUsingBundle(bundle);
        }
        return true;
    }

    /**
     * Release all prototype service objects held by the given bundle
     */
    void releasePrototypeValues(XBundle bundle) {
        Map<S, Integer> values;
        synchronized (this) {
            values = prototypeValues != null ? prototypeValues.remove(bundle.getBundleId()) : null;
            if (values != null)
                prototypeBundles.remove(bundle.getBundleId());
        }
        if (values != null) {
            for (S value : values.keySet())
                ungetPrototypeValueInternal(bundle, value);
        }
        BundleServices.get(bundle).removePrototypeServiceInUse(this);
    }

    /**
     * Release the prototype service objects of all bundles when the service is unregistered.
     *
     * A bundle can get a service object while the service is being unregistered, after this no bundle gets one.
     */
    void releaseAllPrototypeValues() {
        List<XBundle> bundles;
        synchronized (this) {
            prototypeValuesReleased = true;
            bundles = prototypeBundles != null ? new ArrayList<XBundle>(prototypeBundles.values()) : Collections.<XBundle> emptyList();
        }
        for (XBundle bundle : bundles) {
            releasePrototypeValues(bundle);
            if (BundleServices.get(bundle).hasServiceUseCount(this) == false)
                removeUsingBundle(bundle);
        }
    }

    boolean hasPrototypeValues(XBundle bundle) {
        synchronized (this) {
            return prototypeValues != null && prototypeValues.containsKey(bundle.getBundleId());
        }
    }

    @SuppressWarnings("unchecked")
    private void ungetPrototypeValueInternal(XBundle bundle, S value) {
        PrototypeServiceFactory<S> factory = (PrototypeServiceFactory<S>) valueProvider.getValue();
        try {
            factory.ungetService(bundle, factoryRegistration, value);
        } catch (RuntimeException rte) {
            ServiceException sex = new ServiceException("Cannot unget factory value", ServiceException.FACTORY_EXCEPTION, rte);
            serviceManager.fireFrameworkEvent(bundle, FrameworkEvent.WARNING, sex);
        }
    }

    private ServiceFactoryHolder<S> getFactoryHolder(XBundle bundle) {
        return factoryValues != null ? factoryValues.get(bundle.getBundleId()) : null;
    }

    @Override
    public ServiceRegistration<S> getRegistration() {
        return registration;
    }

    @Override
    public List<String> getClassNames() {
        return Arrays.asList(classNames);
    }

    @Override
    public ServiceReference<S> getReference() {
        assertNotUnregistered();
        return reference;
    }

    @Override
    public void unregister() {
        assertNotUnregistered();
        if (unregistering.compareAndSet(false, true) == false)
            throw MESSAGES.illegalStateServiceUnregistered(this);
        unregisterService();
    }

    @Override
    public void unregisterInternal() {
        if (unregistering.compareAndSet(false, true))
            unregisterService();
    }

    private void unregisterService() {
        serviceManager.unregisterService(this);
        usingBundles = null;
        registration = null;
    }

    @Override
    public Object getProperty(String key) {
        if (key == null)
            return null;
        return currProperties.get(key);
    }

    @Override
    public String[] getPropertyKeys() {
        List<String> result = new ArrayList<String>();
        if (currProperties != null) {
            Enumeration<String> keys = currProperties.keys();
            while (keys.hasMoreElements())
                result.add(keys.nextElement());
        }
        return result.toArray(new String[result.size()]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Dictionary<String, Object> getProperties() {
        return new MutableServiceProperties(currProperties);
    }

    @Override
    @SuppressWarnings({ "unchecked" })
    public void setProperties(Dictionary properties) {
        assertNotUnregistered();

        // Remember the previous properties for a potential
        // delivery of the MODIFIED_ENDMATCH event
        prevProperties = currProperties;

        if (properties == null)
            properties = new Hashtable();

        properties.put(Constants.SERVICE_ID, currProperties.get(Constants.SERVICE_ID));
        properties.put(Constants.OBJECTCLASS, currProperties.get(Constants.OBJECTCLASS));
        properties.put(Constants.SERVICE_BUNDLEID, currProperties.get(Constants.SERVICE_BUNDLEID));
        properties.put(Constants.SERVICE_SCOPE, currProperties.get(Constants.SERVICE_SCOPE));
        currProperties = new CaseInsensitiveDictionary(properties);

        // This event is synchronously delivered after the service properties have been modified.
        serviceManager.fireServiceEvent(ownerBundle, ServiceEvent.MODIFIED, this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Dictionary<String, ?> getPreviousProperties() {
        return prevProperties;
    }

    @Override
    public XBundle getServiceOwner() {
        return ownerBundle;
    }

    @Override
    public Bundle getBundle() {
        if (isUnregistered())
            return null;

        return ownerBundle;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A> A adapt(Class<A> type) {
        if (type == ServiceReferenceDTO.class)
            return (A) DTOFactory.adaptServiceReference(this);
        return null;
    }

    @Override
    public void addUsingBundle(XBundle bundleState) {
        synchronized (this) {
            if (usingBundles == null)
                usingBundles = new HashSet<XBundle>();

            usingBundles.add(bundleState);
        }
    }

    @Override
    public void removeUsingBundle(XBundle bundle) {
        synchronized (this) {
            if (usingBundles != null)
                usingBundles.remove(bundle);
        }
    }

    @Override
    public Set<XBundle> getUsingBundlesInternal() {
        synchronized (this) {
            if (usingBundles == null)
                return Collections.emptySet();

            // Return an unmodifieable snapshot of the set
            return Collections.unmodifiableSet(new HashSet<XBundle>(usingBundles));
        }
    }

    @Override
    public Bundle[] getUsingBundles() {
        synchronized (this) {
            // Null is returned when no bundles are using the service
            if (usingBundles == null || usingBundles.isEmpty())
                return null;

            Set<Bundle> bundles = new HashSet<Bundle>();
            for (XBundle aux : usingBundles)
                bundles.add(aux);

            return bundles.toArray(new Bundle[bundles.size()]);
        }
    }

    @Override
    public boolean isAssignableTo(Bundle bundle, String className) {
        if (bundle == null)
            throw MESSAGES.illegalArgumentNull("bundle");
        if (className == null)
            throw MESSAGES.illegalArgumentNull("className");

        if (bundle == ownerBundle || className.startsWith("java."))
            return true;

        int bundleState = bundle.getState();
        if (bundleState == Bundle.UNINSTALLED)
            return false;

        XBundleRevision bundleRev = ((XBundle) bundle).getBundleRevision();
        ClassLoader bundleClassLoader = bundleRev.getModuleClassLoader();
        if (bundleClassLoader == null) {
            LOGGER.infof("NO CLASSLOADER [%s,%s] for: %s", bundle, ConstantsHelper.bundleState(bundleState), className);
            return false;
        }

        FallbackLoader fallbackLoader = null;
        if (bundleRev instanceof HostBundleRevision) {
            fallbackLoader = ((HostBundleRevision) bundleRev).getFallbackLoader();
        }

        Class<?> targetClass;
        try {
            if (fallbackLoader != null) {
                try {
                    fallbackLoader.lockFallbackLoader();
                    fallbackLoader.setEnabled(false);
                    targetClass = bundleClassLoader.loadClass(className);
                } finally {
                    fallbackLoader.setEnabled(true);
                    fallbackLoader.unlockFallbackLoader();
                }
            } else {
                targetClass = bundleClassLoader.loadClass(className);
            }
        } catch (ClassNotFoundException ex) {
            // If the requesting bundle does not have a wire to the
            // service package it cannot be constraint on that package.
            LOGGER.tracef("Requesting bundle [%s] cannot load class: %s", bundle, className);
            return true;
        }

        XBundleRevision ownerRev = ownerBundle.getBundleRevision();
        ClassLoader ownerClassLoader = ownerRev.getModuleClassLoader();
        if (ownerClassLoader == null) {
            LOGGER.tracef("Registrant bundle [%s] has no class loader for: %s", ownerBundle, className);
            return true;
        }

        // For the bundle that registered the service referenced by this ServiceReference (registrant bundle);
        // find the source for the package. If no source is found then return true if the registrant bundle
        // is equal to the specified bundle; otherwise return false
        Class<?> serviceClass;
        try {
            serviceClass = ownerClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            LOGGER.tracef("Registrant bundle [%s] cannot load class: %s", ownerBundle, className);
            return isServiceObjectAssignableTo(targetClass, className);
        }

        // If the package source of the registrant bundle is equal to the package source of the specified bundle
        // then return true; otherwise return false.
        if (targetClass != serviceClass) {
            LOGGER.tracef("Not assignable: %s", className);
            return false;
        }

        return true;
    }

    /**
     * The registrant bundle has no source for the package. The package source is then determined by the service object.
     */
    private boolean isServiceObjectAssignableTo(Class<?> targetClass, String className) {
        Object value = isUnregistered() ? null : valueProvider.getValue();
        if (value == null)
            return true;

        // A service factory that does not come from the registrant bundle can provide anything
        if (value instanceof ServiceFactory) {
            Bundle factoryBundle = FrameworkUtil.getBundle(value.getClass());
            if (factoryBundle != ownerBundle)
                return true;
        }

        Class<?> serviceClass = findClassBySource(value.getClass(), className, new HashSet<Class<?>>());
        if (serviceClass != targetClass) {
            LOGGER.tracef("Not assignable: %s", className);
            return false;
        }
        return true;
    }

    /**
     * Find the class with the given name as it is seen by the class or one of its super types.
     */
    private static Class<?> findClassBySource(Class<?> clazz, String className, Set<Class<?>> visited) {
        if (clazz == null || visited.add(clazz) == false)
            return null;
        if (clazz.getName().equals(className))
            return clazz;

        ClassLoader loader = clazz.getClassLoader();
        if (loader != null) {
            try {
                return loader.loadClass(className);
            } catch (ClassNotFoundException ex) {
                // not a source for the package
            } catch (LinkageError ex) {
                // not a source for the package
            }
        }
        Class<?> result = findClassBySource(clazz.getSuperclass(), className, visited);
        for (Class<?> iface : clazz.getInterfaces()) {
            if (result != null)
                break;
            result = findClassBySource(iface, className, visited);
        }
        return result;
    }

    @Override
    public int compareTo(Object sref) {
        if (sref instanceof ServiceReference == false)
            throw MESSAGES.illegalArgumentInvalidServiceRef(sref);

        Comparator<ServiceReference<?>> comparator = ServiceReferenceComparator.getInstance();
        return comparator.compare(this, (ServiceReference) sref);
    }

    @Override
    public int getServiceRanking() {
        Object prop = getProperty(Constants.SERVICE_RANKING);
        if (prop instanceof Integer == false)
            return 0;

        return ((Integer) prop).intValue();
    }

    @Override
    public boolean isUnregistered() {
        return registration == null;
    }

    private void assertNotUnregistered() {
        if (isUnregistered())
            throw MESSAGES.illegalStateServiceUnregistered(this);
    }

    private boolean checkValidClassNames(XBundle bundleState, String[] classNames, Object value) {
        assert bundleState != null : "Null bundleState";
        assert classNames != null && classNames.length > 0 : "Null service classes";
        assert value != null : "Null value";

        if (value instanceof ServiceFactory)
            return true;

        boolean result = true;
        for (String className : classNames) {
            if (className == null) {
                result = false;
                break;
            }
            try {
                Class<?> valueClass = value.getClass();
                // Use Class.forName with classloader argument as the classloader
                // might be null (for JRE provided types).
                Class<?> clazz = Class.forName(className, false, valueClass.getClassLoader());
                if (clazz.isAssignableFrom(valueClass) == false) {
                    LOGGER.errorServiceNotAssignable(className, clazz.getClassLoader(), valueClass.getName(), valueClass.getClassLoader());
                    result = false;
                    break;
                }
            } catch (ClassNotFoundException ex) {
                LOGGER.errorCannotLoadService(className, bundleState);
                result = false;
                break;
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String toString() {
        Hashtable<String, Object> props = new Hashtable<String, Object>(currProperties);
        String[] classes = (String[]) props.get(Constants.OBJECTCLASS);
        props.put(Constants.OBJECTCLASS, Arrays.asList(classes));
        return "ServiceState" + props;
    }

    class ServiceFactoryHolder<T> {

        ServiceFactory factory;
        XBundle bundle;
        AtomicInteger useCount;
        T value;

        ServiceFactoryHolder(XBundle bundle, ServiceFactory factory) {
            this.bundle = bundle;
            this.factory = factory;
            this.useCount = new AtomicInteger();
        }

        @SuppressWarnings("unchecked")
        T getService() {
            // Multiple calls to getService() return the same value
            if (useCount.get() == 0) {
                // The Framework must not allow this method to be concurrently called for the same bundle
                synchronized (bundle) {
                    T retValue = (T) factory.getService(bundle, getRegistration());
                    if (retValue == null)
                        return null;

                    // The Framework will check if the returned service object is an instance of all the
                    // classes named when the service was registered. If not, then null is returned to the bundle.
                    if (checkValidClassNames(ownerBundle, (String[]) getProperty(Constants.OBJECTCLASS), retValue) == false)
                        return null;

                    value = retValue;
                }
            }

            useCount.incrementAndGet();
            return value;
        }

        @SuppressWarnings("unchecked")
        void ungetService() {
            if (useCount.get() == 0)
                return;

            // Call unget on the factory when done
            if (useCount.decrementAndGet() == 0) {
                synchronized (bundle) {
                    factory.ungetService(bundle, getRegistration(), value);
                    value = null;
                }
            }
        }
    }
}
