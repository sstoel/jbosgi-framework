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
package org.jboss.test.osgi.framework.issues;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * {@code Framework.stop()} and {@code Framework.update()} stop the framework in the calling thread and only return when
 * the framework has stopped (update: restarted), instead of returning immediately and stopping it on another thread;
 * a bundle that stops the framework from its activator is left ACTIVE, with its activator never stopped.
 *
 * <h3>Severity</h3>
 * major. Any caller of {@code getBundle(0).stop()/update()} blocks for as long as every bundle needs to stop (a
 * management operation, a bundle's own thread, an event listener), and the stop runs on whatever locks that caller
 * holds. A bundle that stops the system bundle from {@code BundleActivator.start} (e.g. a "shutdown after task"
 * bundle) is skipped by the shutdown because it is still STARTING; it then becomes ACTIVE in a stopped framework and
 * its {@code stop} method is never called (resources it opened are never released).
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.FrameworkProxy.stop/update},
 * {@code BundleManagerPlugin.shutdownManager}.
 *
 * <h3>Found by</h3>
 * connect workstream (framework lifecycle), exploratory tests plus a differential run against Equinox 3.18.300.
 *
 * <h3>Spec reference</h3>
 * org.osgi.framework.launch.Framework.stop() javadoc (osgi.core 8.0.0): "Stop this Framework. The method returns
 * immediately to the caller after initiating the following steps to be taken on another thread."<br>
 * Framework.update() javadoc: "Stops and restarts this Framework. The method returns immediately to the caller after
 * initiating the following steps to be taken on another thread."<br>
 * OSGi Core R8, 4.2.8 Stopping a Framework (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.lifecycle.html):
 * the steps include "All installed bundles must be stopped without changing each bundle's persistent autostart setting."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/FrameworkProxy.java:276-279} ({@code stop} calls
 * {@code bundleManager.shutdownManager(false)} in the caller's thread) and lines 308-335 ({@code update} builds a
 * Runnable and calls {@code new Thread(cmd, "Framework update thread").run()}, i.e. runs it in the caller's thread);
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/BundleManagerPlugin.java} {@code shutdownManager(int)} (line 955:
 * "Move to start level 0 in the current thread", then waits up to 10 s for the start level event). Unchanged since
 * WF41.
 *
 * <h3>Root cause</h3>
 * The shutdown is performed synchronously; {@code Thread.run()} is called where {@code Thread.start()} was meant.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=FrameworkStopUpdateNotAsynchronousIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * testStopReturnsImmediately: Framework.stop() must return immediately and stop the framework on another thread, but it
 *   returned after 3003 ms, when the framework had already stopped (state 4)
 * testUpdateReturnsImmediately: Framework.update() must return immediately and restart the framework on another
 *   thread, but it returned after 3061 ms
 * testStopFromActivatorStopsTheBundle: The bundle that stopped the framework from its activator must be stopped with
 *   the framework (its activator stop called), but the activator stop was not called and the bundle state is 32
 * </pre>
 *
 * <h3>Expected</h3>
 * stop()/update() return within milliseconds; the framework then stops (and for update restarts) on another thread,
 * which waits for the starting bundle to finish starting and then stops it.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.18.300, same scenarios: update() returned after 2 ms and stop() after 1 ms while a bundle needed 3 s to
 * stop; the bundle that called stop() from its activator was stopped with the framework.
 *
 * <h3>Suggested fix</h3>
 * Run {@code shutdownManager} (and for update the following init/start) on a new thread started with
 * {@code Thread.start()} (keep a reference so that waitForStop and a second stop() see the stop in progress), and let
 * the shutdown wait for bundles that are STARTING instead of skipping them.
 *
 * <h3>Regression vs WF41</h3>
 * no (identical code on WF41; this test fails the same way there).
 */
public class FrameworkStopUpdateNotAsynchronousIssueTestCase extends OSGiTest {

    private static final long STOP_MILLIS = 3000;

    private Framework framework;

    @Before
    public void setUp() {
        System.clearProperty(Activator.MODE);
        System.clearProperty(Activator.SLEEP);
        System.clearProperty(Activator.STOPPED);
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(Activator.MODE);
        System.clearProperty(Activator.SLEEP);
        System.clearProperty(Activator.STOPPED);
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testStopReturnsImmediately() throws Exception {
        System.setProperty(Activator.SLEEP, Long.toString(STOP_MILLIS));
        startFrameworkWithBundle().start();

        long start = System.currentTimeMillis();
        framework.stop();
        long elapsed = System.currentTimeMillis() - start;
        int state = framework.getState();
        assertTrue("Framework.stop() must return immediately and stop the framework on another thread, but it returned after " + elapsed
                + " ms, when the framework had already stopped (state " + state + ")", elapsed < STOP_MILLIS / 2);
        assertEquals(FrameworkEvent.STOPPED, framework.waitForStop(20000).getType());
    }

    @Test
    public void testUpdateReturnsImmediately() throws Exception {
        System.setProperty(Activator.SLEEP, Long.toString(STOP_MILLIS));
        startFrameworkWithBundle().start();

        long start = System.currentTimeMillis();
        framework.update();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("Framework.update() must return immediately and restart the framework on another thread, but it returned after " + elapsed + " ms",
                elapsed < STOP_MILLIS / 2);
    }

    @Test
    public void testStopFromActivatorStopsTheBundle() throws Exception {
        System.setProperty(Activator.MODE, "stop-framework");
        final Bundle bundle = startFrameworkWithBundle();

        // The bundle stops the framework from its activator start method
        Thread starter = new Thread() {
            @Override
            public void run() {
                try {
                    bundle.start();
                } catch (Exception ex) {
                    // the framework is stopping
                }
            }
        };
        starter.start();
        starter.join(20000);
        assertEquals(FrameworkEvent.STOPPED, framework.waitForStop(20000).getType());

        assertTrue("The bundle that stopped the framework from its activator must be stopped with the framework (its activator stop called), but the activator stop was not called and the bundle state is "
                + bundle.getState(), Boolean.getBoolean(Activator.STOPPED));
    }

    private Bundle startFrameworkWithBundle() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();

        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "async-stop-bundle");
        archive.addClasses(Activator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(Activator.class);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(archive.getName(), archive.as(ZipExporter.class).exportAsInputStream());
    }

    /** The activator talks to the test through system properties, because the bundle has its own copy of this class */
    public static class Activator implements BundleActivator {

        static final String MODE = "issues.async.mode";
        static final String SLEEP = "issues.async.sleep";
        static final String STOPPED = "issues.async.stopped";

        @Override
        public void start(BundleContext context) throws Exception {
            if ("stop-framework".equals(System.getProperty(MODE)))
                context.getBundle(0).stop();
        }

        @Override
        public void stop(BundleContext context) throws Exception {
            System.setProperty(STOPPED, "true");
            Thread.sleep(Long.getLong(SLEEP, 0));
        }
    }
}
