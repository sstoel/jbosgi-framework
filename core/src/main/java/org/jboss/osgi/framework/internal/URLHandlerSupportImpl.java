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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ContentHandler;
import java.net.ContentHandlerFactory;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.jboss.osgi.framework.spi.URLHandlerSupport;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerSetter;
import org.osgi.util.tracker.ServiceTracker;

/**
 * This plugin provides OSGi URL handler support as per the specification.
 * The interface is through the java.net.URL class and the OSGi Service Registry.
 *
 * @author <a href="david@redhat.com">David Bosschaert</a>
 * @author Thomas.Diesler@jboss.com
 * @since 10-Jan-2011
 */
final class URLHandlerSupportImpl implements URLHandlerSupport {

    private final BundleManagerPlugin bundleManager;
    private ServiceTracker<URLStreamHandlerService, URLStreamHandlerService> streamServiceTracker;
    private ServiceTracker<ContentHandler, ContentHandler> contentServiceTracker;
    private final Map<String, Boolean> builtinContentHandlers = new ConcurrentHashMap<String, Boolean>();
    private ServiceRegistration<URLStreamHandlerService> registration;

    private static OSGiContentHandlerFactoryDelegate contentHandlerDelegate;
    private static OSGiStreamHandlerFactoryDelegate streamHandlerDelegate;
    // The URL stream handler factory of the JVM when it is the one of the frameworks of another class loader: the
    // frameworks of this class loader register with it
    private static BiConsumer<String, URLStreamHandlerFactory> jvmFactoryRegistry;
    // The URL handler support of the running framework instances, the most recently started last. The factories of the
    // JVM can only be set once, they are shared by all frameworks
    private static final List<URLHandlerSupportImpl> runningInstances = new ArrayList<URLHandlerSupportImpl>();

    // The handler services of this framework, only these are removed when it stops
    private final Set<ServiceReference<URLStreamHandlerService>> streamHandlerRefs = ConcurrentHashMap.newKeySet();
    private final Set<ServiceReference<ContentHandler>> contentHandlerRefs = ConcurrentHashMap.newKeySet();

    static void initURLHandlerSupport() {
    	if (streamHandlerDelegate == null) {
            streamHandlerDelegate = new OSGiStreamHandlerFactoryDelegate();
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    try {
                        URL.setURLStreamHandlerFactory(streamHandlerDelegate);
                    } catch (Throwable th) {
                        // [MODULES-44] Provide an API that allows adding of URLStreamHandlerFactories
                        LOGGER.debugf("Unable to set the URLStreamHandlerFactory");
                        jvmFactoryRegistry = getForeignFrameworkFactory();
                    }
                    return null;
                }
            });
    	}
    	if (contentHandlerDelegate == null) {
            contentHandlerDelegate = new OSGiContentHandlerFactoryDelegate();
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    try {
                        URLConnection.setContentHandlerFactory(contentHandlerDelegate);
                    } catch (Throwable th) {
                        // [MODULES-44] Provide an API that allows adding of URLStreamHandlerFactories
                        LOGGER.debugf("Unable to set the ContentHandlerFactory");
                    }
                    return null;
                }
            });
    	}
    }

    /**
     * The URL stream handler factory of the JVM if it is the one of the frameworks of another class loader, which
     * accepts the registration of the frameworks of this class loader
     */
    @SuppressWarnings("unchecked")
    private static BiConsumer<String, URLStreamHandlerFactory> getForeignFrameworkFactory() {
        try {
            Object factory = TrustedLookup.get().findStaticGetter(URL.class, "factory", URLStreamHandlerFactory.class).invoke();
            if (factory instanceof BiConsumer && factory.getClass().getName().equals(OSGiStreamHandlerFactoryDelegate.class.getName()))
                return (BiConsumer<String, URLStreamHandlerFactory>) factory;
        } catch (Throwable th) {
            LOGGER.debugf(th, "Cannot obtain the URLStreamHandlerFactory of the JVM");
        }
        return null;
    }

    public URLHandlerSupportImpl(BundleManagerPlugin bundleManager) {
        this.bundleManager = bundleManager;
    }

    @Override
    public void start(BundleContext systemContext) {
        synchronized (runningInstances) {
            runningInstances.remove(this);
            runningInstances.add(this);
            setDelegateFactories(this);
        }
        if (jvmFactoryRegistry != null)
            jvmFactoryRegistry.accept(RevisionContent.getFrameworkPrefix(bundleManager), streamHandlerDelegate);
        registerStreamHandlerService();
        setupStreamHandlerTracker(systemContext);
        setupContentHandlerTracker(systemContext);
    }

    @Override
    public void stop() {
        // Only the handlers of this framework go, the other frameworks in the JVM keep theirs
        if (streamServiceTracker != null)
            streamServiceTracker.close();
        if (contentServiceTracker != null)
            contentServiceTracker.close();
        for (ServiceReference<URLStreamHandlerService> ref : streamHandlerRefs)
            streamHandlerDelegate.removeHandler(ref);
        for (ServiceReference<ContentHandler> ref : contentHandlerRefs)
            contentHandlerDelegate.removeHandler(ref);
        streamHandlerRefs.clear();
        contentHandlerRefs.clear();
        try {
            registration.unregister();
        } catch (IllegalStateException ex) {
            // already unregistered
        }
        if (jvmFactoryRegistry != null)
            jvmFactoryRegistry.accept(RevisionContent.getFrameworkPrefix(bundleManager), null);
        synchronized (runningInstances) {
            runningInstances.remove(this);
            setDelegateFactories(runningInstances.isEmpty() ? null : runningInstances.get(runningInstances.size() - 1));
        }
    }

    private static void setDelegateFactories(URLHandlerSupportImpl support) {
        OSGiStreamHandlerFactoryService.setDelegateFactory(support);
        streamHandlerDelegate.setDelegateFactory(support != null ? new OSGiStreamHandlerFactory(support) : null);
        contentHandlerDelegate.setDelegateFactory(support != null ? new OSGiContentHandlerFactory(support) : null);
    }

    private void registerStreamHandlerService() {
        // Register the 'bundle' protocol
        Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(URLConstants.URL_HANDLER_PROTOCOL, BundleProtocolHandler.PROTOCOL_NAME);
        BundleProtocolHandler service = new BundleProtocolHandler(bundleManager);
        BundleContext systemContext = bundleManager.getSystemBundle().getBundleContext();
        registration = systemContext.registerService(URLStreamHandlerService.class, service, props);
    }

    private void setupContentHandlerTracker(BundleContext systemContext) {
        contentServiceTracker = new ServiceTracker<ContentHandler, ContentHandler>(systemContext, ContentHandler.class, null) {

            @Override
            public ContentHandler addingService(ServiceReference<ContentHandler> reference) {
                ContentHandler service = super.addingService(reference);
                String[] mimeTypes = parseServiceProperty(reference.getProperty(URLConstants.URL_CONTENT_MIMETYPE));
                if (mimeTypes != null && service instanceof ContentHandler) {
                    LOGGER.debugf("Adding content handler '%s' for: %s", service, Arrays.asList(mimeTypes));
                    contentHandlerRefs.add(reference);
                    for (String mimeType : mimeTypes) {
                        contentHandlerDelegate.addHandler(mimeType, reference);
                    }
                }
                return service;
            }

            @Override
            public void modifiedService(ServiceReference<ContentHandler> reference, ContentHandler service) {
                removedService(reference, service);
                addingService(reference);
            }

            @Override
            public void removedService(ServiceReference<ContentHandler> reference, ContentHandler service) {
                super.removedService(reference, service);
                contentHandlerRefs.remove(reference);
                contentHandlerDelegate.removeHandler(reference);
            }
        };
        contentServiceTracker.open();
    }

    private void setupStreamHandlerTracker(BundleContext systemContext) {
        streamServiceTracker = new ServiceTracker<URLStreamHandlerService, URLStreamHandlerService>(systemContext, URLStreamHandlerService.class, null) {

            @Override
            public URLStreamHandlerService addingService(ServiceReference<URLStreamHandlerService> reference) {
                URLStreamHandlerService service = super.addingService(reference);
                String[] protocols = parseServiceProperty(reference.getProperty(URLConstants.URL_HANDLER_PROTOCOL));
                if (protocols != null && service instanceof URLStreamHandlerService) {
                    LOGGER.tracef("Adding stream handler '%s' for: %s", service, Arrays.asList(protocols));
                    streamHandlerRefs.add(reference);
                    for (String protocol : protocols) {
                        streamHandlerDelegate.addHandler(protocol, reference);
                    }
                }
                return service;
            }

            @Override
            public void modifiedService(ServiceReference<URLStreamHandlerService> reference, URLStreamHandlerService service) {
                removedService(reference, service);
                addingService(reference);
            }

            @Override
            public void removedService(ServiceReference<URLStreamHandlerService> reference, URLStreamHandlerService service) {
                super.removedService(reference, service);
                streamHandlerRefs.remove(reference);
                streamHandlerDelegate.removeHandler(reference);
            }
        };
        streamServiceTracker.open();
    }

    /**
     * Creates a new <code>URLStreamHandler</code> instance with the specified protocol.
     *
     * @see java.net.URLStreamHandler
     */
    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        List<ServiceReference<URLStreamHandlerService>> refList = streamHandlerDelegate.getStreamHandlers(protocol);
        if (refList == null || refList.isEmpty())
            return null;

        return new URLStreamHandlerProxy(protocol);
    }

    /**
     * Creates a new <code>ContentHandler</code> to read an object from a <code>URLStreamHandler</code>.
     *
     * @see java.net.ContentHandler
     * @see java.net.URLStreamHandler
     */
    @Override
    public ContentHandler createContentHandler(final String mimetype) {
        // The content handlers of the JVM cannot be overridden
        if (hasBuiltinContentHandler(mimetype))
            return null;

        // The JVM caches its default content handler when this factory returns null and does not ask again.
        // Return a handler that finds the registered service on every call instead.
        return new ContentHandler() {

            @Override
            public Object getContent(URLConnection urlc) throws IOException {
                ServiceReference<ContentHandler> ref = contentHandlerDelegate.getContentHandler(mimetype);
                BundleContext context = getServiceContext(ref);
                ContentHandler handler = context != null ? context.getService(ref) : null;
                if (handler == null)
                    return urlc.getInputStream();
                try {
                    return handler.getContent(urlc);
                } finally {
                    context.ungetService(ref);
                }
            }

            @Override
            @SuppressWarnings("rawtypes")
            public Object getContent(URLConnection urlc, Class[] classes) throws IOException {
                ServiceReference<ContentHandler> ref = contentHandlerDelegate.getContentHandler(mimetype);
                BundleContext context = getServiceContext(ref);
                ContentHandler handler = context != null ? context.getService(ref) : null;
                if (handler == null)
                    return super.getContent(urlc, classes);
                try {
                    return handler.getContent(urlc, classes);
                } finally {
                    context.ungetService(ref);
                }
            }
        };
    }

    private BundleContext getServiceContext(ServiceReference<ContentHandler> ref) {
        Bundle bundle = ref != null ? ref.getBundle() : null;
        return bundle != null ? bundle.getBundleContext() : null;
    }

    /**
     * True if the JVM provides a content handler for the given mime type
     */
    private boolean hasBuiltinContentHandler(String mimetype) {
        Boolean result = builtinContentHandlers.get(mimetype);
        if (result == null) {
            result = Boolean.FALSE;

            // Content handler factories that are provided by the JVM modules
            try {
                for (ContentHandlerFactory factory : ServiceLoader.load(ContentHandlerFactory.class, ClassLoader.getSystemClassLoader())) {
                    if (factory.createContentHandler(mimetype) != null) {
                        result = Boolean.TRUE;
                        break;
                    }
                }
            } catch (RuntimeException | ServiceConfigurationError ex) {
                LOGGER.debugf(ex, "Cannot load content handler factories");
            }

            // Content handler classes that follow the package naming convention
            if (!result) {
                StringBuilder typeName = new StringBuilder();
                for (char ch : mimetype.toLowerCase(Locale.ENGLISH).toCharArray())
                    typeName.append(ch == '/' ? '.' : Character.isLetterOrDigit(ch) ? ch : '_');

                String packages = SecurityActions.getSystemProperty("java.content.handler.pkgs", "");
                for (String packageName : (packages + "|sun.net.www.content").split("\\|")) {
                    if (packageName.trim().isEmpty())
                        continue;
                    try {
                        Class.forName(packageName.trim() + "." + typeName, false, ClassLoader.getSystemClassLoader());
                        result = Boolean.TRUE;
                        break;
                    } catch (ClassNotFoundException | LinkageError ex) {
                        // ignore
                    }
                }
            }
            builtinContentHandlers.put(mimetype, result);
        }
        return result;
    }

    /**
     * Parses a service registration property with a value which can be of type String or String [].
     *
     * @param prop The property value.
     * @return All the values found in a String [] or null of the property doesn't comply.
     */
    private String[] parseServiceProperty(Object prop) {
        if (prop == null)
            return null;

        if (prop instanceof String)
            return new String[] { (String) prop };

        if (prop instanceof String[])
            return (String[]) prop;

        return null;
    }

    static final class URLStreamHandlerProxy extends URLStreamHandler implements URLStreamHandlerSetter {

        // The JVM caches this proxy for the protocol, it finds the current handler services on every call. These are
        // maintained by the service trackers of the running frameworks, the top element is used
        private final String protocol;

        URLStreamHandlerProxy(String protocol) {
            this.protocol = protocol;
        }

        private boolean hasOwnHandler() {
            List<ServiceReference<URLStreamHandlerService>> refs = streamHandlerDelegate.getStreamHandlers(protocol);
            return refs != null && refs.isEmpty() == false;
        }

        /**
         * The handler of a framework of another class loader for the URL: the framework that created a bundle URL, or
         * any of them when the frameworks of this class loader have no handler for the protocol
         */
        private URLStreamHandler getForeignHandler(URL url) {
            String prefix = RevisionContent.getFrameworkPrefix(url.getHost());
            URLStreamHandlerFactory factory = streamHandlerDelegate.getForeignFactory(prefix);
            if (factory != null)
                return factory.createURLStreamHandler(protocol);
            if (hasOwnHandler())
                return null;
            for (URLStreamHandlerFactory aux : streamHandlerDelegate.getForeignFactories()) {
                URLStreamHandler handler = aux.createURLStreamHandler(protocol);
                if (handler != null)
                    return handler;
            }
            return null;
        }

        @Override
        public void setURL(URL u, String protocol, String host, int port, String authority, String userInfo, String path, String query, String ref) {
            // Made public to implement URLStreamHandlerSetter
            super.setURL(u, protocol, host, port, authority, userInfo, path, query, ref);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void setURL(URL u, String protocol, String host, int port, String file, String ref) {
            // Made public to implement URLStreamHandlerSetter
            super.setURL(u, protocol, host, port, file, ref);
        }

        @Override
        protected void parseURL(URL u, String spec, int start, int limit) {
            // Without a handler of a framework of this class loader the URL is parsed as usual when another class loader
            // has a handler for the protocol, otherwise the protocol is unknown
            if (hasOwnHandler() || hasForeignHandler() == false)
                getHandlerService().parseURL(this, u, spec, start, limit);
            else
                super.parseURL(u, spec, start, limit);
        }

        private boolean hasForeignHandler() {
            for (URLStreamHandlerFactory factory : streamHandlerDelegate.getForeignFactories()) {
                if (factory.createURLStreamHandler(protocol) != null)
                    return true;
            }
            return false;
        }

        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            URLStreamHandler foreign = getForeignHandler(u);
            if (foreign != null)
                return new URL(null, u.toExternalForm(), foreign).openConnection();
            return getHandlerService(u).openConnection(u);
        }

        @Override
        protected String toExternalForm(URL u) {
            return hasOwnHandler() ? getHandlerService(u).toExternalForm(u) : super.toExternalForm(u);
        }

        @Override
        protected URLConnection openConnection(URL u, Proxy p) throws IOException {
            URLStreamHandler foreign = getForeignHandler(u);
            if (foreign != null)
                return new URL(null, u.toExternalForm(), foreign).openConnection(p);
            URLStreamHandlerService handler = getHandlerService(u);
            try {
                Method method = handler.getClass().getDeclaredMethod("openConnection", URL.class, Proxy.class);
                return (URLConnection) method.invoke(handler, u, p);
            } catch (Throwable th) {
                if (th instanceof InvocationTargetException)
                    th = ((InvocationTargetException)th).getTargetException();
                throw MESSAGES.cannotOpenConnectionOnHandler(th, handler);
            }
        }

        @Override
        protected int getDefaultPort() {
            return hasOwnHandler() ? getHandlerService().getDefaultPort() : super.getDefaultPort();
        }

        @Override
        protected boolean equals(URL u1, URL u2) {
            return hasOwnHandler() ? getHandlerService().equals(u1, u2) : super.equals(u1, u2);
        }

        @Override
        protected int hashCode(URL u) {
            return hasOwnHandler() ? getHandlerService().hashCode(u) : super.hashCode(u);
        }

        @Override
        protected boolean sameFile(URL u1, URL u2) {
            return hasOwnHandler() ? getHandlerService().sameFile(u1, u2) : super.sameFile(u1, u2);
        }

        @Override
        protected synchronized InetAddress getHostAddress(URL u) {
            return hasOwnHandler() ? getHandlerService().getHostAddress(u) : super.getHostAddress(u);
        }

        @Override
        protected boolean hostsEqual(URL u1, URL u2) {
            return hasOwnHandler() ? getHandlerService().hostsEqual(u1, u2) : super.hostsEqual(u1, u2);
        }

        private URLStreamHandlerService getHandlerService() {
            return getHandlerService(null);
        }

        /**
         * The handler service for the URL: a bundle URL is handled by the 'bundle' handler of the framework that
         * created it, its host starts with the prefix of that framework
         */
        private URLStreamHandlerService getHandlerService(URL url) {
            List<ServiceReference<URLStreamHandlerService>> serviceReferences = streamHandlerDelegate.getStreamHandlers(protocol);
            if (serviceReferences == null || serviceReferences.isEmpty())
                throw MESSAGES.illegalStateNoStreamHandlersForProtocol(protocol);
            ServiceReference<URLStreamHandlerService> ref = null;
            String host = url != null ? url.getHost() : null;
            if (BundleProtocolHandler.PROTOCOL_NAME.equals(protocol) && host != null) {
                for (ServiceReference<URLStreamHandlerService> aux : serviceReferences) {
                    Bundle owner = aux.getBundle();
                    if (owner instanceof AbstractBundleState && host.startsWith(RevisionContent.getFrameworkPrefix(((AbstractBundleState<?>) owner).getBundleManager()))) {
                        ref = aux;
                        break;
                    }
                }
            }
            if (ref == null)
                ref = serviceReferences.get(0);
            URLStreamHandlerService service = ref.getBundle().getBundleContext().getService(ref);
            return service;
        }
    }
}
