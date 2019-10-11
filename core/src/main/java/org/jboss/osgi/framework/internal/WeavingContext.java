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

import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
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
    private final List<HookRegistration> weavingHooks;
    private final List<String> weavingImports = new ArrayList<String>();
    private Map<String, ContextClass> wovenClasses = new HashMap<String, ContextClass>();

    static WeavingContext getCurrentContext() {
        return contextAssociation.get();
    }

    static WeavingContext create(UserBundleState hostState) {
        WeavingContext context = new WeavingContext(hostState);
        contextAssociation.set(context);
        return context;
    }

    private WeavingContext(UserBundleState hostState) {
        this.hostState = hostState;

        BundleManagerPlugin bundleManager = hostState.getBundleManager();
        BundleContext syscontext = bundleManager.getSystemContext();

        // Cleanup the blacklist
        for (Iterator<ServiceReference<WeavingHook>> iterator = blacklist.iterator(); iterator.hasNext();) {
            ServiceReference<WeavingHook> sref = iterator.next();
            if (syscontext.getService(sref) == null) {
                iterator.remove();
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
            if (blacklist.contains(sref) == false) {
                WeavingHook hook = syscontext.getService(sref);
                weavingHooks.add(new HookRegistration(sref, hook));
            }
        }
    }

    List<HookRegistration> getWeavingHooks() {
        return weavingHooks;
    }

    void blacklist(ServiceReference<WeavingHook> sref) {
        blacklist.add(sref);
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
        contextAssociation.remove();
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
        private boolean complete;
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
            return classfileBuffer;
        }

        @Override
        public void setBytes(byte[] newBytes) {
            assertNotComplete();
            this.classfileBuffer = newBytes;
        }

        @Override
        public List<String> getDynamicImports() {
            return complete ? Collections.unmodifiableList(weavingImports) : weavingImports;
        }

        @Override
        public boolean isWeavingComplete() {
            return complete;
        }

        void markComplete() {
            if (complete == false) {
                classfileBuffer = Arrays.copyOf(classfileBuffer, classfileBuffer.length);
                complete = true;
                state = TRANSFORMED;
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

        void setProtectionDomain(ProtectionDomain protectionDomain) {
            assertNotComplete();
            this.protectionDomain = protectionDomain;
        }

        @Override
        public Class<?> getDefinedClass() {
            return redefinedClass;
        }

        void setDefinedClass(Class<?> definedClass) {
            assertNotComplete();
            this.redefinedClass = definedClass;
        }

        @Override
        public BundleWiring getBundleWiring() {
            return hostState.adapt(BundleWiring.class);
        }

        @Override
        public int getState() {
            return state;
        }

        private void assertNotComplete() {
            if (complete)
                throw MESSAGES.illegalStateWeavingAlreadyComplete(className);
        }
    }
}