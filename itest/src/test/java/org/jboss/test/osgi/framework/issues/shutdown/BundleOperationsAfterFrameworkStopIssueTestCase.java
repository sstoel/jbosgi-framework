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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * {@code Bundle.stop()}, {@code stop(STOP_TRANSIENT)}, {@code update(InputStream)} and {@code uninstall()} on a bundle of a
 * stopped framework (or of a framework that is tearing down its services) throw {@code NullPointerException} instead of
 * returning or throwing a documented BundleException / IllegalStateException.
 *
 * <h3>Severity</h3>
 * minor. Callers that race with a framework stop - a management operation, a bundle's own worker or timer thread, a
 * listener, a deployment undeploy running during shutdown - get an undocumented unchecked exception from the framework
 * internals ("Cannot invoke ... getLockManager() because the return value of ... getFrameworkState() is null"); code that
 * handles the documented exceptions (BundleException, IllegalStateException) does not catch it, so the calling thread
 * dies or the operation is reported as an internal error. In the randomized shutdown fuzzer these NPEs (from driver
 * threads, listeners and ServiceFactory callbacks) appeared in 3 of 40 seeds on the branch.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.BundleManagerPlugin} ({@code startBundle},
 * {@code stopBundle}, {@code updateBundle}, {@code uninstallBundle}, {@code getBundleLifecycle}).
 *
 * <h3>Found by</h3>
 * sd-fw workstream (shutdown round), randomized shutdown fuzzer {@code ShutdownFuzz} (UNEXPECTED NullPointerException
 * at BundleManagerPlugin.java:608/644/659/730 in seeds 7, 14, 33 and seed 1005 of the run with fuzz/16 and
 * concurrency/05 patched out), Equinox/Felix differential {@code SdDiff d9}. The same pattern (a framework service
 * dereferenced without a state check) also produced NPEs from {@code Framework.getBundleContext()} during the restart of
 * {@code Framework.update()} (FrameworkProxy.java:444, seeds 10 and 14) and from a resolve during the stop
 * (FrameworkExtensions.getModuleProvider:160, seed 26); those windows are not covered by this test.
 *
 * <h3>Spec reference</h3>
 * {@code org.osgi.framework.Bundle.stop(int)} javadoc (osgi.core 8.0.0): "@throws BundleException BundleException types
 * thrown by this method include: BundleException.STATECHANGE_ERROR and BundleException.ACTIVATOR_ERROR. @throws
 * IllegalStateException If this bundle has been uninstalled or this bundle tries to change its own state. @throws
 * SecurityException ..."; {@code Bundle.update(InputStream)}: "@throws BundleException ... @throws IllegalStateException
 * If this bundle has been uninstalled or this bundle tries to change its own state."; {@code Bundle.uninstall()}:
 * "@throws BundleException ... @throws IllegalStateException If this bundle has been uninstalled or this bundle tries to
 * change its own state." No other exception is part of the contract.
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java} (WF41-osgi-r8 d7b20d08): lines
 * 608, 628, 644, 659 ({@code LockManager lockManager = getFrameworkState().getLockManager();}) and 730
 * ({@code return getFrameworkState().getCoreServices().getBundleLifecycle();}); {@code getFrameworkState()} (line
 * 340-342) returns {@code injectedFramework.getOptionalValue()}, which is {@code null} once the framework's MSC services
 * have been stopped. {@code Bundle.start} happens to throw an IllegalStateException without message from another
 * check. Unchanged from WF41.
 *
 * <h3>Root cause</h3>
 * The bundle lifecycle entry points dereference framework services without checking that the framework is still
 * running; after (or during) the stop those services are uninjected.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test-compile surefire:test@setA -Dtest=BundleOperationsAfterFrameworkStopIssueTestCase
 * </pre>
 *
 * <h3>Observed</h3>
 * <pre>
 * Bundle lifecycle methods called on a bundle of a stopped framework must return or throw one of their documented
 * exceptions (BundleException, IllegalStateException), but: [stop() threw java.lang.NullPointerException: Cannot invoke
 * "org.jboss.osgi.framework.internal.FrameworkState.getCoreServices()" because the return value of
 * "org.jboss.osgi.framework.internal.BundleManagerPlugin.getFrameworkState()" is null at
 * org.jboss.osgi.framework.internal.BundleManagerPlugin.getBundleLifecycle(BundleManagerPlugin.java:730),
 * stop(STOP_TRANSIENT) threw java.lang.NullPointerException: ... (BundleManagerPlugin.java:730),
 * update(InputStream) threw java.lang.NullPointerException: Cannot invoke "...FrameworkState.getLockManager()" ...
 * at org.jboss.osgi.framework.internal.BundleManagerPlugin.updateBundle(BundleManagerPlugin.java:644),
 * uninstall() threw java.lang.NullPointerException: ... (BundleManagerPlugin.java:659)]; documented outcomes: [start()
 * threw IllegalStateException: null, adapt(BundleStartLevel).setStartLevel(2) threw IllegalStateException: null]
 * </pre>
 *
 * <h3>Expected</h3>
 * IllegalStateException (as Equinox) or a no-op/BundleException; never a NullPointerException.
 *
 * <h3>Reference behaviour</h3>
 * {@code SdDiff d9} (same sequence as a plain launcher program): Equinox 3.21.0 - start/stop/uninstall/loadClass throw
 * IllegalStateException "Module has been uninstalled", update throws BundleException; Felix 7.0.5 - start/stop/update/
 * uninstall return normally (no effect on the stopped framework).
 *
 * <h3>Suggested fix</h3>
 * Check the framework state at the lifecycle entry points and throw an IllegalStateException
 * ("JBOSGI011xxx: Framework already stopped", {@code MESSAGES.illegalStateFrameworkAlreadyStopped()} exists) when the
 * framework services are gone. Verified as a trial fix (d7b20d08 + {@code activeFrameworkState()} that throws that
 * IllegalStateException, used at the five lines above): the test passes; the launch and bundle test packages still pass
 * (165 tests).
 *
 * <h3>Regression vs WF41</h3>
 * no (measured: the test fails the same way on origin/WF41 5a017edc).
 */
public class BundleOperationsAfterFrameworkStopIssueTestCase {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testLifecycleOperationsOnBundleOfStoppedFramework() throws Exception {
        framework = newFramework();
        framework.start();
        final Bundle bundle = framework.getBundleContext().installBundle("issues.shutdown.after", bundleContent());
        bundle.start();
        framework.stop();
        FrameworkEvent stopped = framework.waitForStop(30000);
        assertEquals(FrameworkEvent.STOPPED, stopped.getType());

        // A management thread, a listener or a worker of the bundle that is late by a few milliseconds
        Map<String, Callable<Object>> operations = new java.util.LinkedHashMap<String, Callable<Object>>();
        operations.put("stop()", () -> { bundle.stop(); return null; });
        operations.put("stop(STOP_TRANSIENT)", () -> { bundle.stop(Bundle.STOP_TRANSIENT); return null; });
        operations.put("update(InputStream)", () -> { bundle.update(bundleContent()); return null; });
        operations.put("uninstall()", () -> { bundle.uninstall(); return null; });
        operations.put("start()", () -> { bundle.start(); return null; });
        operations.put("adapt(BundleStartLevel).setStartLevel(2)", () -> {
            BundleStartLevel bsl = bundle.adapt(BundleStartLevel.class);
            if (bsl != null)
                bsl.setStartLevel(2);
            return null;
        });

        List<String> results = new ArrayList<String>();
        List<String> undocumented = new ArrayList<String>();
        for (Map.Entry<String, Callable<Object>> entry : operations.entrySet()) {
            try {
                entry.getValue().call();
                results.add(entry.getKey() + " returned (bundle state " + bundle.getState() + ")");
            } catch (BundleException | IllegalStateException ex) {
                results.add(entry.getKey() + " threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (Throwable th) {
                StackTraceElement top = th.getStackTrace().length > 0 ? th.getStackTrace()[0] : null;
                undocumented.add(entry.getKey() + " threw " + th + " at " + top);
            }
        }
        System.out.println("documented outcomes: " + results);
        assertTrue("Bundle lifecycle methods called on a bundle of a stopped framework must return or throw one of their documented exceptions"
                + " (BundleException, IllegalStateException), but: " + undocumented + "; documented outcomes: " + results, undocumented.isEmpty());
    }

    private static InputStream bundleContent() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "issues.shutdown.after");
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                return builder.openStream();
            }
        });
        return archive.as(ZipExporter.class).exportAsInputStream();
    }

    private Framework newFramework() {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-shutdown/after-stop").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        return ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
    }
}
