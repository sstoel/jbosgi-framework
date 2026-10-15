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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.osgi.spi.util.ServiceLoader;
import org.jboss.osgi.testing.OSGiTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * A {@code FrameworkStartLevel.setStartLevel} call made while a start level change is in progress either blocks the
 * caller until that change has finished (instead of returning immediately), or, when the caller wins the race for the
 * start level manager's monitor, is queued in the wrong direction so that {@code setStartLevel(10)} followed by
 * {@code setStartLevel(3)} leaves the framework at start level 10.
 *
 * <h3>Severity</h3>
 * major. A management agent calling setStartLevel while bundles are being started hangs for as long as their
 * activators need (the start level manager's monitor is held by the start level thread while it runs the bundle
 * activators); in the other interleaving the framework
 * ends in the wrong start level with bundles running that were meant to be stopped, and the STARTLEVEL_CHANGED event
 * for the request for level 3 is fired while the framework is at level 10.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.StartLevelManagerImpl}.
 *
 * <h3>Found by</h3>
 * connect workstream (compliance matrix framework.startlevel#7/#18/#19), exploratory tests: this test always hits the
 * blocking interleaving; an exploratory variant (framework listener registered, framework idle for 500 ms after start)
 * hit the wrong-direction interleaving in 2 of 2 runs (STARTLEVEL_CHANGED twice at level 10, final level 10, bundle with
 * start level 5 ACTIVE).
 *
 * <h3>Spec reference</h3>
 * OSGi Core R8, 9.3.1 Changing the Active Start Level
 * (https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html): "The process of starting or
 * stopping bundles, which is initiated by the setStartLevel(int) method, must take place asynchronously." ... "If the
 * Framework is currently involved in changing the active start level, it must first reach the previously requested
 * start level before it is allowed to continue with a newly requested start level. For example, assume the active
 * start level is 5 and the Framework is requested to transition to start level 3. Before start level 3 is reached,
 * another request is made to transition to start level 7. In this case, the OSGi Framework must first complete the
 * transition to start level 3 before it transitions to start level 7."
 *
 * <h3>Code location</h3>
 * {@code core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java:83} ({@code synchronized
 * setFrameworkStartLevel}) and {@code :97-133} ({@code synchronized setFrameworkStartLevelInternal}) share the monitor
 * with {@code increaseFrameworkStartLevel} (line 139) / {@code decreaseFrameworkStartLevel} (line 186), which hold it
 * on the "StartLevel Thread" while bundles are started or stopped, so a caller blocks; and the direction of a request
 * is chosen when it is made ({@code if (level > getFrameworkStartLevel())}, line 107, compares with the
 * <em>active</em> level), so a request queued behind a running increase to 10 becomes "increase to 3", which does
 * nothing when it runs. Unchanged since WF41.
 *
 * <h3>Root cause</h3>
 * The request methods synchronize on the same monitor as the long-running start level change, and the comparison
 * that decides between increase and decrease is made at request time instead of when the request is processed.
 *
 * <h3>Reproduction</h3>
 * <pre>
 * cd jbosgi-framework
 * export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
 * export JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"
 * mvn -Dmaven.compiler.proc=full install -DskipTests
 * mvn -Dmaven.compiler.proc=full -pl itest test -Dtest=StartLevelRequestsOutOfOrderIssueTestCase
 * </pre>
 * (-Dmaven.compiler.proc=full works around tests/01 LoggerImplementationMissingIssueTestCase on JDK 23+; the locale only matters for locale-dependent framework tests.)
 * <h3>Observed</h3>
 * <pre>
 * java.lang.AssertionError: setStartLevel must return immediately and change the start level asynchronously, but the
 *   second call blocked for 2019 ms until the first start level change had finished
 * (other interleaving, exploratory run: "After setStartLevel(10) followed by setStartLevel(3) the framework must end
 *   at start level 3 expected:&lt;3&gt; but was:&lt;10&gt;")
 * </pre>
 *
 * <h3>Expected</h3>
 * Both calls return immediately; the framework first reaches 10, then moves down to 3 (stopping the bundle with start
 * level 5); the STARTLEVEL_CHANGED event of the second request is fired at level 3.
 *
 * <h3>Reference behaviour</h3>
 * Not run as a differential test.
 *
 * <h3>Suggested fix</h3>
 * Do not synchronize the request methods with the change itself; queue a single "move to level L" task per request
 * on the start level executor and let its run method compare L with the active level when it runs, then increase or
 * decrease.
 *
 * <h3>Regression vs WF41</h3>
 * no (same code on WF41).
 */
public class StartLevelRequestsOutOfOrderIssueTestCase extends OSGiTest {

    private static final long SLOW_START_MILLIS = 2000;

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() & (Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING)) != 0) {
            framework.stop();
            framework.waitForStop(20000);
        }
    }

    @Test
    public void testLowerRequestAfterHigherRequest() throws Exception {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, new File("target/issues-osgi-store").getAbsolutePath());
        props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        framework = ServiceLoader.loadService(FrameworkFactory.class).newFramework(props);
        framework.start();

        // A bundle at start level 5 whose activator takes a while, so that the second request comes during the first
        Bundle bundle = installSlowBundle();
        bundle.adapt(BundleStartLevel.class).setStartLevel(5);
        bundle.start();

        FrameworkStartLevel startLevel = framework.adapt(FrameworkStartLevel.class);
        final CountDownLatch secondDone = new CountDownLatch(1);
        startLevel.setStartLevel(10);
        long start = System.currentTimeMillis();
        startLevel.setStartLevel(3, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                secondDone.countDown();
            }
        });
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("The second start level request must complete", secondDone.await(20, TimeUnit.SECONDS));

        // Depending on which thread gets the start level manager's monitor first, the second request is either queued
        // in the wrong direction (the framework ends at 10) or the caller is blocked until the first change finished
        assertEquals("After setStartLevel(10) followed by setStartLevel(3) the framework must end at start level 3", 3, startLevel.getStartLevel());
        assertTrue("setStartLevel must return immediately and change the start level asynchronously, but the second call blocked for " + elapsed
                + " ms until the first start level change had finished", elapsed < SLOW_START_MILLIS / 2);
        assertEquals("The bundle with start level 5 must be stopped at start level 3", Bundle.RESOLVED, bundle.getState());
    }

    private Bundle installSlowBundle() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "slow-start-bundle");
        archive.addClasses(SlowActivator.class);
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleActivator(SlowActivator.class);
                builder.addImportPackages("org.osgi.framework");
                return builder.openStream();
            }
        });
        return framework.getBundleContext().installBundle(archive.getName(), archive.as(ZipExporter.class).exportAsInputStream());
    }

    /** Takes two seconds to start */
    public static class SlowActivator implements BundleActivator {

        @Override
        public void start(BundleContext context) throws Exception {
            Thread.sleep(2000);
        }

        @Override
        public void stop(BundleContext context) {
        }
    }
}
