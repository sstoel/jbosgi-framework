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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.testing.OSGiFrameworkTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/**
 * A service can still be obtained after the UNREGISTERING event has been delivered, while the framework releases
 * the service for the bundles that use it; the bundle that gets it keeps a use count on the unregistered service
 * forever (its service object is never released) and Bundle.getServicesInUse() then throws IllegalStateException.
 *
 * <h3>Severity</h3>
 * major. A bundle that gets a service while another thread unregisters it (for example while the providing bundle
 * stops) can receive a service object from a service that is gone. The framework never calls
 * ServiceFactory.ungetService for that object (a leaked component instance), and {@code getServicesInUse()} of
 * that bundle throws {@code IllegalStateException: Service unregistered} from then on (until it stops), which
 * breaks management tools and the bundle's own cleanup.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, org.jboss.osgi.framework.internal.ServiceManagerImpl#unregisterService / #getService,
 * ServiceStateImpl#unregisterInternal / #isUnregistered, AbstractBundleState#getServicesInUse
 *
 * <h3>Found by</h3>
 * services workstream, review of the unregistration sequence after checking commit d7b20d08 (which closed this
 * window only for prototype service objects); Equinox differential.
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, ServiceRegistration.unregister()
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.api.html#org.osgi.framework.ServiceRegistration.unregister--):
 * "1. The service is removed from the Framework service registry so that it can no longer be obtained.
 * 2. A service event of type ServiceEvent.UNREGISTERING is fired so that bundles using this service can release
 * their use of the service. Once delivery of the service event is complete, the ServiceReference objects for the
 * service may no longer be used to get a service object for the service.
 * 3. For each bundle whose use count for this service is greater than zero: The bundle's use count for this
 * service is set to zero. If the service has bundle or prototype scope, the ServiceFactory.ungetService method is
 * called to release the service object for the bundle."
 * BundleContext.getService: "This method will always return null when the service associated with the specified
 * reference has been unregistered."
 *
 * <h3>Code location</h3>
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceManagerImpl.java:249-252 (getService only checks
 * {@code serviceState.isUnregistered()}), :286-323 (unregisterService: event, then the release loop),
 * core/src/main/java/org/jboss/osgi/framework/internal/ServiceStateImpl.java:377-381 ({@code registration = null},
 * which is what isUnregistered() tests, is set only after unregisterService returned) and
 * AbstractBundleState.java:340 (getServicesInUse calls getReference(), which throws for an unregistered service).
 * Original code; commit d7b20d08 added a flag for prototype objects only.
 *
 * <h3>Root cause</h3>
 * The "unregistered" state is only set at the very end of the unregistration. Between the UNREGISTERING event and
 * that point getService still succeeds, adds a use count and calls the factory, but the release loop has already
 * run (or runs over a snapshot of the using bundles), so this use is never released.
 *
 * The test asks for the service from inside the framework's release of bundle A's object (step 3), which hits the
 * same window as another thread calling getService at that moment, but deterministically.
 *
 * <h3>Reproduction</h3>
 * JDK 25: {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=GetServiceDuringUnregisterIssueTestCase}
 *
 * <h3>Observed</h3>
 * <pre>
 * testGetServiceAfterUnregisteringEvent: After the UNREGISTERING event the service must no longer be obtainable
 *   expected null, but was:&lt;java.lang.Object@...&gt;
 * testNoUseCountLeftAfterUnregister: getServicesInUse must not throw, but got
 *   java.lang.IllegalStateException: JBOSGI011224: Service unregistered: ServiceState{...}
 * </pre>
 *
 * <h3>Expected</h3>
 * getService returns null once the UNREGISTERING event has been delivered; after unregister() no bundle has a use
 * count on the service, getServicesInUse() returns null, and every object the factory created was released.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21.0, same scenario (bundle B gets the service from inside the release of bundle A's object):
 * B gets null, factory getService 1 / ungetService 1, B.getServicesInUse() is null.
 *
 * <h3>Suggested fix</h3>
 * Mark the service as unregistering/unregistered under the ServiceState lock before (or right after) the
 * UNREGISTERING event, let getService (and ServiceObjects.getService) return null when the mark is set, and set
 * {@code registration = null} inside the same critical section; check the mark again after the factory call and
 * release the object if the service was unregistered meanwhile (like getPrototypeValue already does).
 *
 * <h3>Regression vs WF41</h3>
 * no (original code).
 */
public class GetServiceDuringUnregisterIssueTestCase extends OSGiFrameworkTest {

    @Test
    public void testGetServiceAfterUnregisteringEvent() throws Exception {
        Scenario scenario = new Scenario("get-during-unregister-a", "get-during-unregister-b");
        try {
            scenario.unregister();
            assertNull("After the UNREGISTERING event the service must no longer be obtainable", scenario.gotDuringRelease);
        } finally {
            scenario.uninstall();
        }
    }

    @Test
    public void testNoUseCountLeftAfterUnregister() throws Exception {
        Scenario scenario = new Scenario("use-count-left-a", "use-count-left-b");
        try {
            scenario.unregister();
            ServiceReference<?>[] inUse = null;
            try {
                inUse = scenario.bundleB.getServicesInUse();
            } catch (IllegalStateException ex) {
                fail("getServicesInUse must not throw, but got " + ex);
            }
            assertNull("No service is in use after unregistration, but got " + (inUse != null ? Arrays.asList(inUse) : null), inUse);
            assertEquals("Every service object of the factory must be released", scenario.gets.get(), scenario.ungets.get());
        } finally {
            scenario.uninstall();
        }
    }

    /**
     * Bundle A uses a bundle scope service. While the framework releases A's service object during unregistration,
     * bundle B asks for the service.
     */
    class Scenario {
        final Bundle bundleA;
        final Bundle bundleB;
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger ungets = new AtomicInteger();
        final ServiceRegistration<?> sreg;
        final ServiceReference<?> sref;
        Object gotDuringRelease = "not called";

        Scenario(String nameA, String nameB) throws Exception {
            bundleA = installBundle(getBundleArchive(nameA));
            bundleB = installBundle(getBundleArchive(nameB));
            bundleA.start();
            bundleB.start();
            final ServiceReference<?>[] holder = new ServiceReference<?>[1];
            sreg = getSystemContext().registerService(Object.class.getName(), new ServiceFactory<Object>() {
                @Override
                public Object getService(Bundle bundle, ServiceRegistration<Object> registration) {
                    gets.incrementAndGet();
                    return new Object();
                }

                @Override
                public void ungetService(Bundle bundle, ServiceRegistration<Object> registration, Object service) {
                    ungets.incrementAndGet();
                    if (bundle == bundleA)
                        gotDuringRelease = bundleB.getBundleContext().getService(holder[0]);
                }
            }, null);
            sref = holder[0] = sreg.getReference();
            bundleA.getBundleContext().getService(sref);
        }

        void unregister() {
            sreg.unregister();
        }

        void uninstall() throws Exception {
            bundleA.uninstall();
            bundleB.uninstall();
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
