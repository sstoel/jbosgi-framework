/*
 * #%L
 * JBossOSGi Framework
 * %%
 * Copyright (C) 2026 JBoss by Red Hat
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
package org.jboss.test.osgi.framework.issues.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.spi.util.ServiceLoader;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * {@code Framework.stop()} sets the framework state to RESOLVED before the services registered by the framework itself
 * (PackageAdmin, StartLevel, LogService, LoggerAdmin, URLStreamHandlerService, Resolver, ...) are unregistered: their
 * UNREGISTERING events are delivered after the framework already reports RESOLVED and {@code stop()} has returned.
 *
 * <h3>Severity</h3>
 * minor. Listeners and ServiceTrackers that track framework services (a launcher's log tracker, a management agent
 * tracking StartLevel/PackageAdmin) are called back when the framework already reads as stopped: their cleanup cannot use
 * the framework any more ({@code getBundleContext()} returns null, lifecycle calls fail, see shutdown/09), the events
 * arrive on MSC threads in parallel to the launcher's own teardown, and code that checks "framework RESOLVED -> the stop
 * is complete" sees UNREGISTERING events afterwards. It also means event handling is still enabled after the framework
 * reported RESOLVED (step 4 before step 5).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin.shutdownManager} (and the MSC
 * {@code stop} of {@code SystemServicesPlugin}, {@code PackageAdminPlugin}, {@code StartLevelPlugin},
 * {@code URLHandlerPlugin}, {@code LifecycleInterceptorPlugin}, which unregister the framework services).
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round), while orchestrating a refresh that completes during the stop (Equinox/Felix
 * differential {@code SdDiff d15}: the system services' UNREGISTERING arrived at framework state 4 only on jbosgi), then
 * {@code SdDiff d17}.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.launch.Framework.stop()} javadoc (osgi.core 8.0.0): "1. This Framework's state is set to
 * STOPPING. 2. All installed bundles must be stopped ... 3. Unregister all services registered by this Framework. 4. Event
 * handling is disabled. 5. This Framework's state is set to RESOLVED. 6. All resources held by this Framework are
 * released. ... 7. Notify all threads that are waiting at waitForStop that the stop operation has completed."<br>
 * OSGi Core R8 4.2.8 "Stopping a Framework" lists the same steps in the same order
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html).
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java} (WF41-osgi-r8 d7b20d08) lines
 * 984-986: {@code cachedSystemBundle = getSystemBundle(); shutdownContainer.shutdown(); setManagerState(Bundle.RESOLVED);}
 * - {@code ShutdownContainer.shutdown()} (line 1116-1124) only <em>initiates</em> the asynchronous MSC container shutdown
 * ({@code ServiceContainer.shutdown()} returns at once); the framework services are unregistered later by the
 * {@code stop(StopContext)} methods of the framework's MSC services (e.g. {@code SystemServicesPlugin.stop} ->
 * {@code SystemServicesImpl.unregisterServices}, {@code spi/SystemServicesPlugin.java:85-122}) on MSC threads, while the
 * state is already RESOLVED. Unchanged from WF41.
 *
 * <h3>Root cause</h3>
 * The state is set to RESOLVED right after initiating the asynchronous container shutdown, instead of after the
 * framework services have been unregistered (container terminated).
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=FrameworkServicesUnregisteredAfterStopIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * The framework must unregister its own services while it is STOPPING (Framework.stop step 3), before its state is set
 * to RESOLVED (step 5); 11 services were registered, but these UNREGISTERING events were delivered when the framework
 * already reported another state: [org.osgi.service.packageadmin.PackageAdmin (framework state 4),
 * org.jboss.osgi.deployment.interceptor.LifecycleInterceptorService (framework state 4),
 * org.osgi.service.startlevel.StartLevel (framework state 4), org.osgi.service.log.LogService (framework state 4),
 * org.osgi.service.url.URLStreamHandlerService (framework state 4), org.osgi.service.log.LogReaderService (framework
 * state 4), org.osgi.service.log.admin.LoggerAdmin (framework state 4), org.jboss.osgi.resolver.XResolver (framework
 * state 4), org.osgi.service.resolver.Resolver (framework state 4), org.jboss.osgi.resolver.XEnvironment (framework
 * state 4), ...]
 * </pre>
 *
 * <h3>Expected</h3>
 * All framework services are unregistered (UNREGISTERING delivered) while the state is STOPPING; RESOLVED afterwards.
 *
 * <h3>Reference behaviour</h3>
 * {@code SdDiff d17} (system-context ServiceListener, Framework.stop, framework state recorded per UNREGISTERING event
 * of a service registered by bundle 0): Equinox 3.21.0 - all 22 UNREGISTERING events of its framework services at state 16 (STOPPING);
 * Felix 7.0.5 - both at state 16; jbosgi - all 11 at state 4 (RESOLVED), after {@code stop()} returned.
 *
 * <h3>Suggested fix</h3>
 * Set RESOLVED only after the container shutdown has completed (await the termination of the framework's own container,
 * bounded), or unregister the framework services explicitly before setting RESOLVED. Verified as a trial fix
 * (d7b20d08 + {@code shutdownContainer.awaitTermination(30 s)} between {@code shutdownContainer.shutdown()} and
 * {@code setManagerState(RESOLVED)}): the test passes; the launch and service test packages still pass (133 tests).<br>
 * Caution (coordinator, from code reading, not measured): in WildFly the framework runs in the server's container
 * ({@code allowContainerShutdown} false, BundleManagerPlugin:213), which never terminates during a framework stop, so
 * that trial would add 30 s to every stop once connect/27 and services/26 make the server stop the framework. Prefer
 * the second variant (unregister the framework services explicitly before RESOLVED), or await termination only when
 * the framework owns its container.
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the test fails the same way on origin/WF41 5a017edc, with the 6 framework services WF41 registers).
 */
public class FrameworkServicesUnregisteredAfterStopIssueTestCase {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testFrameworkServicesUnregisteredWhileStopping() throws Exception {
        framework = newFramework();
        framework.start();

        // A launcher component tracks the services that the framework registers (log, start level, package admin, ...)
        final List<String> late = Collections.synchronizedList(new ArrayList<String>());
        final List<String> all = Collections.synchronizedList(new ArrayList<String>());
        framework.getBundleContext().addServiceListener(new ServiceListener() {
            @Override
            public void serviceChanged(ServiceEvent event) {
                ServiceReference<?> sref = event.getServiceReference();
                Bundle owner = sref.getBundle();
                if (event.getType() == ServiceEvent.UNREGISTERING && owner != null && owner.getBundleId() == 0) {
                    String[] classes = (String[]) sref.getProperty(Constants.OBJECTCLASS);
                    String entry = classes[0] + " (framework state " + framework.getState() + ")";
                    all.add(entry);
                    if (framework.getState() != Bundle.STOPPING)
                        late.add(entry);
                }
            }
        });
        int registered = framework.getRegisteredServices().length;

        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(30000);
        assertEquals("waitForStop must report STOPPED", FrameworkEvent.STOPPED, stopped.getType());
        System.out.println("UNREGISTERING events of framework services: " + all);
        assertTrue("The framework must unregister its own services while it is STOPPING (Framework.stop step 3), before its state is set to RESOLVED (step 5);"
                + " " + registered + " services were registered, but these UNREGISTERING events were delivered when the framework already reported another state: " + late,
                late.isEmpty());
    }

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/framework-services").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
