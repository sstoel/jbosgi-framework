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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.jboss.test.osgi.framework.launch.AbstractFrameworkLaunchTest;
import org.junit.After;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;

/**
 * The initial bundle start level set with FrameworkStartLevel.setInitialBundleStartLevel is not persisted: after a
 * restart of the framework (a new Framework object on the same storage, or stop and start of the same object) it is
 * back to 1, and bundles installed afterwards get start level 1.
 *
 * <h3>Severity</h3>
 * minor - management agents that set the initial bundle start level once (e.g. to keep newly installed bundles from
 * starting before configuration is in place) silently lose that setting at the next restart.
 *
 * <h3>Component</h3>
 * jbosgi-framework, core, {@code org.jboss.osgi.framework.internal.StartLevelManagerImpl}
 * ({@code initialBundleStartLevel}).
 *
 * <h3>Found by</h3>
 * resolver workstream, restart-persistence sweep (Equinox differential, $SP/explore/resolver/restart/Restart.java
 * "basic" and Restart2.java): Equinox 3.21 returns the set value after a restart, jbosgi returns 1.
 *
 * <h3>Spec reference</h3>
 * FrameworkStartLevel.setInitialBundleStartLevel javadoc (OSGi Core R8, org.osgi.framework.startlevel 1.0,
 * https://docs.osgi.org/specification/osgi.core/8.0.0/framework.startlevel.html): "The initial bundle start level will
 * be set to the specified start level. The initial bundle start level value will be persistently recorded by the
 * Framework. When a Bundle is installed via BundleContext.installBundle, it is assigned the initial bundle start level
 * value. The default initial bundle start level value is 1 unless this method has been called to assign a different
 * initial bundle start level value."
 *
 * <h3>Code location</h3>
 * jbosgi-framework {@code core/src/main/java/org/jboss/osgi/framework/internal/StartLevelManagerImpl.java:61}
 * ({@code private AtomicInteger initialBundleStartLevel = new AtomicInteger(1);}) and {@code :294-296}
 * ({@code setInitialBundleStartLevel} only sets the field; nothing writes or reads it from the storage area).
 * Pre-existing (same on WF41).
 *
 * <h3>Root cause</h3>
 * The value is kept in memory only.
 *
 * <h3>Reproduction</h3>
 * {@code JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home mvn -Dmaven.compiler.proc=full
 * -pl itest test -Dtest=InitialBundleStartLevelNotPersistedIssueTestCase} (in jbosgi-framework, after
 * {@code mvn install -DskipTests -Dmaven.compiler.proc=full}).
 *
 * <h3>Observed</h3>
 * <pre>
 * testInitialBundleStartLevelAfterRelaunch: The initial bundle start level must survive a restart expected:&lt;3&gt;
 *   but was:&lt;1&gt;
 * testInitialBundleStartLevelAfterStopStart: The initial bundle start level must survive stop and start of the
 *   framework expected:&lt;3&gt; but was:&lt;1&gt;
 * </pre>
 *
 * <h3>Expected</h3>
 * getInitialBundleStartLevel() returns 3 after the restart and a newly installed bundle gets start level 3 (until the
 * storage is cleaned).
 *
 * <h3>Reference behaviour</h3>
 * Equinox 3.21: 3 after a relaunch on the same storage and after stop/start of the same Framework object.
 *
 * <h3>Suggested fix</h3>
 * Store the value in the framework's persistent storage (e.g. a property file next to the bundle storage, like the
 * bundle start levels in StorageState) when it is set, and read it when the framework initializes (unless the storage
 * is cleaned).
 *
 * <h3>Regression vs WF41</h3>
 * no - same in-memory field on WF41.
 *
 * @since 13-Oct-2026
 */
public class InitialBundleStartLevelNotPersistedIssueTestCase extends AbstractFrameworkLaunchTest {

    private Framework framework;

    @After
    public void tearDown() throws Exception {
        if (framework != null && (framework.getState() == Bundle.ACTIVE || framework.getState() == Bundle.STARTING)) {
            framework.stop();
            framework.waitForStop(10000);
        }
    }

    @Test
    public void testInitialBundleStartLevelAfterRelaunch() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        framework.adapt(FrameworkStartLevel.class).setInitialBundleStartLevel(3);
        framework.stop();
        framework.waitForStop(10000);

        // A new framework on the same storage, not cleaned
        framework = newFramework(getFrameworkInitProperties(false));
        framework.start();
        assertEquals("The initial bundle start level must survive a restart", 3, framework.adapt(FrameworkStartLevel.class).getInitialBundleStartLevel());
        Bundle bundle = install("initial-sl");
        assertEquals("A bundle installed after the restart gets the initial bundle start level", 3, bundle.adapt(BundleStartLevel.class).getStartLevel());
    }

    @Test
    public void testInitialBundleStartLevelAfterStopStart() throws Exception {
        framework = newFramework(getFrameworkInitProperties(true));
        framework.start();
        framework.adapt(FrameworkStartLevel.class).setInitialBundleStartLevel(3);
        framework.stop();
        framework.waitForStop(10000);
        framework.start();
        assertEquals("The initial bundle start level must survive stop and start of the framework", 3, framework.adapt(FrameworkStartLevel.class).getInitialBundleStartLevel());
    }

    private Bundle install(String name) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, name.replace('-', '.'));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new JarOutputStream(baos, manifest).close();
        return framework.getBundleContext().installBundle(name, new ByteArrayInputStream(baos.toByteArray()));
    }
}
