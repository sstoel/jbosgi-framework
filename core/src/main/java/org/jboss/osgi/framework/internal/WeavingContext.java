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

import static org.jboss.osgi.framework.FrameworkMessages.MESSAGES;

import java.security.ProtectionDomain;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.osgi.framework.spi.FrameworkEvents;
import org.jboss.osgi.metadata.OSGiMetaDataBuilder;
import org.jboss.osgi.resolver.XBundle;
import org.jboss.osgi.resolver.XBundleRevision;
import org.jboss.osgi.resolver.XBundleRevisionBuilderFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.hooks.weaving.WovenClassListener;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A context for the processing of registered {@link WeavingHook}s.
 *
 * @author thomas.diesler@jboss.com
 * @since 11-Feb-2013
 */
class WeavingContext {

    private static final ThreadLocal<WeavingContext> contextAssociation = new ThreadLocal<WeavingContext>();
    private static final Set<ServiceReference<WeavingHook>> blacklist = new HashSet<ServiceReference<WeavingHook>>();

    private final UserBundleState hostState;
    // The revision whose class loader defines the classes, null if not known
    private final XBundleRevision hostRev;
    private final WeavingContext parent;
    private final List<HookRegistration> weavingHooks;
    private final List<String> weavingImports = new DynamicImportList();
    private Map<String, ContextClass> wovenClasses = new HashMap<String, ContextClass>();

    static WeavingContext getCurrentContext() {
        return contextAssociation.get();
    }

    static WeavingContext create(UserBundleState hostState) {
        return create(hostState, null);
    }

    static WeavingContext create(UserBundleState hostState, XBundleRevision hostRev) {
        // Defining a class loads its super types on the same thread, so contexts can be nested
        WeavingContext context = new WeavingContext(hostState, hostRev, contextAssociation.get());
        contextAssociation.set(context);
        return context;
    }

    private WeavingContext(UserBundleState hostState, XBundleRevision hostRev, WeavingContext parent) {
        this.hostState = hostState;
        this.hostRev = hostRev;
        this.parent = parent;

        BundleManagerPlugin bundleManager = hostState.getBundleManager();
        BundleContext syscontext = bundleManager.getSystemContext();

        // Cleanup the blacklist: the hooks that are unregistered, also those of a framework that was stopped
        synchronized (blacklist) {
            for (Iterator<ServiceReference<WeavingHook>> iterator = blacklist.iterator(); iterator.hasNext();) {
                ServiceReference<WeavingHook> sref = iterator.next();
                if (ServiceStateImpl.assertServiceState(sref).isUnregistered()) {
                    iterator.remove();
                }
            }
        }

        // Find the registered {@link WeavingHook}
        Collection<ServiceReference<WeavingHook>> srefs = null;
        try {
            srefs = syscontext.getServiceReferences(WeavingHook.class, null);
        } catch (InvalidSyntaxException e) {
            // ignore
        }

        // Weaving Hook services that are lower in ranking will weave any of the changes of higher ranking Weaving Hook services.
        List<ServiceReference<WeavingHook>> sorted = new ArrayList<ServiceReference<WeavingHook>>(srefs);
        Collections.reverse(sorted);

        // Get the hook instances and associate them with the current thread
        weavingHooks = new ArrayList<HookRegistration>();
        for (ServiceReference<WeavingHook> sref : sorted) {
            boolean blacklisted;
            synchronized (blacklist) {
                blacklisted = blacklist.contains(sref);
            }
            if (blacklisted == false) {
                // A hook that cannot be obtained (its factory returned null, it was unregistered) is skipped
                WeavingHook hook = syscontext.getService(sref);
                if (hook != null)
                    weavingHooks.add(new HookRegistration(sref, hook));
            }
        }
    }

    List<HookRegistration> getWeavingHooks() {
        return weavingHooks;
    }

    void blacklist(ServiceReference<WeavingHook> sref) {
        synchronized (blacklist) {
            blacklist.add(sref);
        }
    }

    synchronized ContextClass createContextClass(String className, Class<?> redefinedClass, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        ContextClass contextClass = new ContextClass(className, redefinedClass, protectionDomain, classfileBuffer);
        wovenClasses.put(contextClass.getClassName(), contextClass);
        return contextClass;
    }

    synchronized ContextClass getContextClass(String className) {
        return wovenClasses.get(className.replace('/', '.'));
    }

    void close() {
        if (parent != null) {
            contextAssociation.set(parent);
        } else {
            contextAssociation.remove();
        }
    }

    static class HookRegistration {
        final WeavingHook hook;
        final ServiceReference<WeavingHook> sref;
        HookRegistration(ServiceReference<WeavingHook> sref, WeavingHook hook) {
            this.sref = sref;
            this.hook = hook;
        }
    }

    class ContextClass implements WovenClass {

        private final String className;
        private Class<?> redefinedClass;
        private ProtectionDomain protectionDomain;
        private boolean frozen;
        private byte[] classfileBuffer;
        private int state;

        ContextClass(String className, Class<?> redefinedClass, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            this.className = className.replace('/', '.');
            this.redefinedClass = redefinedClass;
            this.protectionDomain = protectionDomain;
            this.classfileBuffer = classfileBuffer;
            this.state = TRANSFORMING;
        }

        @Override
        public byte[] getBytes() {
            // After TRANSFORMING every call returns a new copy, the class is defined from the internal array
            return frozen ? classfileBuffer.clone() : classfileBuffer;
        }

        /**
         * The bytes the class is defined from
         */
        byte[] getDefineBytes() {
            return classfileBuffer;
        }

        @Override
        public void setBytes(byte[] newBytes) {
            if (newBytes == null)
                throw new NullPointerException("newBytes");
            assertNotFrozen();
            this.classfileBuffer = newBytes;
        }

        @Override
        public List<String> getDynamicImports() {
            return frozen ? Collections.unmodifiableList(weavingImports) : weavingImports;
        }

        @Override
        public boolean isWeavingComplete() {
            return state == DEFINED || state == DEFINE_FAILED || state == TRANSFORMING_FAILED;
        }

        /**
         * All weaving hooks have been called. The class bytes and dynamic imports can no longer be changed.
         */
        void markTransformed() {
            if (state == TRANSFORMING) {
                freeze();
                state = TRANSFORMED;
                notifyListeners();
            }
        }

        void markTransformingFailed() {
            if (state == TRANSFORMING) {
                freeze();
                state = TRANSFORMING_FAILED;
                notifyListeners();
            }
        }

        void markDefined(Class<?> definedClass) {
            if (state == TRANSFORMING || state == TRANSFORMED) {
                freeze();
                this.protectionDomain = definedClass.getProtectionDomain();
                this.redefinedClass = definedClass;
                state = DEFINED;
                notifyListeners();
            }
        }

        void markDefineFailed() {
            if (state == TRANSFORMING || state == TRANSFORMED) {
                freeze();
                state = DEFINE_FAILED;
                notifyListeners();
            }
        }

        private void freeze() {
            if (frozen == false) {
                classfileBuffer = Arrays.copyOf(classfileBuffer, classfileBuffer.length);
                frozen = true;
            }
        }

        private void notifyListeners() {
            BundleManagerPlugin bundleManager = hostState.getBundleManager();
            BundleContext syscontext = bundleManager.getSystemContext();
            Collection<ServiceReference<WovenClassListener>> srefs = null;
            try {
                srefs = syscontext.getServiceReferences(WovenClassListener.class, null);
            } catch (InvalidSyntaxException e) {
                // ignore
            }
            if (srefs == null)
                return;

            for (ServiceReference<WovenClassListener> sref : srefs) {
                WovenClassListener listener = syscontext.getService(sref);
                if (listener == null)
                    continue;
                try {
                    listener.modified(this);
                } catch (Throwable th) {
                    // A listener cannot fail the class load
                    FrameworkEvents frameworkEvents = hostState.getFrameworkState().getFrameworkEvents();
                    frameworkEvents.fireFrameworkEvent((XBundle) sref.getBundle(), FrameworkEvent.ERROR, th, (FrameworkListener[]) null);
                } finally {
                    syscontext.ungetService(sref);
                }
            }
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public ProtectionDomain getProtectionDomain() {
            return protectionDomain;
        }

        @Override
        public Class<?> getDefinedClass() {
            return redefinedClass;
        }

        @Override
        public BundleWiring getBundleWiring() {
            // The wiring of the revision that defines the class, also when the bundle was updated meanwhile
            if (hostRev != null)
                return hostRev.getWiring();
            return hostState.adapt(BundleWiring.class);
        }

        @Override
        public int getState() {
            return state;
        }

        private void assertNotFrozen() {
            if (frozen)
                throw MESSAGES.illegalStateWeavingAlreadyComplete(className);
        }
    }

    /**
     * The dynamic imports of the woven classes: a clause that is not a valid DynamicImport-Package clause is rejected
     * with an IllegalArgumentException when it is added (56.6.4.10)
     */
    private static final class DynamicImportList extends AbstractList<String> {

        private final List<String> delegate = new ArrayList<String>();

        @Override
        public String get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void add(int index, String element) {
            delegate.add(index, validate(element));
        }

        @Override
        public String set(int index, String element) {
            return delegate.set(index, validate(element));
        }

        @Override
        public String remove(int index) {
            return delegate.remove(index);
        }

        private static String validate(String importSpec) {
            if (importSpec == null)
                throw MESSAGES.illegalArgumentNull("importSpec");
            try {
                OSGiMetaDataBuilder mdbuilder = OSGiMetaDataBuilder.createBuilder("woven.class", Version.emptyVersion);
                mdbuilder.addDynamicImportPackages(importSpec);
                XBundleRevisionBuilderFactory.create().loadFrom(mdbuilder.getOSGiMetaData()).getResource();
            } catch (RuntimeException rte) {
                throw MESSAGES.illegalArgumentDynamicWeavingImport(rte, importSpec);
            }
            return importSpec;
        }
    }
}
