package org.jboss.test.osgi.framework.issues;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * The getService recursion guard is per bundle instead of per service: a ServiceFactory that gets another service
 * for the requesting bundle receives null, while a real recursive call is answered with null without the
 * FrameworkEvent ERROR that the specification requires.
 *
 * <h3>Severity</h3>
 * major. Any {@link ServiceFactory#getService(Bundle, ServiceRegistration)} that obtains another service through the
 * context of the requesting bundle gets null. This is what Declarative Services does when a bundle uses its own
 * delayed component (registered as a ServiceFactory) and that component has a reference: binding the reference
 * happens inside the factory call with the same bundle context, gets null, and the component cannot be activated.
 * The missing ERROR event for a real recursion is minor.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.ServiceManagerImpl#getService
 *
 * <h3>Found by</h3>
 * services workstream, code review (recursion detection / ServiceException.FACTORY_RECURSION), confirmed with an
 * Equinox / Felix differential of both scenarios.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, BundleContext.getService(ServiceReference)
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.BundleContext.getService-ServiceReference-):
 * "If the context bundle's use count for the service is currently zero and the service has bundle or prototype
 * scope, the ServiceFactory.getService(Bundle, ServiceRegistration) method is called to supply the service object
 * for the context bundle. If the service object returned by the ServiceFactory object is null, not an instanceof all
 * the classes named when the service was registered or the ServiceFactory object throws an exception or will be
 * recursively called for the context bundle, null is returned and a Framework event of type FrameworkEvent.ERROR
 * containing a ServiceException describing the error is fired."
 * ServiceException.FACTORY_RECURSION: "The service factory resulted in a recursive call to itself for the
 * requesting bundle."
 * Recursion is defined per service factory ("a recursive call to itself"); getting a different service is not a
 * recursion.
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:66
 * ({@code ThreadLocal<Bundle> getServiceRecursion}) and lines 254-257 / 277:
 * {@code if (getServiceRecursion.get() == bundle) return null;} ... {@code getServiceRecursion.remove();}.
 * Introduced by commit e9fc5a3e (2013, "Fix some service registry related issues"), already on WF41.
 *
 * <h3>Root cause</h3>
 * The thread local only remembers the requesting bundle. Any nested getService for the same bundle, for any service
 * (also a plain singleton service), is treated as a recursion and answered with null. A real recursion is answered
 * with null but no FrameworkEvent is fired. In addition the finally block removes the thread local instead of
 * restoring the previous value, so a nested call for another bundle disables the guard for the outer call.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=GetServiceRecursionGuardIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testFactoryGetsAnotherServiceForTheSameBundle: The factory must get the other service for the requesting bundle
 *   expected same:&lt;...TestService@...&gt; was not:&lt;null&gt;
 * testRecursiveFactoryCallFiresError: A recursive factory call must fire a FrameworkEvent ERROR
 *   (no FrameworkEvent within 5 seconds)
 * </pre>
 *
 * <h3>Expected</h3>
 * The factory gets the other service. Only a call of the same service factory for the same bundle while it is
 * already running returns null, and a FrameworkEvent ERROR with a ServiceException (type FACTORY_RECURSION,
 * the type the API defines for this case) is fired.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0 and Felix 7.0.5: the factory gets the other service (same object as the singleton). For the real
 * recursion both return null to the nested call and fire a FrameworkEvent with a ServiceException: Felix fires
 * ERROR (ServiceException type FACTORY_ERROR), Equinox fires WARNING with type FACTORY_RECURSION. JBoss OSGi
 * returns null for the other service and fires no event for the recursion.
 *
 * <h3>Suggested fix</h3>
 * Track the factory calls in progress per (service, bundle), e.g. a flag in the ServiceFactoryHolder for the bundle,
 * or a thread local set of {@code ServiceState}/bundle pairs that is restored (not removed) in the finally block.
 * On a detected recursion fire {@code FrameworkEvent.ERROR} with
 * {@code new ServiceException(..., ServiceException.FACTORY_RECURSION)} and return null.
 *
 * <h3>Regression vs WF41</h3>
 * no (present since 2013).
 */
public class GetServiceRecursionGuardIssueTestCase extends OSGiFrameworkTest {

    static class TestService implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    public void testFactoryGetsAnotherServiceForTheSameBundle() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("recursion-guard-a"));
        try {
            bundle.start();
            BundleContext context = bundle.getBundleContext();

            // A plain service and a service factory of the same bundle; the factory needs the plain service
            TestService other = new TestService();
            final ServiceRegistration<Runnable> otherReg = context.registerService(Runnable.class, other, null);
            final Object[] seenByFactory = new Object[1];
            ServiceRegistration<?> factoryReg = context.registerService(Object.class.getName(), new ServiceFactory<Object>() {
                @Override
                public Object getService(Bundle requester, ServiceRegistration<Object> registration) {
                    seenByFactory[0] = requester.getBundleContext().getService(otherReg.getReference());
                    return new Object();
                }

                @Override
                public void ungetService(Bundle requester, ServiceRegistration<Object> registration, Object service) {
                }
            }, null);

            assertNotNull("The factory service is available", context.getService(factoryReg.getReference()));
            assertSame("The factory must get the other service for the requesting bundle", other, seenByFactory[0]);
        } finally {
            bundle.uninstall();
        }
    }

    @Test
    public void testRecursiveFactoryCallFiresError() throws Exception {
        Bundle bundle = installBundle(getBundleArchive("recursion-guard-b"));
        final BlockingQueue<FrameworkEvent> events = new LinkedBlockingQueue<FrameworkEvent>();
        FrameworkListener listener = new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getThrowable() instanceof ServiceException)
                    events.add(event);
            }
        };
        BundleContext syscontext = getSystemContext();
        syscontext.addFrameworkListener(listener);
        try {
            bundle.start();
            BundleContext context = bundle.getBundleContext();

            // A factory that calls itself once for the same bundle
            final AtomicInteger calls = new AtomicInteger();
            final Object[] nested = new Object[] { "not called" };
            final ServiceRegistration<?>[] sreg = new ServiceRegistration<?>[1];
            sreg[0] = context.registerService(Object.class.getName(), new ServiceFactory<Object>() {
                @Override
                public Object getService(Bundle requester, ServiceRegistration<Object> registration) {
                    if (calls.incrementAndGet() == 1)
                        nested[0] = requester.getBundleContext().getService(sreg[0].getReference());
                    return new Object();
                }

                @Override
                public void ungetService(Bundle requester, ServiceRegistration<Object> registration, Object service) {
                }
            }, null);
            ServiceReference<?> sref = sreg[0].getReference();

            assertNotNull("The outer call gets the service", context.getService(sref));
            assertEquals("The factory is not called for the recursive call", 1, calls.get());
            assertNull("The recursive call gets null", nested[0]);

            FrameworkEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull("A recursive factory call must fire a FrameworkEvent ERROR", event);
            assertEquals("The event type must be ERROR (2)", FrameworkEvent.ERROR, event.getType());
            assertTrue("The event must carry a ServiceException", event.getThrowable() instanceof ServiceException);
            assertEquals("The ServiceException type must be FACTORY_RECURSION (6)", ServiceException.FACTORY_RECURSION,
                    ((ServiceException) event.getThrowable()).getType());
        } finally {
            syscontext.removeFrameworkListener(listener);
            bundle.uninstall();
        }
    }

    private JavaArchive getBundleArchive(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(name);
                return builder.openStream();
            }
        });
        return archive;
    }
}
