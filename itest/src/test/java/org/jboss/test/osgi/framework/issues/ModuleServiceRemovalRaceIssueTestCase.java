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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.msc.Service;
import org.jboss.msc.service.DuplicateServiceException;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.jboss.osgi.framework.spi.FutureServiceValue;
import org.junit.Test;

/**
 * Resolving a bundle right after it was refreshed can fail with an unchecked MSC DuplicateServiceException ("Service
 * jbosgi.module.... is already registered") thrown out of FrameworkWiring.resolveBundles / Bundle.start; afterwards the
 * bundle can never resolve again ("JBOSGI011216: Module already exists"). Cause: the wait for the removal of the old
 * module service returns as soon as MSC reports State.REMOVED, which MSC already reports while the service is still
 * REMOVING and still owns its name.
 *
 * <h3>Severity</h3>
 * major - a timing race (refresh, then resolve of the same revision, e.g. refreshBundles followed by start or
 * resolveBundles, or the framework's own restart of refreshed bundles), rare on an idle machine and more frequent under
 * load (WildFly deployment churn). When it hits, the failure is permanent: the module spec of the revision was already
 * registered before the module service failed and is never rolled back, so every later resolve of that bundle throws
 * IllegalStateException "Module already exists" until the bundle is uninstalled or the server restarted; the
 * resolveBundles caller gets an undeclared RuntimeException instead of {@code false}.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.spi.FutureServiceValue} as used by
 * {@code org.jboss.osgi.framework.internal.FrameworkModuleLoaderImpl#createModuleService}; consequence in
 * {@code FrameworkResolverImpl#applyResolverResults}.
 *
 * <h3>Found by</h3>
 * fuzz-api-2 workstream (fuzz round 3, target 5): {@code fa.WiringModel} (18 JVMs in parallel), seed 3 step 59
 * {@code refreshBundles([w4, w5])} then {@code resolveBundles(null)}: {@code DuplicateServiceException: Service
 * jbosgi.module."jbosgi.w5"."1.0.0.bid4rev0" is already registered} at
 * {@code FrameworkModuleLoaderImpl.createModuleService(FrameworkModuleLoaderImpl.java:223)} &lt;
 * {@code FrameworkResolverImpl.createModuleServices(:341)} &lt; {@code applyResolverResults(:276)} &lt;
 * {@code FrameworkWiringImpl.resolveBundles(:270)}; steps 60 and 61 (later resolves of w5):
 * {@code IllegalStateException: JBOSGI011216: Module already exists: jbosgi.w5:1.0.0.bid4rev0} at
 * {@code FrameworkModuleLoaderImpl.addModuleSpec(:156)}; w5 stayed INSTALLED for the rest of the run. Probe
 * {@code fa.ProbeRefreshRace} (install exporter, resolve, refresh, resolveBundles(null), uninstall; repeated): 3 of 5400
 * iterations failed with the DuplicateServiceException, each within 1 ms of the resolve call (no wait), only with
 * several JVMs running in parallel. The MSC sources (jboss-msc 1.5.6 {@code Substate.REMOVING(State.REMOVED, false)},
 * {@code ServiceControllerImpl} DOWN_to_REMOVING schedules the RemoveTask that clears the registration
 * asynchronously) explain the window; this test opens it deterministically with a container whose only thread is
 * busy.
 *
 * <h3>Spec reference</h3>
 * FrameworkWiring.resolveBundles javadoc (OSGi Core R8): "@return true if all specified bundles are resolved; false
 * otherwise" (no unchecked exception for a resolvable bundle); Bundle.start: a resolve failure is a BundleException
 * (RESOLVE_ERROR). The MSC contract: {@code ServiceController.State.REMOVED} covers the substates REMOVING and REMOVED
 * ({@code Substate.REMOVING(ServiceController.State.REMOVED, false)}); the service name is released by the
 * RemoveTask, after which the {@code LifecycleEvent.REMOVED} is fired (transition REMOVING_to_REMOVED).
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/spi/FutureServiceValue.java:100-102}
 * ({@code if (controller.getState() == expectedState) return ...;} - returns at once for a controller in substate
 * REMOVING) used by {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkModuleLoaderImpl.java:203-211}
 * (wait for the old module service to be REMOVED, then {@code sb.install()} at :223) after
 * {@code removeModule} (:175-185) only did {@code moduleService.setMode(Mode.REMOVE)}.
 * {@code FrameworkResolverImpl.java:273/276} ({@code addModules(brevmap)} before {@code createModuleServices(brevmap)},
 * no rollback when the latter throws). Pre-existing (7689fc5a 2012, 71df054a); unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * MSC's public State.REMOVED does not mean "removed and name released"; FutureServiceValue treats it so. The resolver
 * does not undo the registered module specs when the module service cannot be installed.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl core test -Dtest=ModuleServiceRemovalRaceIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testRemovedFutureWaitsUntilNameIsReleased: FutureServiceValue(REMOVED) returned, so the module service name must be
 *   free again for the new module service, but the container still holds the old service
 *   expected null, but was:&lt;org.jboss.msc.service.ServiceControllerImpl@...&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * FutureServiceValue(controller, REMOVED) returns only after the controller has really left the registry (the
 * REMOVED lifecycle event), so that the new module service can be installed under the same name.
 *
 * <h3>Reference behaviour</h3>
 * Not applicable (jbosgi/MSC specific); Equinox and Felix never failed a resolve after refresh in the same fuzz runs.
 *
 * <h3>Suggested fix</h3>
 * In FutureServiceValue for {@code expectedState == REMOVED} do not shortcut on {@code getState()}: wait for the
 * {@code LifecycleEvent.REMOVED} (MSC fires it immediately to a listener added in substate REMOVED, and only after the
 * RemoveTask in substate REMOVING), or poll {@code serviceRegistry.getService(name) == null} in createModuleService.
 * In FrameworkResolverImpl remove the module specs added by addModules when createModuleServices fails, and map the
 * failure to a ResolutionException. (Checked: with the shortcut in FutureServiceValue.getValue disabled for REMOVED,
 * this test passes in about 1.2 s.)
 *
 * <h3>Regression vs WF41</h3>
 * no - pre-existing.
 *
 * @since 14-Oct-2026
 */
public class ModuleServiceRemovalRaceIssueTestCase {

    @Test
    public void testRemovedFutureWaitsUntilNameIsReleased() throws Exception {
        // One container thread, so the asynchronous part of the removal can be held back
        ServiceContainer container = ServiceContainer.Factory.create("module-removal-race", 1, 30, TimeUnit.SECONDS);
        final CountDownLatch blocking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            // The module service as FrameworkModuleLoaderImpl.createModuleService installs it (ON_DEMAND, nobody demands it)
            ServiceName moduleName = ServiceName.of("jbosgi", "module", "removal-race", "1.0.0.bid1rev0");
            ServiceBuilder<?> moduleBuilder = container.addService(moduleName);
            moduleBuilder.setInitialMode(Mode.ON_DEMAND);
            moduleBuilder.setInstance(Service.NULL);
            ServiceController<?> moduleService = moduleBuilder.install();
            container.awaitStability();
            assertEquals(State.DOWN, moduleService.getState());

            // Any other service work that keeps the container thread busy at this moment
            ServiceBuilder<?> busyBuilder = container.addService(ServiceName.of("busy"));
            busyBuilder.setInstance(new Service() {
                public void start(StartContext context) {
                    blocking.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }

                public void stop(StopContext context) {
                }
            });
            busyBuilder.install();
            assertTrue(blocking.await(10, TimeUnit.SECONDS));

            // FrameworkModuleLoaderImpl.removeModule
            moduleService.setMode(Mode.REMOVE);

            // The busy work ends one second later; a correct wait returns after that, when the removal has completed
            Thread releaser = new Thread() {
                public void run() {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        // ignore
                    }
                    release.countDown();
                }
            };
            releaser.start();

            // FrameworkModuleLoaderImpl.createModuleService: wait for the old service to be removed ...
            new FutureServiceValue(moduleService, State.REMOVED).get(10, TimeUnit.SECONDS);

            // ... then install the new module service under the same name
            assertNull("FutureServiceValue(REMOVED) returned, so the module service name must be free again for the new module service, but the container still holds the old service",
                    container.getService(moduleName));
            try {
                ServiceBuilder<?> newBuilder = container.addService(moduleName);
                newBuilder.setInitialMode(Mode.ON_DEMAND);
                newBuilder.setInstance(Service.NULL);
                newBuilder.install();
            } catch (DuplicateServiceException ex) {
                fail("Installing the new module service after FutureServiceValue(REMOVED) returned must not fail: " + ex);
            }
        } finally {
            release.countDown();
            container.shutdown();
            container.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
