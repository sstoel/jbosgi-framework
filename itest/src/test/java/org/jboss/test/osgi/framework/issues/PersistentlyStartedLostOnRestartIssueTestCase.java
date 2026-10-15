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
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * After a framework restart, a persistently started bundle whose start level is above the beginning start level
 * loses its autostart setting: isPersistentlyStarted() returns false and raising the framework start level does not
 * start it any more.
 *
 * <h3>Severity</h3>
 * major - persistent bundle state is lost on every restart for bundles that use start levels above the beginning
 * start level (a common way to stage the start of applications); after the restart they stay RESOLVED.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.StartLevelManagerImpl.BundleStartLevelState}.
 *
 * <h3>Found by</h3>
 * fuzz workstream, stateful API fuzzer ({@code Stateful}, seed 1, 1 thread x 300 random install/start/stop/update/
 * start level/refresh operations): the persistent-state oracle compares (location, start level, persistently started)
 * before a framework stop and after a restart from the same storage; {@code ps=true} became {@code ps=false} for the
 * bundles at start level 2 and 3. Minimized to the scenario of this test; compared with Equinox 3.21.
 *
 * <h3>Spec reference</h3>
 * Bundle.start(int) javadoc (OSGi Core R8, 10.1.5.33): "If the START_TRANSIENT option is not set then set this
 * bundle's autostart setting to Started ... When the Framework is restarted and this bundle's autostart setting is not
 * Stopped, this bundle must be automatically started." BundleStartLevel.isPersistentlyStarted (10.5.2.3): "Returns
 * whether the bundle's autostart setting indicates it must be started. The autostart setting of a bundle indicates
 * whether the bundle is to be started when its start level is reached." 9.3.1 Changing the Active Start Level: "If the
 * requested start level is higher than the active start level, the Framework must increase the start level by one and
 * then start all bundles that meet the following criteria: Bundles that are persistently marked started, and Bundles
 * that have a bundle start level equal to the new active start level."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java:350-357}:
 * the {@code BundleStartLevelState} constructor restores the start level from the bundle's {@code StorageState}, but
 * not the {@code started} flag ({@code StorageState.isPersistentlyStarted()}), which stays false. Only the bundles
 * that {@code BootstrapBundlesResolve/Activate} start during the launch (start level &lt;= beginning start level) get
 * the flag back through {@code start()}; {@code increaseStartLevel} (:161-172) then skips the others because
 * {@code state.isStarted()} is false. Code unchanged by the R8 branch.
 *
 * <h3>Root cause</h3>
 * The in-memory autostart setting is not initialized from persistent storage when a bundle is re-created at launch.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=PersistentlyStartedLostOnRestartIssueTestCase} (in jbosgi-framework).
 *
 * <h3>Observed</h3>
 * <pre>
 * testAutostartSettingSurvivesRestart: The autostart setting of the bundle at start level 2 must survive the restart
 *   (isPersistentlyStarted() is false after the restart)
 * testStartedWhenStartLevelReachedAfterRestart: The persistently started bundle at start level 3 must be ACTIVE once the
 *   framework start level is 3 after the restart expected:&lt;32&gt; but was:&lt;2&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * After the restart the bundle is still persistently started, and it becomes ACTIVE when the framework start level
 * reaches its start level.
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: after the restart isPersistentlyStarted() is true for the bundles at start level 2 and 3, and both are
 * ACTIVE after setStartLevel(3).
 *
 * <h3>Suggested fix</h3>
 * In the BundleStartLevelState constructor also set {@code started = storageState != null &&
 * storageState.isPersistentlyStarted()} (and the activation policy flag, if it is kept separately).
 *
 * <h3>Regression vs WF41</h3>
 * no - same behaviour with the WF41 framework (6.6.0.Final).
 *
 * @since 13-Oct-2026
 */
public class PersistentlyStartedLostOnRestartIssueTestCase {

    @Test
    public void testAutostartSettingSurvivesRestart() throws Exception {
        String storage = new File("target/persistently-started-restart").getAbsolutePath();
        Framework framework = newFramework(storage, true);
        framework.start();
        try {
            Bundle bundle = framework.getBundleContext().installBundle("persistently-started", new ByteArrayInputStream(bundle("persistently-started")));
            bundle.adapt(BundleStartLevel.class).setStartLevel(2);
            bundle.start();
            assertTrue("Precondition: persistently started before the restart", bundle.adapt(BundleStartLevel.class).isPersistentlyStarted());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }

        framework = newFramework(storage, false);
        framework.start();
        try {
            Bundle bundle = framework.getBundleContext().getBundle("persistently-started");
            assertTrue("The autostart setting of the bundle at start level 2 must survive the restart",
                    bundle.adapt(BundleStartLevel.class).isPersistentlyStarted());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testStartedWhenStartLevelReachedAfterRestart() throws Exception {
        String storage = new File("target/persistently-started-level").getAbsolutePath();
        Framework framework = newFramework(storage, true);
        framework.start();
        try {
            Bundle bundle = framework.getBundleContext().installBundle("persistently-started-level", new ByteArrayInputStream(bundle("persistently-started-level")));
            bundle.adapt(BundleStartLevel.class).setStartLevel(3);
            bundle.start();
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }

        framework = newFramework(storage, false);
        framework.start();
        try {
            setFrameworkStartLevel(framework, 3);
            Bundle bundle = framework.getBundleContext().getBundle("persistently-started-level");
            assertEquals("The persistently started bundle at start level 3 must be ACTIVE once the framework start level is 3 after the restart",
                    Bundle.ACTIVE, bundle.getState());
        } finally {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    private static void setFrameworkStartLevel(Framework framework, int level) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        framework.adapt(FrameworkStartLevel.class).setStartLevel(level, new FrameworkListener() {
            public void frameworkEvent(FrameworkEvent event) {
                latch.countDown();
            }
        });
        assertTrue("Start level change completes", latch.await(30, TimeUnit.SECONDS));
    }

    private static Framework newFramework(String storage, boolean clean) {
        Map<String, String> props = new HashMap<String, String>();
        props.put(Constants.FRAMEWORK_STORAGE, storage);
        if (clean)
            props.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        FrameworkFactory factory = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        return factory.newFramework(props);
    }

    private static byte[] bundle(String symbolicName) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, symbolicName);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return baos.toByteArray();
    }
}
