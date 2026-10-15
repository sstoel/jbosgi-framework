package org.jboss.test.osgi.framework.issues;
/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2010 - 2026 JBoss by Red Hat
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


import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.service.ListenerHook;

/**
 * A {@code ListenerHook} registered as a {@code ServiceFactory} (e.g. a Declarative Services delayed component, or any
 * bundle-scoped hook) never gets the initial {@code added()} call with the service listeners that were added before
 * it was registered; a hook registered as a plain object does.
 *
 * <h3>Severity</h3>
 * major. ListenerHooks exist to learn which services bundles are waiting for (Remote Service Admin topology managers,
 * on-demand service providers). A hook provided by a DS component with the default (delayed) activation is
 * registered as a ServiceFactory; it only learns about listeners added after its registration and never provides
 * the services that already-waiting bundles (ServiceTrackers opened earlier) need.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.ServiceManagerImpl.registerService}.
 *
 * <h3>Found by</h3>
 * static workstream: Error Prone/SpotBugs triage of the listener bookkeeping (FrameworkEventsImpl
 * getServiceListenerInfos iterates the listener lists without the lock that guards them; reading its caller
 * ServiceManagerImpl.registerService showed the {@code instanceof} test on the raw registration object); confirmed
 * with an Equinox/Felix differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8 55.9.5.1 {@code ListenerHook.added(Collection)}
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.servicehooks.html): "This method will be called as
 * service listeners are added while this hook is registered. Also, immediately after registration of this hook, this
 * method will be called to provide the current collection of service listeners which had been added prior to the hook
 * being registered."<br>
 * {@code BundleContext.registerService(String[], Object, Dictionary)} javadoc: "A bundle can register a service object
 * that implements the ServiceFactory interface to have more flexibility in providing service objects to other
 * bundles." (hooks are services; nothing restricts them to plain objects).
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:107-109} (the initial listener
 * snapshot is only taken {@code if (serviceValue instanceof ListenerHook)}) and lines 142-146 (only then
 * {@code ((ListenerHook) serviceValue).added(listenerInfos)} is called). A ServiceFactory is not an instance of
 * ListenerHook, so for it neither happens; later {@code added()} calls go through
 * {@code FrameworkEventsImpl.getServiceListenerHooks()}, which obtains the hook with {@code getService} and works.
 * Since 2011 (Thomas Diesler, 3c8ccc02); identical on WF41.
 *
 * <h3>Root cause</h3>
 * The ListenerHook is detected by the type of the registered object instead of by the registered class name
 * ({@code org.osgi.framework.hooks.service.ListenerHook} in objectClass), and called directly instead of being
 * obtained through the service registry.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=ListenerHookFactoryNotInitializedIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: A ListenerHook registered as ServiceFactory must get added() with the existing listeners
 * right after its registration (55.9.5.1); expected a listener with filter (objectClass=com.acme.static05.Foo), got []
 * </pre>
 *
 * <h3>Expected</h3>
 * Right after the registration the hook (obtained from the factory) gets {@code added()} with all existing service
 * listeners, including the one with filter {@code (objectClass=com.acme.static05.Foo)}.
 *
 * <h3>Reference behaviour</h3>
 * Standalone JDK 25 program (listener added, then a ListenerHook registered once as a plain object and once as a
 * ServiceFactory): Equinox 3.21.0 and Felix 7.0.5 call the initial {@code added()} of both hooks with the existing
 * listener; JBoss OSGi (this branch, aggregated jar) only for the plain hook.
 *
 * <h3>Suggested fix</h3>
 * Decide by the registered class names ({@code Arrays.asList(classNames).contains(ListenerHook.class.getName())}),
 * and after the registration obtain the hook via the system bundle context ({@code getService}) and call
 * {@code added()} with the snapshot; ideally take the snapshot and register under the listener lock so that no
 * listener added in between is missed.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class ListenerHookFactoryNotInitializedIssueTestCase extends OSGiFrameworkTest {

    private static final String FILTER = "(objectClass=com.acme.static05.Foo)";

    @Test
    public void testFactoryListenerHookGetsExistingListeners() throws Exception {
        BundleContext context = getSystemContext();
        ServiceListener listener = new ServiceListener() {
            public void serviceChanged(ServiceEvent event) {
            }
        };
        context.addServiceListener(listener, FILTER);
        final List<String> initialFilters = Collections.synchronizedList(new ArrayList<String>());
        ServiceRegistration<?> reg = null;
        try {
            reg = context.registerService(ListenerHook.class.getName(), new ServiceFactory<ListenerHook>() {
                public ListenerHook getService(Bundle bundle, ServiceRegistration<ListenerHook> registration) {
                    return new ListenerHook() {
                        public void added(Collection<ListenerHook.ListenerInfo> listeners) {
                            for (ListenerHook.ListenerInfo info : listeners)
                                initialFilters.add(info.getFilter());
                        }

                        public void removed(Collection<ListenerHook.ListenerInfo> listeners) {
                        }
                    };
                }

                public void ungetService(Bundle bundle, ServiceRegistration<ListenerHook> registration, ListenerHook service) {
                }
            }, null);
            assertTrue("A ListenerHook registered as ServiceFactory must get added() with the existing listeners right after its registration "
                    + "(55.9.5.1); expected a listener with filter " + FILTER + ", got " + initialFilters, initialFilters.contains(FILTER));
        } finally {
            if (reg != null)
                reg.unregister();
            context.removeServiceListener(listener);
        }
    }
}
